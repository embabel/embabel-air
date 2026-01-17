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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an EntityView interface for auto-registration.
 *
 * <p>Interfaces annotated with this annotation will be automatically discovered
 * and registered with the EntityViewService via classpath scanning.
 *
 * <p>Example:
 * <pre>{@code
 * @EntityViewFor(
 *     entity = Customer.class,
 *     description = "Customer account with reservations and loyalty status"
 * )
 * public interface CustomerView extends EntityView<Customer> {
 *
 *     @LlmTool(description = "Get customer reservations")
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
 *         return summary();
 *     }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EntityViewFor {

    /**
     * The entity class this view is for.
     */
    Class<?> entity();

    /**
     * Description of the entity for the finder tool.
     * This helps the LLM understand what this entity represents.
     */
    String description();
}
