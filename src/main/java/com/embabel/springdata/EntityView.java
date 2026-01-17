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

import com.embabel.agent.api.common.LlmReference;

/**
 * A view over an entity that exposes tools to the LLM.
 *
 * <p>Extend this interface and annotate methods with {@code @LlmTool} to expose
 * entity operations. The {@link #getEntity()} method is provided by the framework
 * via proxy. Override {@link #summary()} and {@link #fullText()} for custom formatting,
 * or leave them unimplemented to use reflection-based defaults.
 *
 * <p>Implements {@link LlmReference} so views can be added directly to prompt runners
 * and conversations. The view provides both content ({@link #notes()}) and tools.
 *
 * <p>Example:
 * <pre>{@code
 * @LlmView
 * public interface CustomerView extends EntityView<Customer> {
 *
 *     @LlmTool(description = "Get customer's reservations")
 *     default List<Reservation> getReservations() {
 *         return getEntity().getReservations();
 *     }
 *
 *     // Optionally override summary/fullText for custom formatting
 * }
 * }</pre>
 *
 * @param <E> The entity type
 */
public interface EntityView<E> extends LlmReference {

    /**
     * Marker exception thrown by default implementations to signal
     * that the framework should use its reflection-based strategy.
     */
    class UseDefaultStrategy extends RuntimeException {
        public static final UseDefaultStrategy INSTANCE = new UseDefaultStrategy();
        private UseDefaultStrategy() {}
    }

    /**
     * Get the wrapped entity.
     *
     * <p>This method is implemented by the framework proxy. Calling it in
     * default methods will return the entity passed to
     * {@link EntityViewService#viewOf(Object)}.
     *
     * @return The wrapped entity
     * @throws UnsupportedOperationException if called outside a proxy context
     */
    default E getEntity() {
        throw new UnsupportedOperationException(
                "getEntity() must be called on a proxy created by EntityViewService.viewOf()");
    }

    /**
     * Return a short summary of the entity.
     * Override for custom formatting; default uses reflection (entity type + ID).
     */
    default String summary() {
        throw UseDefaultStrategy.INSTANCE;
    }

    /**
     * Return detailed text representation of the entity.
     * Override for custom formatting; default uses reflection (simple properties + collection sizes).
     */
    default String fullText() {
        throw UseDefaultStrategy.INSTANCE;
    }

    /**
     * LlmReference implementation - delegates to fullText().
     * Provides the detailed entity content for the prompt.
     */
    @Override
    default String notes() {
        return fullText();
    }

    /**
     * LlmReference name - provided by the proxy based on entity class.
     */
    @Override
    default String getName() {
        throw UseDefaultStrategy.INSTANCE;
    }

    /**
     * LlmReference description - provided by the proxy from @LlmView or entity class name.
     */
    @Override
    default String getDescription() {
        throw UseDefaultStrategy.INSTANCE;
    }
}
