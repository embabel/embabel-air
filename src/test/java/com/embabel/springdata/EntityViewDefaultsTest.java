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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.repository.core.EntityInformation;
import org.springframework.data.repository.support.Repositories;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EntityViewDefaultsTest {

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private Repositories repositories;

    @Mock
    private EntityInformation<Object, Object> entityInfo;

    private EntityViewService service;

    @BeforeEach
    void setUp() {
        service = new EntityViewService(transactionTemplate, repositories, null);
    }

    @Nested
    class DefaultSummary {

        @Test
        void summary_includesEntityTypeAndId() {
            service.register(SimpleEntity.class, SimpleEntityView.class, "Simple entity");
            when(repositories.getEntityInformationFor(SimpleEntity.class)).thenReturn(entityInfo);

            var entity = new SimpleEntity(42L, "Test Name");
            when(entityInfo.getId(entity)).thenReturn(42L);

            var view = service.viewOf(entity);

            assertThat(view.summary()).isEqualTo("SimpleEntity (id=42)");
        }

        @Test
        void summary_handlesStringId() {
            service.register(StringIdEntity.class, StringIdEntityView.class, "String ID entity");
            when(repositories.getEntityInformationFor(StringIdEntity.class)).thenReturn(entityInfo);

            var entity = new StringIdEntity("abc-123", "Test");
            when(entityInfo.getId(entity)).thenReturn("abc-123");

            var view = service.viewOf(entity);

            assertThat(view.summary()).isEqualTo("StringIdEntity (id=abc-123)");
        }

        @Test
        void summary_handlesUuidId() {
            service.register(UuidIdEntity.class, UuidIdEntityView.class, "UUID entity");
            when(repositories.getEntityInformationFor(UuidIdEntity.class)).thenReturn(entityInfo);

            var uuid = UUID.randomUUID();
            var entity = new UuidIdEntity(uuid, "Test");
            when(entityInfo.getId(entity)).thenReturn(uuid);

            var view = service.viewOf(entity);

            assertThat(view.summary()).contains("UuidIdEntity (id=" + uuid + ")");
        }
    }

    @Nested
    class DefaultFullText {

        @Test
        void fullText_includesSimpleProperties() {
            service.register(SimpleEntity.class, SimpleEntityView.class, "Simple entity");
            when(repositories.getEntityInformationFor(SimpleEntity.class)).thenReturn(entityInfo);

            var entity = new SimpleEntity(1L, "John Doe");
            when(entityInfo.getId(entity)).thenReturn(1L);

            var view = service.viewOf(entity);
            var fullText = view.fullText();

            assertThat(fullText).contains("SimpleEntity");
            assertThat(fullText).contains("id: 1");
            assertThat(fullText).contains("name: John Doe");
        }

        @Test
        void fullText_includesAllSimpleTypes() {
            service.register(AllTypesEntity.class, AllTypesEntityView.class, "All types");
            when(repositories.getEntityInformationFor(AllTypesEntity.class)).thenReturn(entityInfo);

            var entity = new AllTypesEntity();
            entity.id = 1L;
            entity.stringVal = "hello";
            entity.intVal = 42;
            entity.doubleVal = 3.14;
            entity.boolVal = true;
            entity.dateVal = LocalDate.of(2026, 1, 17);
            entity.enumVal = Status.ACTIVE;
            entity.bigDecimalVal = new BigDecimal("99.99");
            when(entityInfo.getId(entity)).thenReturn(1L);

            var view = service.viewOf(entity);
            var fullText = view.fullText();

            assertThat(fullText).contains("stringVal: hello");
            assertThat(fullText).contains("intVal: 42");
            assertThat(fullText).contains("doubleVal: 3.14");
            assertThat(fullText).contains("boolVal: true");
            assertThat(fullText).contains("dateVal: 2026-01-17");
            assertThat(fullText).contains("enumVal: ACTIVE");
            assertThat(fullText).contains("bigDecimalVal: 99.99");
        }

        @Test
        void fullText_showsCollectionSizes() {
            service.register(EntityWithCollections.class, EntityWithCollectionsView.class, "With collections");
            when(repositories.getEntityInformationFor(EntityWithCollections.class)).thenReturn(entityInfo);

            var entity = new EntityWithCollections(1L);
            entity.items.add("one");
            entity.items.add("two");
            entity.items.add("three");
            when(entityInfo.getId(entity)).thenReturn(1L);

            var view = service.viewOf(entity);
            var fullText = view.fullText();

            assertThat(fullText).contains("items: 3 items");
        }

        @Test
        void fullText_handlesEmptyCollections() {
            service.register(EntityWithCollections.class, EntityWithCollectionsView.class, "With collections");
            when(repositories.getEntityInformationFor(EntityWithCollections.class)).thenReturn(entityInfo);

            var entity = new EntityWithCollections(1L);
            when(entityInfo.getId(entity)).thenReturn(1L);

            var view = service.viewOf(entity);
            var fullText = view.fullText();

            assertThat(fullText).contains("items: 0 items");
        }

        @Test
        void fullText_excludesComplexObjects() {
            service.register(EntityWithRelation.class, EntityWithRelationView.class, "With relation");
            when(repositories.getEntityInformationFor(EntityWithRelation.class)).thenReturn(entityInfo);

            var related = new SimpleEntity(2L, "Related");
            var entity = new EntityWithRelation(1L, "Parent", related);
            when(entityInfo.getId(entity)).thenReturn(1L);

            var view = service.viewOf(entity);
            var fullText = view.fullText();

            assertThat(fullText).contains("name: Parent");
            // Should NOT include the related entity as a property
            assertThat(fullText).doesNotContain("related:");
            assertThat(fullText).doesNotContain("Related");
        }

        @Test
        void fullText_handlesNullValues() {
            service.register(SimpleEntity.class, SimpleEntityView.class, "Simple entity");
            when(repositories.getEntityInformationFor(SimpleEntity.class)).thenReturn(entityInfo);

            var entity = new SimpleEntity(1L, null);
            when(entityInfo.getId(entity)).thenReturn(1L);

            var view = service.viewOf(entity);
            var fullText = view.fullText();

            assertThat(fullText).contains("name: null");
        }
    }

    @Nested
    class CustomOverrides {

        @Test
        void customSummary_overridesDefault() {
            service.register(CustomViewEntity.class, CustomEntityView.class, "Custom view");
            when(repositories.getEntityInformationFor(CustomViewEntity.class)).thenReturn(entityInfo);

            var entity = new CustomViewEntity(1L, "Test");
            when(entityInfo.getId(entity)).thenReturn(1L);

            var view = service.viewOf(entity);

            assertThat(view.summary()).isEqualTo("Custom: Test");
        }

        @Test
        void customFullText_overridesDefault() {
            service.register(CustomViewEntity.class, CustomEntityView.class, "Custom view");
            when(repositories.getEntityInformationFor(CustomViewEntity.class)).thenReturn(entityInfo);

            var entity = new CustomViewEntity(1L, "Test");
            when(entityInfo.getId(entity)).thenReturn(1L);

            var view = service.viewOf(entity);

            assertThat(view.fullText()).isEqualTo("Full: Test (id=1)");
        }
    }

    // Test entities

    static class SimpleEntity {
        @Id
        private Long id;
        private String name;

        SimpleEntity(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        public Long getId() { return id; }
        public String getName() { return name; }
    }

    interface SimpleEntityView extends EntityView<SimpleEntity> {
        // Uses default summary/fullText
    }

    static class StringIdEntity {
        @Id
        private String id;
        private String name;

        StringIdEntity(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String getId() { return id; }
        public String getName() { return name; }
    }

    interface StringIdEntityView extends EntityView<StringIdEntity> {}

    static class UuidIdEntity {
        @Id
        private UUID id;
        private String name;

        UuidIdEntity(UUID id, String name) {
            this.id = id;
            this.name = name;
        }

        public UUID getId() { return id; }
        public String getName() { return name; }
    }

    interface UuidIdEntityView extends EntityView<UuidIdEntity> {}

    enum Status { ACTIVE, INACTIVE }

    static class AllTypesEntity {
        @Id Long id;
        String stringVal;
        int intVal;
        double doubleVal;
        boolean boolVal;
        LocalDate dateVal;
        Status enumVal;
        BigDecimal bigDecimalVal;

        public Long getId() { return id; }
        public String getStringVal() { return stringVal; }
        public int getIntVal() { return intVal; }
        public double getDoubleVal() { return doubleVal; }
        public boolean isBoolVal() { return boolVal; }
        public LocalDate getDateVal() { return dateVal; }
        public Status getEnumVal() { return enumVal; }
        public BigDecimal getBigDecimalVal() { return bigDecimalVal; }
    }

    interface AllTypesEntityView extends EntityView<AllTypesEntity> {}

    static class EntityWithCollections {
        @Id
        private Long id;
        private List<String> items = new ArrayList<>();

        EntityWithCollections(Long id) {
            this.id = id;
        }

        public Long getId() { return id; }
        public List<String> getItems() { return items; }
    }

    interface EntityWithCollectionsView extends EntityView<EntityWithCollections> {}

    static class EntityWithRelation {
        @Id
        private Long id;
        private String name;
        private SimpleEntity related;

        EntityWithRelation(Long id, String name, SimpleEntity related) {
            this.id = id;
            this.name = name;
            this.related = related;
        }

        public Long getId() { return id; }
        public String getName() { return name; }
        public SimpleEntity getRelated() { return related; }
    }

    interface EntityWithRelationView extends EntityView<EntityWithRelation> {}

    static class CustomViewEntity {
        @Id
        private Long id;
        private String name;

        CustomViewEntity(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        public Long getId() { return id; }
        public String getName() { return name; }
    }

    interface CustomEntityView extends EntityView<CustomViewEntity> {
        @Override
        default String summary() {
            return "Custom: " + getEntity().getName();
        }

        @Override
        default String fullText() {
            return "Full: " + getEntity().getName() + " (id=" + getEntity().getId() + ")";
        }
    }
}
