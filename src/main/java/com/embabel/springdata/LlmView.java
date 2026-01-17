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
 * Marks an EntityView interface for auto-registration with the LLM.
 *
 * <p>Interfaces annotated with this annotation will be automatically discovered
 * and registered with the EntityViewService via classpath scanning.
 *
 * <p>Minimal usage - everything is inferred:
 * <pre>{@code
 * @LlmView
 * public interface CustomerView extends EntityView<Customer> {
 *
 *     @LlmTool(description = "Get customer reservations")
 *     default List<Reservation> getReservations() {
 *         return getEntity().getReservations();
 *     }
 * }
 * }</pre>
 *
 * <p>With explicit values:
 * <pre>{@code
 * @LlmView(entity = Customer.class, description = "Customer account with loyalty status")
 * public interface CustomerView extends EntityView<Customer> { ... }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface LlmView {

    /**
     * The entity class this view is for.
     * Default: inferred from the generic type parameter of EntityView.
     */
    Class<?> entity() default Void.class;

    /**
     * Description of the entity for the finder tool.
     * Default: the entity class simple name (e.g., "Customer").
     */
    String description() default "";
}
