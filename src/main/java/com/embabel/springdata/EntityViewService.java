/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.springdata;

import com.embabel.agent.api.annotation.LlmTool;
import com.embabel.agent.api.reference.LlmReference;
import com.embabel.agent.api.tool.MatryoshkaTool;
import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.api.tool.progressive.UnfoldingTool;
import com.embabel.common.textio.template.NoSuchTemplateException;
import com.embabel.common.textio.template.TemplateRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vavr.collection.Traversable;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.support.Repositories;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates transactional tools from EntityView implementations.
 *
 * <p>For an EntityView, this service:
 * <ul>
 *   <li>Discovers @LlmTool methods on the view class</li>
 *   <li>Creates tools that reload the entity and recreate the view for each invocation</li>
 *   <li>Ensures all tool executions happen within transactions</li>
 *   <li>Optionally renders summary/fullText from Jinja templates</li>
 * </ul>
 *
 * <h2>Template Support</h2>
 * <p>If a {@link TemplateRenderer} is provided, the {@link EntityView#summary()} and
 * {@link EntityView#fullText()} methods can be rendered from Jinja templates:
 * <ul>
 *   <li>{@code prompts/views/<simplename>_short.jinja} for summary()</li>
 *   <li>{@code prompts/views/<simplename>_long.jinja} for fullText()</li>
 * </ul>
 * <p>The entity is passed to the template with the lowercase simple name as the variable
 * (e.g., {@code customer} for a {@code Customer} entity). If the template doesn't exist,
 * the default method implementation is used.
 *
 * <p>Example template at {@code prompts/views/customer_short.jinja}:
 * <pre>{@code
 * Customer: {{ customer.name }} ({{ customer.email }})
 * }</pre>
 *
 * <p>Example usage:
 * <pre>{@code
 * @LlmView(entity = Customer.class, description = "Customer account")
 * public interface CustomerView extends EntityView<Customer> {
 *
 *     @LlmTool(description = "Get customer's reservation history")
 *     default List<Reservation> getReservations() {
 *         return getEntity().getReservations();
 *     }
 *
 *     @Override
 *     default String summary() {
 *         return "Customer: " + getEntity().getName();
 *     }
 *
 *     @Override
 *     default String fullText() {
 *         return summary() + "\nEmail: " + getEntity().getEmail();
 *     }
 * }
 *
 * // Create a view and tools
 * var view = entityViewService.viewOf(customer);
 * var tools = entityViewService.createTools(view);
 * }</pre>
 */
public class EntityViewService {

    private static final Logger logger = LoggerFactory.getLogger(EntityViewService.class);
    private static final String VIEW_TEMPLATE_PATH = "prompts/views/";

    private final TransactionTemplate transactionTemplate;
    private final Repositories repositories;
    private final ObjectMapper objectMapper;
    private final @Nullable TemplateRenderer templateRenderer;

    // Maps entity class -> view interface class
    private final Map<Class<?>, Class<? extends EntityView<?>>> viewRegistry = new ConcurrentHashMap<>();
    // Maps entity class -> description for finder tool
    private final Map<Class<?>, String> descriptionRegistry = new ConcurrentHashMap<>();

    public EntityViewService(
            TransactionTemplate transactionTemplate,
            ListableBeanFactory beanFactory,
            @Nullable TemplateRenderer templateRenderer,
            String... basePackages) {
        this.transactionTemplate = transactionTemplate;
        this.repositories = new Repositories(beanFactory);
        this.objectMapper = new ObjectMapper();
        this.templateRenderer = templateRenderer;
        scanForEntityViews(basePackages);
    }

    // Package-private for testing
    EntityViewService(
            TransactionTemplate transactionTemplate,
            Repositories repositories,
            @Nullable TemplateRenderer templateRenderer) {
        this.transactionTemplate = transactionTemplate;
        this.repositories = repositories;
        this.objectMapper = new ObjectMapper();
        this.templateRenderer = templateRenderer;
    }

    /**
     * Scan for interfaces annotated with @LlmView and register them.
     */
    private void scanForEntityViews(String... basePackages) {
        // Override isCandidateComponent to allow interfaces (default only allows concrete classes)
        var scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                return beanDefinition.getMetadata().isInterface();
            }
        };
        scanner.addIncludeFilter(new AnnotationTypeFilter(LlmView.class));

        for (var basePackage : basePackages) {
            for (var bd : scanner.findCandidateComponents(basePackage)) {
                try {
                    var viewInterface = Class.forName(bd.getBeanClassName());
                    var annotation = viewInterface.getAnnotation(LlmView.class);
                    if (annotation != null && EntityView.class.isAssignableFrom(viewInterface)) {
                        var entityClass = annotation.entity();
                        if (entityClass == Void.class) {
                            entityClass = inferEntityClass(viewInterface);
                        }
                        var description = annotation.description();
                        if (description.isEmpty()) {
                            description = entityClass.getSimpleName();
                        }
                        registerView(entityClass, viewInterface, description);
                    }
                } catch (ClassNotFoundException e) {
                    logger.warn("Failed to load EntityView class: {}", bd.getBeanClassName(), e);
                }
            }
        }

        logger.info("Registered {} EntityViews: {}", viewRegistry.size(),
                viewRegistry.entrySet().stream()
                        .map(e -> e.getKey().getSimpleName() + " -> " + e.getValue().getSimpleName())
                        .toList());
    }

    /**
     * Infer the entity class from the generic type parameter of EntityView.
     * Supports direct extension (interface FooView extends EntityView&lt;Foo&gt;)
     * and intermediate interfaces.
     */
    static Class<?> inferEntityClass(Class<?> viewInterface) {
        // Check direct interfaces
        for (var type : viewInterface.getGenericInterfaces()) {
            var result = extractEntityClass(type);
            if (result != null) {
                return result;
            }
        }

        // Check parent interfaces recursively
        for (var parent : viewInterface.getInterfaces()) {
            if (EntityView.class.isAssignableFrom(parent)) {
                var result = inferEntityClass(parent);
                if (result != null && result != Object.class) {
                    return result;
                }
            }
        }

        throw new IllegalArgumentException(
                "Cannot infer entity class from " + viewInterface.getName() +
                        ". Ensure it extends EntityView<YourEntity> or specify entity explicitly in @LlmView.");
    }

    private static Class<?> extractEntityClass(java.lang.reflect.Type type) {
        if (type instanceof java.lang.reflect.ParameterizedType pt) {
            if (pt.getRawType() == EntityView.class) {
                var args = pt.getActualTypeArguments();
                if (args.length > 0 && args[0] instanceof Class<?> entityClass) {
                    return entityClass;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void registerView(Class<?> entityClass, Class<?> viewInterface, String description) {
        viewRegistry.put(entityClass, (Class<? extends EntityView<?>>) viewInterface);
        descriptionRegistry.put(entityClass, description);
        logger.debug("Registered EntityView {} for entity {}",
                viewInterface.getSimpleName(), entityClass.getSimpleName());
    }

    /**
     * Create an EntityView for the given entity.
     *
     * <p>Looks up the registered EntityView interface for the entity's class
     * and creates a dynamic proxy implementing that interface.
     *
     * @param entity The entity to wrap
     * @param <E>    Entity type
     * @return An EntityView wrapping the entity
     * @throws IllegalArgumentException if no EntityView is registered for the entity class
     */
    @SuppressWarnings("unchecked")
    public <E> EntityView<E> viewOf(E entity) {
        var entityClass = entity.getClass();
        var viewInterface = viewRegistry.get(entityClass);

        if (viewInterface == null) {
            throw new IllegalArgumentException(
                    "No EntityView registered for " + entityClass.getSimpleName() +
                            ". Create an interface extending EntityView<" + entityClass.getSimpleName() +
                            "> annotated with @LlmView.");
        }

        var entitySimpleName = entityClass.getSimpleName().toLowerCase();
        var description = descriptionRegistry.getOrDefault(entityClass, entityClass.getSimpleName());

        // Lazily create tools on first access
        var toolsHolder = new Object() {
            List<Tool> tools = null;
        };

        return (EntityView<E>) Proxy.newProxyInstance(
                viewInterface.getClassLoader(),
                new Class<?>[]{viewInterface},
                (proxy, method, args) -> {
                    // Handle getEntity()
                    if ("getEntity".equals(method.getName()) && method.getParameterCount() == 0) {
                        return entity;
                    }

                    // Handle toView(F f) - delegate to viewOf
                    if ("toView".equals(method.getName()) && method.getParameterCount() == 1) {
                        var relatedEntity = args[0];
                        if (relatedEntity == null) {
                            return null;
                        }
                        return viewOf(relatedEntity);
                    }

                    // Handle LlmReference.getName()
                    if ("getName".equals(method.getName()) && method.getParameterCount() == 0) {
                        return entitySimpleName;
                    }

                    // Handle LlmReference.getDescription()
                    if ("getDescription".equals(method.getName()) && method.getParameterCount() == 0) {
                        return description;
                    }

                    // Handle LlmReference.tools()
                    if ("tools".equals(method.getName()) && method.getParameterCount() == 0) {
                        if (toolsHolder.tools == null) {
                            toolsHolder.tools = createTools((EntityView<E>) proxy);
                        }
                        return toolsHolder.tools;
                    }

                    // Handle LlmReference.notes() - reload entity in transaction for lazy loading
                    if ("notes".equals(method.getName()) && method.getParameterCount() == 0) {
                        var entityId = getEntityId(entity);
                        var result = transactionTemplate.execute(status -> {
                            var freshView = reloadView(entityClass, entityId);
                            return freshView.fullText();
                        });
                        return result != null ? result : "";
                    }

                    // Handle summary() - try template, then user override, then strategy
                    if ("summary".equals(method.getName()) && method.getParameterCount() == 0) {
                        var rendered = tryRenderTemplate(entitySimpleName + "_short", entity, entitySimpleName);
                        if (rendered != null) {
                            return rendered;
                        }
                        try {
                            return invokeDefault(proxy, method, args, viewInterface);
                        } catch (EntityView.UseDefaultStrategy e) {
                            return EntityViewStrategy.DEFAULT.summary(entity);
                        }
                    }

                    // Handle fullText() - try template, then user override, then strategy
                    if ("fullText".equals(method.getName()) && method.getParameterCount() == 0) {
                        var rendered = tryRenderTemplate(entitySimpleName + "_long", entity, entitySimpleName);
                        if (rendered != null) {
                            return rendered;
                        }
                        try {
                            return invokeDefault(proxy, method, args, viewInterface);
                        } catch (EntityView.UseDefaultStrategy e) {
                            return EntityViewStrategy.DEFAULT.fullText(entity);
                        }
                    }

                    // Handle default methods
                    if (method.isDefault()) {
                        return invokeDefault(proxy, method, args, viewInterface);
                    }

                    // Handle Object methods
                    return switch (method.getName()) {
                        case "toString" -> viewInterface.getSimpleName() + "[" + entity + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "getId" -> getEntityId(entity);
                        default -> throw new UnsupportedOperationException(
                                "Method " + method.getName() + " is not a default method on " + viewInterface.getSimpleName());
                    };

                }
        );
    }

    /**
     * Try to render a template for an entity. Returns null if template doesn't exist.
     */
    private @Nullable String tryRenderTemplate(String templateSuffix, Object entity, String entityVarName) {
        if (templateRenderer == null) {
            return null;
        }
        var templateName = VIEW_TEMPLATE_PATH + templateSuffix + ".jinja";
        try {
            return templateRenderer.renderLoadedTemplate(templateName, Map.of(entityVarName, entity));
        } catch (NoSuchTemplateException e) {
            return null;
        }
    }

    /**
     * Invoke a default method on the proxy.
     */
    private static Object invokeDefault(Object proxy, java.lang.reflect.Method method, Object[] args, Class<?> viewInterface) throws Throwable {
        return java.lang.invoke.MethodHandles.lookup()
                .findSpecial(
                        viewInterface,
                        method.getName(),
                        java.lang.invoke.MethodType.methodType(
                                method.getReturnType(),
                                method.getParameterTypes()),
                        viewInterface)
                .bindTo(proxy)
                .invokeWithArguments(args != null ? args : new Object[0]);
    }

    /**
     * Reload an entity within a transaction and create a fresh view.
     */
    @SuppressWarnings("unchecked")
    private <E> EntityView<E> reloadView(Class<?> entityClass, Object entityId) {
        var repo = (CrudRepository<E, Object>) repositories.getRepositoryFor(entityClass)
                .orElseThrow(() -> new IllegalStateException("No repository for " + entityClass));
        E freshEntity = repo.findById(entityId)
                .orElseThrow(() -> new IllegalStateException("Entity not found: " + entityId));
        return viewOf(freshEntity);
    }

    /**
     * Manually register an EntityView interface for an entity type.
     *
     * @param entityClass   The entity class
     * @param viewInterface The EntityView interface
     * @param description   Description of the entity for the finder tool
     * @param <E>           Entity type
     * @return this for fluent chaining
     */
    @SuppressWarnings("unchecked")
    public <E> EntityViewService register(
            Class<E> entityClass,
            Class<? extends EntityView<E>> viewInterface,
            String description) {
        viewRegistry.put(entityClass, (Class<? extends EntityView<?>>) viewInterface);
        descriptionRegistry.put(entityClass, description);
        logger.debug("Registered EntityView {} for entity {}", viewInterface.getSimpleName(), entityClass.getSimpleName());
        return this;
    }

    /**
     * Create finder tools for multiple entity types.
     *
     * @param entityClasses The entity classes to create finders for
     * @return List of MatryoshkaTools, one per entity class
     * @throws IllegalArgumentException if no EntityView is registered for any entity class
     */
    public List<MatryoshkaTool> findersFor(Class<?>... entityClasses) {
        var finders = new ArrayList<MatryoshkaTool>();
        for (var entityClass : entityClasses) {
            finders.add(finderFor(entityClass));
        }
        return finders;
    }

    /**
     * Create a finder tool for an entity type.
     *
     * <p>The returned tool is a MatryoshkaTool that accepts an entity ID. When invoked,
     * it finds the entity, wraps it in its EntityView (if registered), and exposes all
     * @LlmTool methods from that view as inner tools.
     *
     * <p>If no EntityView is registered but the entity class has @LlmTool methods,
     * falls back to using those entity methods directly with EntityViewStrategy.DEFAULT
     * for rendering.
     *
     * @param entityClass The entity class to create a finder for
     * @param <E>         Entity type
     * @return A MatryoshkaTool that finds entities by ID and exposes their tools
     * @throws IllegalArgumentException if no EntityView registered and no @LlmTool methods on entity
     */
    public <E> MatryoshkaTool finderFor(Class<E> entityClass) {
        if (repositories.getRepositoryFor(entityClass).isEmpty()) {
            throw new IllegalArgumentException(
                    "No repository found for " + entityClass.getSimpleName() +
                            ". Ensure a CrudRepository exists for this entity.");
        }

        if (viewRegistry.containsKey(entityClass)) {
            var description = descriptionRegistry.getOrDefault(entityClass, entityClass.getSimpleName());
            return new EntityFinderMatryoshkaTool<>(
                    entityClass,
                    this::viewOf,
                    description,
                    transactionTemplate,
                    repositories,
                    objectMapper,
                    this
            );
        }

        // Fall back to entity @LlmTool methods
        if (!hasEntityTools(entityClass)) {
            throw new IllegalArgumentException(
                    "No EntityView registered and no @LlmTool methods on " + entityClass.getSimpleName() +
                            ". Create an interface annotated with @LlmView or add @LlmTool to entity methods.");
        }

        var description = descriptionRegistry.getOrDefault(entityClass, entityClass.getSimpleName());
        return new EntityFinderMatryoshkaTool<>(
                entityClass,
                null,
                description,
                transactionTemplate,
                repositories,
                objectMapper,
                this
        );
    }

    /**
     * Check if an EntityView is registered for an entity class.
     */
    public boolean hasViewFor(Class<?> entityClass) {
        return viewRegistry.containsKey(entityClass);
    }

    /**
     * Check if an entity class has any methods annotated with @LlmTool.
     */
    public boolean hasEntityTools(Class<?> entityClass) {
        for (Method method : entityClass.getMethods()) {
            if (method.getAnnotation(LlmTool.class) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Create an LlmReference for an entity without requiring an EntityView interface.
     *
     * <p>Uses @LlmTool methods on the entity class directly for tools,
     * and EntityViewStrategy.DEFAULT for rendering notes.
     *
     * @param entity The entity to create a reference for
     * @param <E>    Entity type
     * @return An LlmReference with tools from entity @LlmTool methods and default notes
     */
    public <E> LlmReference entityReferenceFor(E entity) {
        var entityClass = entity.getClass();
        var entitySimpleName = entityClass.getSimpleName().toLowerCase();
        var description = descriptionRegistry.getOrDefault(entityClass, entityClass.getSimpleName());

        var tools = createEntityTools(entity);

        var entityId = getEntityId(entity);
        var notes = transactionTemplate.execute(status -> {
            @SuppressWarnings("unchecked")
            var repo = (CrudRepository<E, Object>) repositories.getRepositoryFor(entityClass)
                    .orElseThrow(() -> new IllegalStateException("No repository for " + entityClass));
            E freshEntity = repo.findById(entityId)
                    .orElseThrow(() -> new IllegalStateException("Entity not found: " + entityId));
            return EntityViewStrategy.DEFAULT.fullText(freshEntity);
        });

        return LlmReference.of(entitySimpleName, description, tools, notes != null ? notes : "");
    }

    /**
     * Create tools from @LlmTool methods on an entity class directly.
     *
     * <p>Unlike createTools(EntityView), this scans the entity class itself
     * for @LlmTool methods and creates TransactionalEntityTool wrappers.
     *
     * @param entity The entity instance
     * @param <E>    Entity type
     * @return List of tools from @LlmTool methods on the entity class
     */
    public <E> List<Tool> createEntityTools(E entity) {
        var tools = new ArrayList<Tool>();
        var entityClass = entity.getClass();
        var entityId = getEntityId(entity);

        for (Method method : entityClass.getMethods()) {
            var annotation = method.getAnnotation(LlmTool.class);
            if (annotation != null) {
                tools.add(new TransactionalEntityTool<>(
                        entityClass,
                        entityId,
                        method,
                        annotation,
                        transactionTemplate,
                        repositories,
                        objectMapper,
                        this
                ));
            }
        }

        logger.info(Tool.formatToolTree(entityClass.getSimpleName(), tools));
        return tools;
    }

    /**
     * Create UnfoldingTools from custom finder methods on a Spring Data repository interface.
     *
     * <p>Scans the repository interface for custom finder methods (declared directly,
     * not inherited from CrudRepository/JpaRepository) and creates a RepositoryFinderTool
     * for each single-entity-returning finder.
     *
     * @param repositoryInterface The repository interface class (e.g., ReservationRepository.class)
     * @return List of tools wrapping custom finder methods
     */
    @SuppressWarnings("unchecked")
    public List<Tool> repositoryToolsFor(Class<?> repositoryInterface) {
        // Resolve entity class from the repository's generic type parameter
        var entityClass = inferEntityClassFromRepository(repositoryInterface);

        var repo = repositories.getRepositoryFor(entityClass)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No repository found for " + entityClass.getSimpleName()));

        var tools = new ArrayList<Tool>();
        for (Method method : repositoryInterface.getDeclaredMethods()) {
            if (method.isDefault() || method.isSynthetic()) continue;

            var returnType = method.getReturnType();
            boolean returnsEntity = returnType.equals(entityClass);
            boolean returnsOptional = returnType.equals(Optional.class);
            boolean returnsList = List.class.isAssignableFrom(returnType) || Collection.class.isAssignableFrom(returnType);

            if (returnsEntity || returnsOptional || returnsList) {
                tools.add(new RepositoryFinderTool<>(
                        entityClass,
                        repo,
                        method,
                        transactionTemplate,
                        repositories,
                        objectMapper,
                        this
                ));
            }
        }

        logger.info(Tool.formatToolTree(repositoryInterface.getSimpleName(), tools));
        return tools;
    }

    /**
     * Infer the entity class from a repository interface's generic type parameter.
     */
    static Class<?> inferEntityClassFromRepository(Class<?> repositoryInterface) {
        for (var type : repositoryInterface.getGenericInterfaces()) {
            if (type instanceof ParameterizedType pt) {
                var args = pt.getActualTypeArguments();
                if (args.length > 0 && args[0] instanceof Class<?> entityClass) {
                    return entityClass;
                }
            }
        }
        throw new IllegalArgumentException(
                "Cannot infer entity class from " + repositoryInterface.getName());
    }

    /**
     * Create tools from an EntityView.
     *
     * <p>Each tool invocation will:
     * <ol>
     *   <li>Run within a transaction</li>
     *   <li>Reload the entity from the database</li>
     *   <li>Create a fresh view using viewOf()</li>
     *   <li>Invoke the @LlmTool method on the fresh view</li>
     * </ol>
     *
     * @param view The view instance
     * @param <E>  Entity type
     * @return List of tools from @LlmTool methods on the view interface
     */
    @SuppressWarnings("unchecked")
    public <E> List<Tool> createTools(EntityView<E> view) {
        var entity = view.getEntity();
        var entityClass = (Class<E>) entity.getClass();
        return createTools(view, e -> (EntityView<E>) viewOf(e));
    }

    /**
     * Create tools from an EntityView with a custom factory.
     *
     * @param view        The view instance
     * @param viewFactory Factory to create new view instances from reloaded entities
     * @param <E>         Entity type
     * @param <V>         View type
     * @return List of tools from @LlmTool methods on the view interface
     */
    public <E, V extends EntityView<E>> List<Tool> createTools(
            V view,
            java.util.function.Function<E, V> viewFactory) {

        var tools = new ArrayList<Tool>();
        var entity = view.getEntity();
        var entityClass = entity.getClass();
        var entityId = getEntityId(entity);

        // Find the view interface (could be a proxy)
        var viewInterface = findViewInterface(view.getClass(), entityClass);
        var viewName = viewInterface != null ? viewInterface.getSimpleName() : view.getClass().getSimpleName();

        // Scan the interface for @LlmTool methods
        var methodSource = viewInterface != null ? viewInterface : view.getClass();
        for (Method method : methodSource.getMethods()) {
            var annotation = method.getAnnotation(LlmTool.class);
            if (annotation != null) {
                tools.add(new TransactionalViewTool<>(
                        entityClass,
                        entityId,
                        viewFactory,
                        method,
                        annotation,
                        transactionTemplate,
                        repositories,
                        objectMapper,
                        this
                ));
            }
        }

        logger.info(Tool.formatToolTree(viewName, tools));
        return tools;
    }

    /**
     * Find the EntityView interface for a given class.
     */
    private Class<?> findViewInterface(Class<?> clazz, Class<?> entityClass) {
        // Check if it's a registered view interface
        var registered = viewRegistry.get(entityClass);
        if (registered != null) {
            return registered;
        }

        // Search through interfaces
        for (var iface : clazz.getInterfaces()) {
            if (EntityView.class.isAssignableFrom(iface) && iface != EntityView.class) {
                return iface;
            }
        }
        return null;
    }

    private Object getEntityId(Object entity) {
        var entityInfo = repositories.getEntityInformationFor(entity.getClass());
        return entityInfo.getId(entity);
    }

    /**
     * Tool that reloads entity, recreates view, and invokes method within transaction.
     */
    static class TransactionalViewTool<E, V extends EntityView<E>> implements Tool {

        private final Class<?> entityClass;
        private final Object entityId;
        private final java.util.function.Function<E, V> viewFactory;
        private final Method method;
        private final LlmTool annotation;
        private final TransactionTemplate transactionTemplate;
        private final Repositories repositories;
        private final ObjectMapper objectMapper;
        private final EntityViewService entityViewService;
        private final Definition definition;

        TransactionalViewTool(
                Class<?> entityClass,
                Object entityId,
                java.util.function.Function<E, V> viewFactory,
                Method method,
                LlmTool annotation,
                TransactionTemplate transactionTemplate,
                Repositories repositories,
                ObjectMapper objectMapper,
                EntityViewService entityViewService) {
            this.entityClass = entityClass;
            this.entityId = entityId;
            this.viewFactory = viewFactory;
            this.method = method;
            this.annotation = annotation;
            this.entityViewService = entityViewService;
            this.transactionTemplate = transactionTemplate;
            this.repositories = repositories;
            this.objectMapper = objectMapper;
            this.definition = buildDefinition();
        }

        private Definition buildDefinition() {
            var name = annotation.name().isEmpty() ? method.getName() : annotation.name();
            var description = annotation.description();

            var toolParams = new ArrayList<Tool.Parameter>();
            for (var param : method.getParameters()) {
                var paramAnnotation = param.getAnnotation(LlmTool.Param.class);
                var paramDesc = paramAnnotation != null ? paramAnnotation.description() : param.getName();
                var required = paramAnnotation == null || paramAnnotation.required();

                toolParams.add(new Tool.Parameter(
                        param.getName(),
                        mapJavaTypeToParameterType(param.getType()),
                        paramDesc,
                        required,
                        null
                ));
            }

            return Definition.create(name, description, InputSchema.of(toolParams.toArray(new Tool.Parameter[0])));
        }

        private ParameterType mapJavaTypeToParameterType(Class<?> type) {
            if (type == String.class) return ParameterType.STRING;
            if (type == int.class || type == Integer.class || type == long.class || type == Long.class)
                return ParameterType.INTEGER;
            if (type == double.class || type == Double.class || type == float.class || type == Float.class)
                return ParameterType.NUMBER;
            if (type == boolean.class || type == Boolean.class) return ParameterType.BOOLEAN;
            if (type.isArray() || List.class.isAssignableFrom(type)) return ParameterType.ARRAY;
            return ParameterType.OBJECT;
        }

        @Override
        public Definition getDefinition() {
            return definition;
        }

        @Override
        public Metadata getMetadata() {
            return Metadata.create(annotation.returnDirect());
        }

        @Override
        @SuppressWarnings("unchecked")
        public Result call(String input) {
            return transactionTemplate.execute(status -> {
                try {
                    // Reload entity within transaction
                    var repo = (CrudRepository<E, Object>) repositories.getRepositoryFor(entityClass)
                            .orElseThrow(() -> new IllegalStateException("No repository for " + entityClass));

                    E freshEntity = repo.findById(entityId)
                            .orElseThrow(() -> new IllegalStateException("Entity not found: " + entityId));

                    // Create fresh view
                    V freshView = viewFactory.apply(freshEntity);

                    // Parse input and invoke method
                    Object[] args = parseArguments(input);
                    Object result = method.invoke(freshView, args);
                    return convertResult(result);

                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    return Result.error(cause.getMessage(), cause);
                }
            });
        }

        @SuppressWarnings("unchecked")
        private Object[] parseArguments(String input) {
            if (input == null || input.isBlank()) {
                return new Object[method.getParameterCount()];
            }

            try {
                Map<String, Object> inputMap = objectMapper.readValue(input, Map.class);
                var params = method.getParameters();
                Object[] args = new Object[params.length];

                for (int i = 0; i < params.length; i++) {
                    Object value = inputMap.get(params[i].getName());
                    args[i] = convertToType(value, params[i].getType());
                }

                return args;
            } catch (Exception e) {
                return new Object[method.getParameterCount()];
            }
        }

        private Object convertToType(Object value, Class<?> targetType) {
            if (value == null) return null;
            if (targetType.isInstance(value)) return value;

            if (targetType == String.class) return value.toString();
            if (targetType == int.class || targetType == Integer.class) {
                return value instanceof Number n ? n.intValue() : Integer.parseInt(value.toString());
            }
            if (targetType == long.class || targetType == Long.class) {
                return value instanceof Number n ? n.longValue() : Long.parseLong(value.toString());
            }
            if (targetType == double.class || targetType == Double.class) {
                return value instanceof Number n ? n.doubleValue() : Double.parseDouble(value.toString());
            }
            if (targetType == boolean.class || targetType == Boolean.class) {
                return value instanceof Boolean b ? b : Boolean.parseBoolean(value.toString());
            }

            return objectMapper.convertValue(value, targetType);
        }

        private Result convertResult(Object result) {
            if (result == null) return Result.text("No result");
            if (result instanceof String s) return Result.text(s);
            if (result instanceof Result r) return r;

            // If result is an EntityView, use its summary
            if (result instanceof EntityView<?> view) {
                return Result.text(view.summary());
            }

            // If result is an entity with a registered view, wrap and summarize
            if (entityViewService.hasViewFor(result.getClass())) {
                return Result.text(entityViewService.viewOf(result).summary());
            }

            // If result is a collection, check if items are entities or views
            if (result instanceof Collection<?> collection && !collection.isEmpty()) {
                var first = collection.iterator().next();

                // Collection of EntityViews
                if (first instanceof EntityView<?>) {
                    var summaries = collection.stream()
                            .map(e -> ((EntityView<?>) e).summary())
                            .toList();
                    return toJsonResult(summaries);
                }

                // Collection of entities with registered views
                if (entityViewService.hasViewFor(first.getClass())) {
                    var summaries = collection.stream()
                            .map(e -> entityViewService.viewOf(e).summary())
                            .toList();
                    return toJsonResult(summaries);
                }
            }

            // Handle Vavr collections
            if (result instanceof Traversable<?> vavrCollection && !vavrCollection.isEmpty()) {
                var first = vavrCollection.head();

                // Vavr collection of EntityViews
                if (first instanceof EntityView<?>) {
                    var summaries = vavrCollection
                            .map(e -> ((EntityView<?>) e).summary())
                            .toJavaList();
                    return toJsonResult(summaries);
                }

                // Vavr collection of entities with registered views
                if (entityViewService.hasViewFor(first.getClass())) {
                    var summaries = vavrCollection
                            .map(e -> entityViewService.viewOf(e).summary())
                            .toJavaList();
                    return toJsonResult(summaries);
                }
            }

            return toJsonResult(result);
        }

        private Result toJsonResult(Object value) {
            try {
                return Result.text(objectMapper.writeValueAsString(value));
            } catch (Exception e) {
                return Result.text(value.toString());
            }
        }
    }

    /**
     * MatryoshkaTool that finds an entity by ID and exposes its EntityView's tools,
     * or entity @LlmTool methods if no view is registered.
     */
    static class EntityFinderMatryoshkaTool<E> implements MatryoshkaTool {

        private final Class<E> entityClass;
        private final Class<?> idType;
        // Null when no EntityView is registered (falls back to entity @LlmTool methods)
        private final java.util.function.Function<E, ? extends EntityView<E>> viewFactory;
        private final TransactionTemplate transactionTemplate;
        private final Repositories repositories;
        private final ObjectMapper objectMapper;
        private final EntityViewService entityViewService;
        private final Definition definition;

        // Inner tools are created dynamically when the entity is found
        private List<Tool> currentInnerTools = List.of();

        EntityFinderMatryoshkaTool(
                Class<E> entityClass,
                java.util.function.Function<E, ? extends EntityView<E>> viewFactory,
                String description,
                TransactionTemplate transactionTemplate,
                Repositories repositories,
                ObjectMapper objectMapper,
                EntityViewService entityViewService) {
            this.entityClass = entityClass;
            this.idType = repositories.getEntityInformationFor(entityClass).getIdType();
            this.viewFactory = viewFactory;
            this.transactionTemplate = transactionTemplate;
            this.repositories = repositories;
            this.objectMapper = objectMapper;
            this.entityViewService = entityViewService;
            this.definition = buildDefinition(entityClass, description);
        }

        private Definition buildDefinition(Class<E> entityClass, String description) {
            var name = "find_" + entityClass.getSimpleName().toLowerCase();
            var desc = description + " Pass the ID to access tools for this " + entityClass.getSimpleName() + ".";
            var paramType = isNumericIdType() ? ParameterType.INTEGER : ParameterType.STRING;
            var paramDesc = isNumericIdType()
                    ? "The numeric database ID"
                    : "The UUID shown as (id=...) in summaries. Do NOT use booking references or codes.";

            return Definition.create(name, desc, InputSchema.of(
                    new Tool.Parameter("id", paramType, paramDesc, true, null)
            ));
        }

        private boolean isNumericIdType() {
            return idType == Long.class || idType == long.class ||
                    idType == Integer.class || idType == int.class;
        }

        @Override
        public Definition getDefinition() {
            return definition;
        }

        @Override
        public List<Tool> getInnerTools() {
            return currentInnerTools;
        }

        @Override
        public boolean getRemoveOnInvoke() {
            return true;
        }

        @Override
        public List<Tool> selectTools(String input) {
            return currentInnerTools;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Result call(String input) {
            return transactionTemplate.execute(status -> {
                try {
                    // Parse the ID from input
                    Object entityId = parseEntityId(input);
                    if (entityId == null) {
                        return Result.error("ID is required", null);
                    }

                    // Find the entity
                    var repo = (CrudRepository<E, Object>) repositories.getRepositoryFor(entityClass)
                            .orElseThrow(() -> new IllegalStateException("No repository for " + entityClass));

                    E entity = repo.findById(entityId)
                            .orElseThrow(() -> new IllegalStateException(
                                    entityClass.getSimpleName() + " not found with ID: " + entityId));

                    String entityText;
                    if (viewFactory != null) {
                        // Use EntityView path
                        EntityView<E> view = viewFactory.apply(entity);
                        currentInnerTools = entityViewService.createTools(view, (java.util.function.Function<E, EntityView<E>>) viewFactory);
                        entityText = view.fullText();
                    } else {
                        // Fall back to entity @LlmTool methods
                        currentInnerTools = entityViewService.createEntityTools(entity);
                        entityText = EntityViewStrategy.DEFAULT.fullText(entity);
                    }

                    // Return full text and list of available tools as JSON
                    var toolNames = currentInnerTools.stream()
                            .map(t -> t.getDefinition().getName())
                            .toList();

                    var result = Map.of(
                            "entity", entityText,
                            "availableTools", toolNames
                    );
                    return Result.withArtifact(objectMapper.writeValueAsString(result), entity);
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    return Result.error(cause.getMessage(), cause);
                }
            });
        }

        @SuppressWarnings("unchecked")
        private Object parseEntityId(String input) {
            if (input == null || input.isBlank()) {
                return null;
            }

            try {
                Map<String, Object> inputMap = objectMapper.readValue(input, Map.class);
                var idValue = inputMap.get("id");
                if (idValue == null) {
                    return null;
                }

                // Convert to the correct ID type
                if (idType == String.class) {
                    return idValue.toString();
                }
                if (idType == Long.class || idType == long.class) {
                    return idValue instanceof Number n ? n.longValue() : Long.parseLong(idValue.toString());
                }
                if (idType == Integer.class || idType == int.class) {
                    return idValue instanceof Number n ? n.intValue() : Integer.parseInt(idValue.toString());
                }

                return idValue;
            } catch (Exception e) {
                return null;
            }
        }
    }

    /**
     * Tool that reloads entity and invokes @LlmTool methods on the entity directly
     * (not on a view interface). Used by createEntityTools().
     */
    static class TransactionalEntityTool<E> implements Tool {

        private final Class<?> entityClass;
        private final Object entityId;
        private final Method method;
        private final LlmTool annotation;
        private final TransactionTemplate transactionTemplate;
        private final Repositories repositories;
        private final ObjectMapper objectMapper;
        private final EntityViewService entityViewService;
        private final Definition definition;

        TransactionalEntityTool(
                Class<?> entityClass,
                Object entityId,
                Method method,
                LlmTool annotation,
                TransactionTemplate transactionTemplate,
                Repositories repositories,
                ObjectMapper objectMapper,
                EntityViewService entityViewService) {
            this.entityClass = entityClass;
            this.entityId = entityId;
            this.method = method;
            this.annotation = annotation;
            this.entityViewService = entityViewService;
            this.transactionTemplate = transactionTemplate;
            this.repositories = repositories;
            this.objectMapper = objectMapper;
            this.definition = buildDefinition();
        }

        private Definition buildDefinition() {
            var name = annotation.name().isEmpty() ? method.getName() : annotation.name();
            var description = annotation.description();

            var toolParams = new ArrayList<Tool.Parameter>();
            for (var param : method.getParameters()) {
                var paramAnnotation = param.getAnnotation(LlmTool.Param.class);
                var paramDesc = paramAnnotation != null ? paramAnnotation.description() : param.getName();
                var required = paramAnnotation == null || paramAnnotation.required();

                toolParams.add(new Tool.Parameter(
                        param.getName(),
                        mapJavaTypeToParameterType(param.getType()),
                        paramDesc,
                        required,
                        null
                ));
            }

            return Definition.create(name, description, InputSchema.of(toolParams.toArray(new Tool.Parameter[0])));
        }

        private ParameterType mapJavaTypeToParameterType(Class<?> type) {
            if (type == String.class) return ParameterType.STRING;
            if (type == int.class || type == Integer.class || type == long.class || type == Long.class)
                return ParameterType.INTEGER;
            if (type == double.class || type == Double.class || type == float.class || type == Float.class)
                return ParameterType.NUMBER;
            if (type == boolean.class || type == Boolean.class) return ParameterType.BOOLEAN;
            if (type.isArray() || List.class.isAssignableFrom(type)) return ParameterType.ARRAY;
            return ParameterType.OBJECT;
        }

        @Override
        public Definition getDefinition() {
            return definition;
        }

        @Override
        public Metadata getMetadata() {
            return Metadata.create(annotation.returnDirect());
        }

        @Override
        @SuppressWarnings("unchecked")
        public Result call(String input) {
            return transactionTemplate.execute(status -> {
                try {
                    var repo = (CrudRepository<E, Object>) repositories.getRepositoryFor(entityClass)
                            .orElseThrow(() -> new IllegalStateException("No repository for " + entityClass));

                    E freshEntity = repo.findById(entityId)
                            .orElseThrow(() -> new IllegalStateException("Entity not found: " + entityId));

                    Object[] args = parseArguments(input);
                    Object result = method.invoke(freshEntity, args);
                    return convertResult(result);

                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    return Result.error(cause.getMessage(), cause);
                }
            });
        }

        @SuppressWarnings("unchecked")
        private Object[] parseArguments(String input) {
            if (input == null || input.isBlank()) {
                return new Object[method.getParameterCount()];
            }

            try {
                Map<String, Object> inputMap = objectMapper.readValue(input, Map.class);
                var params = method.getParameters();
                Object[] args = new Object[params.length];

                for (int i = 0; i < params.length; i++) {
                    Object value = inputMap.get(params[i].getName());
                    args[i] = convertToType(value, params[i].getType());
                }

                return args;
            } catch (Exception e) {
                return new Object[method.getParameterCount()];
            }
        }

        private Object convertToType(Object value, Class<?> targetType) {
            if (value == null) return null;
            if (targetType.isInstance(value)) return value;

            if (targetType == String.class) return value.toString();
            if (targetType == int.class || targetType == Integer.class) {
                return value instanceof Number n ? n.intValue() : Integer.parseInt(value.toString());
            }
            if (targetType == long.class || targetType == Long.class) {
                return value instanceof Number n ? n.longValue() : Long.parseLong(value.toString());
            }
            if (targetType == double.class || targetType == Double.class) {
                return value instanceof Number n ? n.doubleValue() : Double.parseDouble(value.toString());
            }
            if (targetType == boolean.class || targetType == Boolean.class) {
                return value instanceof Boolean b ? b : Boolean.parseBoolean(value.toString());
            }

            return objectMapper.convertValue(value, targetType);
        }

        private Result convertResult(Object result) {
            if (result == null) return Result.text("No result");
            if (result instanceof String s) return Result.text(s);
            if (result instanceof Result r) return r;

            if (result instanceof EntityView<?> view) {
                return Result.text(view.summary());
            }

            if (entityViewService.hasViewFor(result.getClass())) {
                return Result.text(entityViewService.viewOf(result).summary());
            }

            if (result instanceof Collection<?> collection && !collection.isEmpty()) {
                var first = collection.iterator().next();
                if (first instanceof EntityView<?>) {
                    var summaries = collection.stream()
                            .map(e -> ((EntityView<?>) e).summary())
                            .toList();
                    return toJsonResult(summaries);
                }
                if (entityViewService.hasViewFor(first.getClass())) {
                    var summaries = collection.stream()
                            .map(e -> entityViewService.viewOf(e).summary())
                            .toList();
                    return toJsonResult(summaries);
                }

                // Fallback: use default strategy for entity collections without views
                var summaries = collection.stream()
                        .map(EntityViewStrategy.DEFAULT::fullText)
                        .toList();
                return toJsonResult(summaries);
            }

            if (result instanceof Traversable<?> vavrCollection && !vavrCollection.isEmpty()) {
                var first = vavrCollection.head();
                if (first instanceof EntityView<?>) {
                    var summaries = vavrCollection.map(e -> ((EntityView<?>) e).summary()).toJavaList();
                    return toJsonResult(summaries);
                }
                if (entityViewService.hasViewFor(first.getClass())) {
                    var summaries = vavrCollection.map(e -> entityViewService.viewOf(e).summary()).toJavaList();
                    return toJsonResult(summaries);
                }
            }

            return toJsonResult(result);
        }

        private Result toJsonResult(Object value) {
            try {
                return Result.text(objectMapper.writeValueAsString(value));
            } catch (Exception e) {
                return Result.text(value.toString());
            }
        }
    }

    /**
     * UnfoldingTool that wraps a Spring Data repository finder method.
     * When invoked, calls the finder, then exposes entity @LlmTool methods (or view tools)
     * as inner tools.
     */
    static class RepositoryFinderTool<E> implements MatryoshkaTool {

        private final Class<?> entityClass;
        private final Object repository;
        private final Method finderMethod;
        private final TransactionTemplate transactionTemplate;
        private final Repositories repositories;
        private final ObjectMapper objectMapper;
        private final EntityViewService entityViewService;
        private final boolean returnsList;
        private final Definition definition;

        private List<Tool> currentInnerTools = List.of();

        RepositoryFinderTool(
                Class<?> entityClass,
                Object repository,
                Method finderMethod,
                TransactionTemplate transactionTemplate,
                Repositories repositories,
                ObjectMapper objectMapper,
                EntityViewService entityViewService) {
            this.entityClass = entityClass;
            this.repository = repository;
            this.finderMethod = finderMethod;
            this.transactionTemplate = transactionTemplate;
            this.repositories = repositories;
            this.objectMapper = objectMapper;
            this.entityViewService = entityViewService;
            var returnType = finderMethod.getReturnType();
            this.returnsList = List.class.isAssignableFrom(returnType) || Collection.class.isAssignableFrom(returnType);
            this.definition = buildDefinition();
        }

        private Definition buildDefinition() {
            // Convert method name to tool-friendly format: findByBookingReference -> find_reservation_by_booking_reference
            var entityName = entityClass.getSimpleName().toLowerCase();
            var methodName = finderMethod.getName();
            var toolName = camelToSnake(methodName.replaceFirst("^findBy", "find_" + entityName + "_by_"));

            var desc = returnsList
                    ? "Find " + entityClass.getSimpleName() + " records using " + methodName
                    : "Find a " + entityClass.getSimpleName() + " using " + methodName;

            var toolParams = new ArrayList<Tool.Parameter>();
            for (var param : finderMethod.getParameters()) {
                var paramAnnotation = param.getAnnotation(LlmTool.Param.class);
                var paramDesc = paramAnnotation != null ? paramAnnotation.description() : param.getName();
                var required = paramAnnotation == null || paramAnnotation.required();

                toolParams.add(new Tool.Parameter(
                        param.getName(),
                        mapJavaTypeToParameterType(param.getType()),
                        paramDesc,
                        required,
                        null
                ));
            }

            return Definition.create(toolName, desc, InputSchema.of(toolParams.toArray(new Tool.Parameter[0])));
        }

        private static String camelToSnake(String name) {
            return name.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
        }

        private ParameterType mapJavaTypeToParameterType(Class<?> type) {
            if (type == String.class) return ParameterType.STRING;
            if (type == int.class || type == Integer.class || type == long.class || type == Long.class)
                return ParameterType.INTEGER;
            if (type == double.class || type == Double.class || type == float.class || type == Float.class)
                return ParameterType.NUMBER;
            if (type == boolean.class || type == Boolean.class) return ParameterType.BOOLEAN;
            if (type.isArray() || List.class.isAssignableFrom(type)) return ParameterType.ARRAY;
            return ParameterType.OBJECT;
        }

        @Override
        public Definition getDefinition() {
            return definition;
        }

        @Override
        public List<Tool> getInnerTools() {
            return currentInnerTools;
        }

        @Override
        public boolean getRemoveOnInvoke() {
            return true;
        }

        @Override
        public List<Tool> selectTools(String input) {
            return currentInnerTools;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Result call(String input) {
            return transactionTemplate.execute(status -> {
                try {
                    Object[] args = parseArguments(input);

                    // Invoke the repository finder method
                    Object rawResult = finderMethod.invoke(repository, args);

                    // Handle collection returns (e.g., findByCustomer_Id returning List<Reservation>)
                    if (rawResult instanceof Collection<?> collection) {
                        if (collection.isEmpty()) {
                            return Result.error("No " + entityClass.getSimpleName() + " records found", null);
                        }
                        var summaries = new ArrayList<String>();
                        for (var item : collection) {
                            E e = (E) item;
                            if (entityViewService.hasViewFor(entityClass)) {
                                summaries.add(entityViewService.viewOf(e).fullText());
                            } else {
                                summaries.add(EntityViewStrategy.DEFAULT.fullText(e));
                            }
                        }
                        currentInnerTools = List.of();
                        var result = Map.of(
                                "count", collection.size(),
                                "entities", summaries
                        );
                        return Result.withArtifact(objectMapper.writeValueAsString(result), collection);
                    }

                    // Handle single entity returns (Optional or direct)
                    E entity;
                    if (rawResult instanceof Optional<?> opt) {
                        entity = (E) opt.orElseThrow(() -> new IllegalStateException(
                                entityClass.getSimpleName() + " not found"));
                    } else if (rawResult == null) {
                        return Result.error(entityClass.getSimpleName() + " not found", null);
                    } else {
                        entity = (E) rawResult;
                    }

                    // Create inner tools from entity or view
                    String entityText;
                    if (entityViewService.hasViewFor(entityClass)) {
                        var view = entityViewService.viewOf(entity);
                        currentInnerTools = entityViewService.createTools(view);
                        entityText = view.fullText();
                    } else {
                        currentInnerTools = entityViewService.createEntityTools(entity);
                        entityText = EntityViewStrategy.DEFAULT.fullText(entity);
                    }

                    var toolNames = currentInnerTools.stream()
                            .map(t -> t.getDefinition().getName())
                            .toList();

                    var result = Map.of(
                            "entity", entityText,
                            "availableTools", toolNames
                    );
                    return Result.withArtifact(objectMapper.writeValueAsString(result), entity);
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    return Result.error(cause.getMessage(), cause);
                }
            });
        }

        @SuppressWarnings("unchecked")
        private Object[] parseArguments(String input) {
            if (input == null || input.isBlank()) {
                return new Object[finderMethod.getParameterCount()];
            }

            try {
                Map<String, Object> inputMap = objectMapper.readValue(input, Map.class);
                var params = finderMethod.getParameters();
                Object[] args = new Object[params.length];

                for (int i = 0; i < params.length; i++) {
                    Object value = inputMap.get(params[i].getName());
                    args[i] = convertToType(value, params[i].getType());
                }

                return args;
            } catch (Exception e) {
                return new Object[finderMethod.getParameterCount()];
            }
        }

        private Object convertToType(Object value, Class<?> targetType) {
            if (value == null) return null;
            if (targetType.isInstance(value)) return value;

            if (targetType == String.class) return value.toString();
            if (targetType == int.class || targetType == Integer.class) {
                return value instanceof Number n ? n.intValue() : Integer.parseInt(value.toString());
            }
            if (targetType == long.class || targetType == Long.class) {
                return value instanceof Number n ? n.longValue() : Long.parseLong(value.toString());
            }
            if (targetType == double.class || targetType == Double.class) {
                return value instanceof Number n ? n.doubleValue() : Double.parseDouble(value.toString());
            }
            if (targetType == boolean.class || targetType == Boolean.class) {
                return value instanceof Boolean b ? b : Boolean.parseBoolean(value.toString());
            }

            return objectMapper.convertValue(value, targetType);
        }
    }
}
