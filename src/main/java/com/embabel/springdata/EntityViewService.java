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
import com.embabel.agent.api.common.LlmReference;
import com.embabel.agent.api.tool.MatryoshkaTool;
import com.embabel.agent.api.tool.Tool;
import com.embabel.common.textio.template.NoSuchTemplateException;
import com.embabel.common.textio.template.TemplateRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.support.Repositories;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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
 * @EntityViewFor(entity = Customer.class, description = "Customer account")
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
     * Scan for interfaces annotated with @EntityViewFor and register them.
     */
    private void scanForEntityViews(String... basePackages) {
        // Override isCandidateComponent to allow interfaces (default only allows concrete classes)
        var scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                return beanDefinition.getMetadata().isInterface();
            }
        };
        scanner.addIncludeFilter(new AnnotationTypeFilter(EntityViewFor.class));

        for (var basePackage : basePackages) {
            for (var bd : scanner.findCandidateComponents(basePackage)) {
                try {
                    var viewInterface = Class.forName(bd.getBeanClassName());
                    var annotation = viewInterface.getAnnotation(EntityViewFor.class);
                    if (annotation != null && EntityView.class.isAssignableFrom(viewInterface)) {
                        registerView(annotation.entity(), viewInterface, annotation.description());
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
                    "> annotated with @EntityViewFor.");
        }

        var entitySimpleName = entityClass.getSimpleName().toLowerCase();

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

                    // Handle summary() - try template first
                    if ("summary".equals(method.getName()) && method.getParameterCount() == 0) {
                        var rendered = tryRenderTemplate(entitySimpleName + "_short", entity, entitySimpleName);
                        if (rendered != null) {
                            return rendered;
                        }
                        // Fall through to default method
                    }

                    // Handle fullText() - try template first
                    if ("fullText".equals(method.getName()) && method.getParameterCount() == 0) {
                        var rendered = tryRenderTemplate(entitySimpleName + "_long", entity, entitySimpleName);
                        if (rendered != null) {
                            return rendered;
                        }
                        // Fall through to default method
                    }

                    // Handle default methods
                    if (method.isDefault()) {
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

                    // Handle Object methods
                    if ("toString".equals(method.getName())) {
                        return viewInterface.getSimpleName() + "[" + entity + "]";
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }

                    throw new UnsupportedOperationException(
                            "Method " + method.getName() + " is not a default method on " + viewInterface.getSimpleName());
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
     * Create an LlmReference for the given entity.
     *
     * <p>The returned reference:
     * <ul>
     *   <li>Creates an EntityView for the entity</li>
     *   <li>Exposes all @LlmTool methods from the view as tools</li>
     *   <li>Uses the view's summary() as notes</li>
     *   <li>Uses the view's fullText() for the prompt contribution</li>
     * </ul>
     *
     * @param entity The entity to create a reference for
     * @param <E>    Entity type
     * @return An LlmReference wrapping the entity's view and tools
     * @throws IllegalArgumentException if no EntityView is registered for the entity class
     */
    public <E> LlmReference makeReference(E entity) {
        var view = viewOf(entity);
        var tools = createTools(view);
        var entityClass = entity.getClass();
        var entityId = getEntityId(entity);
        var entityName = entityClass.getSimpleName().toLowerCase();
        var description = descriptionRegistry.getOrDefault(entityClass, entityClass.getSimpleName());

        return new LlmReference() {
            @Override
            public @NonNull String notes() {
                // Execute within transaction to allow lazy loading
                var result = transactionTemplate.execute(status -> {
                    var freshView = reloadView(entityClass, entityId);
                    return freshView.fullText();
                });
                return result != null ? result : "";
            }

            @Override
            public @NonNull List<Tool> tools() {
                return tools;
            }

            @Override
            public @NonNull String getDescription() {
                return description;
            }

            @Override
            public @NonNull String getName() {
                return entityName;
            }
        };
    }

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
     * Create a finder tool for an entity type.
     *
     * <p>The returned tool is a MatryoshkaTool that accepts an entity ID. When invoked,
     * it finds the entity, wraps it in its EntityView, and exposes all @LlmTool methods
     * from that view as inner tools.
     *
     * @param entityClass The entity class to create a finder for
     * @param <E>         Entity type
     * @return A MatryoshkaTool that finds entities by ID and exposes their view's tools
     * @throws IllegalArgumentException if no EntityView is registered for the entity class
     */
    public <E> MatryoshkaTool finderFor(Class<E> entityClass) {
        if (!viewRegistry.containsKey(entityClass)) {
            throw new IllegalArgumentException(
                    "No EntityView registered for " + entityClass.getSimpleName() +
                    ". Create an interface annotated with @EntityViewFor.");
        }

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

    /**
     * Check if an EntityView is registered for an entity class.
     */
    public boolean hasViewFor(Class<?> entityClass) {
        return viewRegistry.containsKey(entityClass);
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

        logToolTree(viewName, tools);
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
     * Log a tree of tools in Maven dependency tree style.
     */
    public static void logToolTree(String name, List<Tool> tools) {
        EntityNavigationService.logToolTree(name, tools);
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
            if (result == null) return Result.text("");
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
     * MatryoshkaTool that finds an entity by ID and exposes its EntityView's tools.
     */
    static class EntityFinderMatryoshkaTool<E> implements MatryoshkaTool {

        private final Class<E> entityClass;
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

            return Definition.create(name, desc, InputSchema.of(
                    new Tool.Parameter("id", ParameterType.INTEGER, "The entity ID", true, null)
            ));
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

                    // Create the view
                    EntityView<E> view = viewFactory.apply(entity);

                    // Create tools from the view
                    currentInnerTools = entityViewService.createTools(view, (java.util.function.Function<E, EntityView<E>>) viewFactory);

                    // Return summary and list of available tools
                    var toolNames = currentInnerTools.stream()
                            .map(t -> t.getDefinition().getName())
                            .toList();

                    return Result.text(view.summary() + "\n\nAvailable tools: " + String.join(", ", toolNames));

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
                if (idValue instanceof Number n) {
                    return n.longValue();
                }
                if (idValue instanceof String s) {
                    return Long.parseLong(s);
                }
                return idValue;
            } catch (Exception e) {
                return null;
            }
        }
    }
}
