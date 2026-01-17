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

import jakarta.persistence.Id;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.Temporal;
import java.util.Collection;
import java.util.UUID;

/**
 * Strategy for generating default summary and fullText for EntityViews.
 * Used when the view interface doesn't override these methods.
 */
public interface EntityViewStrategy {

    /**
     * Default strategy using reflection.
     */
    EntityViewStrategy DEFAULT = new ReflectionStrategy();

    /**
     * Generate a summary for the entity.
     * Default: "EntityType (id=value)"
     */
    String summary(Object entity);

    /**
     * Generate full text for the entity.
     * Default: simple properties + collection sizes
     */
    String fullText(Object entity);

    /**
     * Reflection-based implementation.
     */
    class ReflectionStrategy implements EntityViewStrategy {

        @Override
        public String summary(Object entity) {
            var entityClass = entity.getClass();
            var id = findId(entity, entityClass);
            return "%s (id=%s)".formatted(entityClass.getSimpleName(), id);
        }

        @Override
        public String fullText(Object entity) {
            var entityClass = entity.getClass();
            var sb = new StringBuilder();
            sb.append(entityClass.getSimpleName()).append("\n");

            for (var method : entityClass.getMethods()) {
                if (!isGetter(method)) continue;
                var name = propertyName(method);
                if (name.equals("class")) continue;

                try {
                    var value = method.invoke(entity);
                    var type = method.getReturnType();

                    if (isSimpleType(type)) {
                        sb.append("  ").append(name).append(": ").append(value).append("\n");
                    } else if (Collection.class.isAssignableFrom(type) && value != null) {
                        sb.append("  ").append(name).append(": ")
                                .append(((Collection<?>) value).size()).append(" items\n");
                    }
                } catch (Exception ignored) {
                }
            }
            return sb.toString().trim();
        }

        private static Object findId(Object entity, Class<?> entityClass) {
            // Check fields for @Id
            for (var field : entityClass.getDeclaredFields()) {
                if (field.isAnnotationPresent(Id.class)) {
                    try {
                        field.setAccessible(true);
                        return field.get(entity);
                    } catch (Exception ignored) {
                    }
                }
            }
            // Try getId() method
            try {
                return entityClass.getMethod("getId").invoke(entity);
            } catch (Exception ignored) {
            }
            return "?";
        }

        private static boolean isGetter(Method method) {
            var name = method.getName();
            return method.getParameterCount() == 0
                    && !method.getReturnType().equals(void.class)
                    && (name.startsWith("get") || name.startsWith("is"));
        }

        private static String propertyName(Method method) {
            var name = method.getName();
            var prefix = name.startsWith("is") ? 2 : 3;
            if (name.length() <= prefix) return name;
            return Character.toLowerCase(name.charAt(prefix)) + name.substring(prefix + 1);
        }

        private static boolean isSimpleType(Class<?> type) {
            return type.isPrimitive()
                    || type == String.class
                    || Number.class.isAssignableFrom(type)
                    || type == Boolean.class
                    || type.isEnum()
                    || Temporal.class.isAssignableFrom(type)
                    || type == UUID.class
                    || type == BigDecimal.class
                    || type == BigInteger.class;
        }
    }
}
