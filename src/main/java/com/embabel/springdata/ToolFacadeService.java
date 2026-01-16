package com.embabel.springdata;

import com.embabel.agent.api.annotation.LlmTool;
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
import java.util.List;
import java.util.Map;

/**
 * Creates LLM tools from objects with @LlmTool-annotated methods,
 * automatically wrapping each tool invocation in a transaction
 * and reloading entity fields from Spring Data repositories.
 *
 * <p>Usage:
 * <pre>
 * var tools = entityTools.createTools(new CustomerTools(customer));
 * context.ai().withTools(tools)...
 * </pre>
 */
public class ToolFacadeService {

    private static final Logger logger = LoggerFactory.getLogger(ToolFacadeService.class);

    private final TransactionTemplate transactionTemplate;
    private final Repositories repositories;
    private final ObjectMapper objectMapper;

    public ToolFacadeService(
            TransactionTemplate transactionTemplate,
            ListableBeanFactory beanFactory) {
        this.transactionTemplate = transactionTemplate;
        this.repositories = new Repositories(beanFactory);
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Create Tool instances from an object's @LlmTool-annotated methods.
     * Each tool invocation will be wrapped in a transaction with entity fields reloaded.
     *
     * @param toolObject Object containing @LlmTool-annotated methods
     * @return List of Tool instances ready for LLM use
     */
    public List<Tool> createTools(Object toolObject) {
        var entityBindings = discoverEntityBindings(toolObject);
        var tools = new ArrayList<Tool>();

        for (Method method : toolObject.getClass().getDeclaredMethods()) {
            var annotation = method.getAnnotation(LlmTool.class);
            if (annotation != null) {
                method.setAccessible(true);
                tools.add(new TransactionalMethodTool(
                        toolObject,
                        method,
                        annotation,
                        entityBindings,
                        transactionTemplate,
                        objectMapper
                ));
            }
        }

        if (tools.isEmpty()) {
            throw new IllegalArgumentException(
                    "No @LlmTool-annotated methods found on " + toolObject.getClass().getSimpleName());
        }

        return tools;
    }

    private List<EntityFieldBinding> discoverEntityBindings(Object toolObject) {
        List<EntityFieldBinding> bindings = new ArrayList<>();
        for (Field field : toolObject.getClass().getDeclaredFields()) {
            var repoOpt = repositories.getRepositoryFor(field.getType());
            if (repoOpt.isPresent()) {
                field.setAccessible(true);
                try {
                    Object entity = field.get(toolObject);
                    if (entity != null) {
                        var entityInfo = repositories.getEntityInformationFor(field.getType());
                        Object id = entityInfo.getId(entity);
                        @SuppressWarnings("unchecked")
                        var repo = (CrudRepository<Object, Object>) repoOpt.get();
                        bindings.add(new EntityFieldBinding(field, repo, id));
                    }
                } catch (IllegalAccessException e) {
                    throw new RuntimeException("Failed to access field: " + field.getName(), e);
                }
            }
        }
        return bindings;
    }

    private record EntityFieldBinding(Field field, CrudRepository<Object, Object> repository, Object id) {
    }

    /**
     * Tool implementation that wraps method invocation in a transaction
     * and reloads entity fields before each call.
     */
    private static class TransactionalMethodTool implements Tool {

        private final Object instance;
        private final Method method;
        private final LlmTool annotation;
        private final List<EntityFieldBinding> entityBindings;
        private final TransactionTemplate transactionTemplate;
        private final ObjectMapper objectMapper;
        private final Definition definition;

        TransactionalMethodTool(
                Object instance,
                Method method,
                LlmTool annotation,
                List<EntityFieldBinding> entityBindings,
                TransactionTemplate transactionTemplate,
                ObjectMapper objectMapper) {
            this.instance = instance;
            this.method = method;
            this.annotation = annotation;
            this.entityBindings = entityBindings;
            this.transactionTemplate = transactionTemplate;
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
        public Result call(String input) {
            return transactionTemplate.execute(status -> {
                try {
                    // Reload entities within transaction
                    for (var binding : entityBindings) {
                        Object freshEntity = binding.repository().findById(binding.id())
                                .orElseThrow(() -> new IllegalStateException(
                                        "Entity not found: " + binding.id()));
                        binding.field().set(instance, freshEntity);
                    }

                    // Parse input and invoke method
                    Object[] args = parseArguments(input);
                    Object result = method.invoke(instance, args);
                    return convertResult(result);

                } catch (Exception e) {
                    logger.error("Error invoking tool '{}': {}", definition.getName(), e.getMessage(), e);
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
                java.lang.reflect.Parameter[] params = method.getParameters();
                Object[] args = new Object[params.length];

                for (int i = 0; i < params.length; i++) {
                    Object value = inputMap.get(params[i].getName());
                    args[i] = convertToType(value, params[i].getType());
                }

                return args;
            } catch (Exception e) {
                logger.warn("Failed to parse tool input: {}", e.getMessage());
                return new Object[method.getParameterCount()];
            }
        }

        private Object convertToType(Object value, Class<?> targetType) {
            if (value == null) return null;
            if (targetType.isInstance(value)) return value;

            // Handle common conversions
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

            // Complex types via ObjectMapper
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
