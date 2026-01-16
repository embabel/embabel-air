package com.embabel.springdata;

import com.embabel.agent.api.annotation.LlmTool;
import com.embabel.agent.api.tool.MatryoshkaTool;
import com.embabel.agent.api.tool.Tool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.support.Repositories;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Creates navigable tool structures from entities.
 *
 * <p>For an entity, this service creates:
 * <ul>
 *   <li>Direct tools for each @LlmTool method on the entity</li>
 *   <li>MatryoshkaTool for each related entity that has @LlmTool methods</li>
 * </ul>
 *
 * <p>This enables progressive tool disclosure - the LLM sees the main entity's
 * tools plus facades for related entities. When it invokes a facade, that
 * entity's tools become available.
 */
public class EntityNavigationService {

    private static final Logger logger = LoggerFactory.getLogger(EntityNavigationService.class);

    private final TransactionTemplate transactionTemplate;
    private final Repositories repositories;
    private final ObjectMapper objectMapper;

    public EntityNavigationService(
            TransactionTemplate transactionTemplate,
            ListableBeanFactory beanFactory) {
        this(transactionTemplate, new Repositories(beanFactory));
    }

    // Package-private for testing
    EntityNavigationService(
            TransactionTemplate transactionTemplate,
            Repositories repositories) {
        this.transactionTemplate = transactionTemplate;
        this.repositories = repositories;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Create tools for an entity including navigation to related entities.
     *
     * @param entity The entity to create tools for
     * @return List of tools: direct tools + MatryoshkaTools for navigable relations
     */
    public List<Tool> makeNavigable(Object entity) {
        var tools = new ArrayList<Tool>();

        // Add direct tools from the entity's @LlmTool methods
        tools.addAll(createDirectTools(entity));

        // Add MatryoshkaTools for related entities that have tools
        tools.addAll(createRelationMatryoshkaTools(entity));

        logToolTree(entity.getClass().getSimpleName(), tools);

        return tools;
    }

    /**
     * Log a tree of tools in Maven dependency tree style.
     *
     * @param name  The name to display as the root
     * @param tools The tools to log
     */
    // TODO move to embabel-agent core
    public static void logToolTree(String name, List<Tool> tools) {
        if (tools.isEmpty()) {
            logger.warn("{} has no tools", name);
            return;
        }

        var sb = new StringBuilder();
        sb.append(name).append("\n");
        for (int i = 0; i < tools.size(); i++) {
            var tool = tools.get(i);
            var isLast = (i == tools.size() - 1);
            var prefix = isLast ? "└── " : "├── ";

            if (tool instanceof MatryoshkaTool mt) {
                sb.append(prefix).append(tool.getDefinition().getName())
                        .append(" (").append(mt.getInnerTools().size()).append(" inner tools)\n");
                var innerTools = mt.getInnerTools();
                for (int j = 0; j < innerTools.size(); j++) {
                    var inner = innerTools.get(j);
                    var innerIsLast = (j == innerTools.size() - 1);
                    var innerPrefix = (isLast ? "    " : "│   ") + (innerIsLast ? "└── " : "├── ");
                    sb.append(innerPrefix).append(inner.getDefinition().getName()).append("\n");
                }
            } else {
                sb.append(prefix).append(tool.getDefinition().getName()).append("\n");
            }
        }
        logger.info(sb.toString().trim());
    }

    /**
     * Create direct tools from an entity's @LlmTool methods.
     */
    List<Tool> createDirectTools(Object entity) {
        var tools = new ArrayList<Tool>();
        var entityClass = entity.getClass();
        Object entityId = null;

        for (Method method : entityClass.getDeclaredMethods()) {
            var annotation = method.getAnnotation(LlmTool.class);
            if (annotation != null) {
                if (entityId == null) {
                    entityId = getEntityId(entity);
                }
                method.setAccessible(true);
                tools.add(new TransactionalEntityTool(
                        entityClass,
                        entityId,
                        method,
                        annotation,
                        transactionTemplate,
                        repositories,
                        objectMapper
                ));
            }
        }

        return tools;
    }

    /**
     * Create MatryoshkaTools for related entities that have @LlmTool methods.
     */
    List<Tool> createRelationMatryoshkaTools(Object entity) {
        var tools = new ArrayList<Tool>();

        for (Field field : entity.getClass().getDeclaredFields()) {
            // Skip collections - only handle single relations
            if (Collection.class.isAssignableFrom(field.getType()) ||
                    Map.class.isAssignableFrom(field.getType())) {
                continue;
            }

            // Check if the field type has a repository (is an entity)
            var repoOpt = repositories.getRepositoryFor(field.getType());
            if (repoOpt.isEmpty()) {
                continue;
            }

            // Check if the related entity type has @LlmTool methods
            if (!hasLlmToolMethods(field.getType())) {
                continue;
            }

            // Get the related entity value
            field.setAccessible(true);
            Object relatedEntity;
            try {
                relatedEntity = field.get(entity);
            } catch (IllegalAccessException e) {
                logger.warn("Failed to access field {}: {}", field.getName(), e.getMessage());
                continue;
            }

            if (relatedEntity == null) {
                continue;
            }

            // Create MatryoshkaTool for this relation
            var innerTools = createDirectTools(relatedEntity);
            if (!innerTools.isEmpty()) {
                String name = field.getName() + "_tools";
                String description = "Access tools for " + field.getType().getSimpleName() +
                        ". Invoke to see available operations.";

                tools.add(MatryoshkaTool.of(name, description, innerTools, true));
                logger.debug("Created MatryoshkaTool '{}' with {} tools for relation {}",
                        name, innerTools.size(), field.getName());
            }
        }

        return tools;
    }

    /**
     * Check if a class has any @LlmTool annotated methods.
     */
    boolean hasLlmToolMethods(Class<?> clazz) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(LlmTool.class)) {
                return true;
            }
        }
        return false;
    }

    private Object getEntityId(Object entity) {
        var entityInfo = repositories.getEntityInformationFor(entity.getClass());
        return entityInfo.getId(entity);
    }

    /**
     * Tool that reloads entity within transaction before invoking method.
     */
    static class TransactionalEntityTool implements Tool {

        private final Class<?> entityClass;
        private final Object entityId;
        private final Method method;
        private final LlmTool annotation;
        private final TransactionTemplate transactionTemplate;
        private final Repositories repositories;
        private final ObjectMapper objectMapper;
        private final Definition definition;

        TransactionalEntityTool(
                Class<?> entityClass,
                Object entityId,
                Method method,
                LlmTool annotation,
                TransactionTemplate transactionTemplate,
                Repositories repositories,
                ObjectMapper objectMapper) {
            this.entityClass = entityClass;
            this.entityId = entityId;
            this.method = method;
            this.annotation = annotation;
            this.transactionTemplate = transactionTemplate;
            this.repositories = repositories;
            this.objectMapper = objectMapper;
            this.definition = buildDefinition();
        }

        private Definition buildDefinition() {
            String name = annotation.name().isEmpty() ? method.getName() : annotation.name();
            String description = annotation.description();

            var toolParams = new ArrayList<Tool.Parameter>();
            for (var param : method.getParameters()) {
                var paramAnnotation = param.getAnnotation(LlmTool.Param.class);
                String paramDesc = paramAnnotation != null ? paramAnnotation.description() : param.getName();
                boolean required = paramAnnotation == null || paramAnnotation.required();

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
                    var repo = (CrudRepository<Object, Object>) repositories.getRepositoryFor(entityClass)
                            .orElseThrow(() -> new IllegalStateException("No repository for " + entityClass));

                    Object freshEntity = repo.findById(entityId)
                            .orElseThrow(() -> new IllegalStateException("Entity not found: " + entityId));

                    // Parse input and invoke method
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
            if (result == null) return Result.text("");
            if (result instanceof String s) return Result.text(s);
            if (result instanceof Result r) return r;

            try {
                return Result.text(objectMapper.writeValueAsString(result));
            } catch (Exception e) {
                return Result.text(result.toString());
            }
        }
    }
}
