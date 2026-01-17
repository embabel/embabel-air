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

/**
 * A view over an entity that exposes tools to the LLM.
 *
 * <p>Extend this interface and annotate methods with {@code @LlmTool} to expose
 * entity operations. The {@link #getEntity()} and {@link #toView(Object)} methods
 * are provided by the framework via proxy - you only need to implement
 * {@link #summary()} and {@link #fullText()}.
 *
 * <p>Example:
 * <pre>{@code
 * @EntityViewFor(entity = Customer.class, description = "Customer account")
 * public interface CustomerView extends EntityView<Customer> {
 *
 *     @LlmTool(description = "Get customer's reservations")
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
 * }</pre>
 *
 * @param <E> The entity type
 */
public interface EntityView<E> {

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
     * Return a short summary of the entity for LLM context.
     * MUST include the id
     *
     * @return A concise description of the entity
     */
    String summary();

    /**
     * Return a detailed text representation of the entity.
     *
     * @return Full details about the entity
     */
    String fullText();
}
