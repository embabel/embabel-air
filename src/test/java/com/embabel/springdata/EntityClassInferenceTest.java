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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for entity class inference from generic type parameters.
 */
class EntityClassInferenceTest {

    @Nested
    class DirectExtension {

        @Test
        void infersEntityFromDirectExtension() {
            var entityClass = EntityViewService.inferEntityClass(DirectCustomerView.class);
            assertThat(entityClass).isEqualTo(Customer.class);
        }

        @Test
        void infersEntityFromDirectExtensionWithDifferentEntity() {
            var entityClass = EntityViewService.inferEntityClass(DirectOrderView.class);
            assertThat(entityClass).isEqualTo(Order.class);
        }

        interface DirectCustomerView extends EntityView<Customer> {}
        interface DirectOrderView extends EntityView<Order> {}
    }

    @Nested
    class IntermediateInterface {

        @Test
        void infersEntityThroughIntermediateInterface() {
            var entityClass = EntityViewService.inferEntityClass(ConcreteProductView.class);
            assertThat(entityClass).isEqualTo(Product.class);
        }

        @Test
        void infersEntityThroughMultipleLevels() {
            var entityClass = EntityViewService.inferEntityClass(DeepView.class);
            assertThat(entityClass).isEqualTo(DeepEntity.class);
        }

        // Intermediate interface that extends EntityView
        interface BaseProductView extends EntityView<Product> {
            default String getProductName() {
                return getEntity().name;
            }
        }

        // Concrete view extends intermediate
        interface ConcreteProductView extends BaseProductView {}

        // Multiple levels of inheritance
        interface Level1View extends EntityView<DeepEntity> {}
        interface Level2View extends Level1View {}
        interface DeepView extends Level2View {}
    }

    @Nested
    class MultipleInterfaces {

        @Test
        void infersEntityWhenViewExtendsMultipleInterfaces() {
            var entityClass = EntityViewService.inferEntityClass(MultiInterfaceView.class);
            assertThat(entityClass).isEqualTo(Item.class);
        }

        @Test
        void infersEntityWhenEntityViewIsNotFirst() {
            var entityClass = EntityViewService.inferEntityClass(EntityViewNotFirstView.class);
            assertThat(entityClass).isEqualTo(Item.class);
        }

        interface SomeOtherInterface {
            void doSomething();
        }

        interface AnotherInterface {
            void doSomethingElse();
        }

        interface MultiInterfaceView extends EntityView<Item>, SomeOtherInterface {}
        interface EntityViewNotFirstView extends SomeOtherInterface, AnotherInterface, EntityView<Item> {}
    }

    @Nested
    class EdgeCases {

        @Test
        void throwsForRawEntityView() {
            assertThatThrownBy(() -> EntityViewService.inferEntityClass(RawEntityView.class))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Cannot infer entity class");
        }

        @Test
        void throwsForUnrelatedInterface() {
            assertThatThrownBy(() -> EntityViewService.inferEntityClass(UnrelatedInterface.class))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Cannot infer entity class");
        }

        @Test
        void infersEntityWithComplexGenerics() {
            var entityClass = EntityViewService.inferEntityClass(ComplexView.class);
            assertThat(entityClass).isEqualTo(ComplexEntity.class);
        }

        @SuppressWarnings("rawtypes")
        interface RawEntityView extends EntityView {}

        interface UnrelatedInterface {
            void unrelated();
        }

        interface ComplexView extends EntityView<ComplexEntity> {}
    }

    @Nested
    class WithAnnotation {

        @Test
        void annotationEntityTakesPrecedenceWhenSpecified() {
            var annotation = ExplicitEntityView.class.getAnnotation(LlmView.class);
            assertThat(annotation.entity()).isEqualTo(AnnotatedEntity.class);
        }

        @Test
        void annotationDefaultsToVoidWhenNotSpecified() {
            var annotation = InferredEntityView.class.getAnnotation(LlmView.class);
            assertThat(annotation.entity()).isEqualTo(Void.class);
        }

        @Test
        void annotationDescriptionDefaultsToEmpty() {
            var annotation = MinimalView.class.getAnnotation(LlmView.class);
            assertThat(annotation.description()).isEmpty();
        }

        @Test
        void annotationDescriptionCanBeExplicit() {
            var annotation = ExplicitDescriptionView.class.getAnnotation(LlmView.class);
            assertThat(annotation.description()).isEqualTo("Custom description");
        }

        @LlmView(entity = AnnotatedEntity.class, description = "Explicit")
        interface ExplicitEntityView extends EntityView<AnnotatedEntity> {}

        @LlmView(description = "Inferred")
        interface InferredEntityView extends EntityView<InferredEntity> {}

        @LlmView
        interface MinimalView extends EntityView<MinimalEntity> {}

        @LlmView(description = "Custom description")
        interface ExplicitDescriptionView extends EntityView<DescribedEntity> {}
    }

    // Test entities
    static class Customer { String name; }
    static class Order { String orderId; }
    static class MinimalEntity { String id; }
    static class DescribedEntity { String id; }
    static class Product { String name; }
    static class DeepEntity { String value; }
    static class Item { String itemId; }
    static class ComplexEntity { String data; }
    static class AnnotatedEntity { String id; }
    static class InferredEntity { String id; }
}
