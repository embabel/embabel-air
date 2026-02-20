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
import com.embabel.agent.api.reference.LlmReference;
import com.embabel.agent.api.tool.MatryoshkaTool;
import com.embabel.agent.api.tool.Tool;
import jakarta.persistence.Id;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.core.EntityInformation;
import org.springframework.data.repository.support.Repositories;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EntityViewServiceTest {

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private Repositories repositories;

    @Mock
    private CrudRepository<Object, Object> mockRepository;

    @Mock
    private EntityInformation<Object, Object> entityInfo;

    private EntityViewService service;

    @BeforeEach
    void setUp() {
        service = new EntityViewService(transactionTemplate, repositories, null);
        when(repositories.getRepositoryFor(any())).thenReturn(Optional.empty());
    }

    @Nested
    class ViewRegistration {

        @Test
        void register_addsViewToRegistry() {
            service.register(TestEntity.class, TestEntityView.class, "Test entity");

            assertThat(service.hasViewFor(TestEntity.class)).isTrue();
        }

        @Test
        void hasViewFor_returnsFalseForUnregisteredEntity() {
            assertThat(service.hasViewFor(UnregisteredEntity.class)).isFalse();
        }
    }

    @Nested
    class ViewCreation {

        @BeforeEach
        void registerView() {
            service.register(TestEntity.class, TestEntityView.class, "Test entity");
        }

        @Test
        void viewOf_createsViewForRegisteredEntity() {
            var entity = new TestEntity(1L, "Test");

            var view = service.viewOf(entity);

            assertThat(view).isNotNull();
            assertThat(view.getEntity()).isSameAs(entity);
        }

        @Test
        void viewOf_throwsForUnregisteredEntity() {
            var entity = new UnregisteredEntity(1L);

            assertThatThrownBy(() -> service.viewOf(entity))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No EntityView registered for");
        }

        @Test
        void viewOf_proxySummaryDelegatesToDefaultMethod() {
            var entity = new TestEntity(1L, "Hello");

            var view = service.viewOf(entity);

            assertThat(view.summary()).isEqualTo("TestEntity: Hello");
        }

        @Test
        void viewOf_proxyFullTextDelegatesToDefaultMethod() {
            var entity = new TestEntity(1L, "Hello");

            var view = service.viewOf(entity);

            assertThat(view.fullText()).isEqualTo("TestEntity: Hello\nID: 1");
        }
    }

    @Nested
    class ToolCreation {

        @BeforeEach
        void registerView() {
            service.register(TestEntity.class, TestEntityView.class, "Test entity");
            when(repositories.getEntityInformationFor(TestEntity.class)).thenReturn(entityInfo);
        }

        @Test
        void createTools_returnsToolsForLlmToolMethods() {
            var entity = new TestEntity(1L, "Test");
            when(entityInfo.getId(entity)).thenReturn(1L);

            var view = service.viewOf(entity);
            var tools = service.createTools(view);

            assertThat(tools).hasSize(2);
            assertThat(tools).extracting(t -> t.getDefinition().getName())
                    .containsExactlyInAnyOrder("greet", "getCreatedDate");
        }

        @Test
        void createTools_toolDefinitionIncludesDescription() {
            var entity = new TestEntity(1L, "Test");
            when(entityInfo.getId(entity)).thenReturn(1L);

            var view = service.viewOf(entity);
            var tools = service.createTools(view);

            var greetTool = tools.stream()
                    .filter(t -> t.getDefinition().getName().equals("greet"))
                    .findFirst()
                    .orElseThrow();

            assertThat(greetTool.getDefinition().getDescription()).isEqualTo("Greet the entity");
        }

        @Test
        void createTools_returnsEmptyListForViewWithoutTools() {
            service.register(EntityWithoutTools.class, EntityWithoutToolsView.class, "No tools");
            when(repositories.getEntityInformationFor(EntityWithoutTools.class)).thenReturn(entityInfo);

            var entity = new EntityWithoutTools(1L);
            when(entityInfo.getId(entity)).thenReturn(1L);

            var view = service.viewOf(entity);
            var tools = service.createTools(view);

            assertThat(tools).isEmpty();
        }

        @Test
        void createTools_toolParametersAreExtracted() {
            var entity = new TestEntity(1L, "Test");
            when(entityInfo.getId(entity)).thenReturn(1L);

            var view = service.viewOf(entity);
            var tools = service.createTools(view);

            var greetTool = tools.stream()
                    .filter(t -> t.getDefinition().getName().equals("greet"))
                    .findFirst()
                    .orElseThrow();

            var params = greetTool.getDefinition().getInputSchema().getParameters();
            assertThat(params).hasSize(1);
            assertThat(params.get(0).getName()).isEqualTo("greeting");
            assertThat(params.get(0).getType()).isEqualTo(Tool.ParameterType.STRING);
        }
    }

    @Nested
    class FinderTool {

        @BeforeEach
        void registerView() {
            service.register(TestEntity.class, TestEntityView.class, "Test entity");
            when(repositories.getRepositoryFor(TestEntity.class)).thenReturn(Optional.of(mockRepository));
            when(repositories.getEntityInformationFor(TestEntity.class)).thenReturn(entityInfo);
            when(entityInfo.getIdType()).thenReturn((Class) Long.class);
        }

        @Test
        void finderFor_createsMatryoshkaTool() {
            var finder = service.finderFor(TestEntity.class);

            assertThat(finder).isInstanceOf(MatryoshkaTool.class);
            assertThat(finder.getDefinition().getName()).isEqualTo("find_testentity");
        }

        @Test
        void finderFor_toolDescriptionIncludesEntityDescription() {
            var finder = service.finderFor(TestEntity.class);

            assertThat(finder.getDefinition().getDescription())
                    .contains("Test entity")
                    .contains("Pass the ID");
        }

        @Test
        void finderFor_throwsForUnregisteredEntityWithoutTools() {
            assertThatThrownBy(() -> service.finderFor(UnregisteredEntity.class))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No repository found for");
        }

        @Test
        void finderFor_throwsWhenNoRepositoryExists() {
            service.register(EntityWithoutRepo.class, EntityWithoutRepoView.class, "No repo");

            assertThatThrownBy(() -> service.finderFor(EntityWithoutRepo.class))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No repository found for");
        }

        @Test
        void finderFor_usesIntegerTypeForNumericIds() {
            when(entityInfo.getIdType()).thenReturn((Class) Long.class);

            var finder = service.finderFor(TestEntity.class);
            var params = finder.getDefinition().getInputSchema().getParameters();

            assertThat(params).hasSize(1);
            assertThat(params.get(0).getName()).isEqualTo("id");
            assertThat(params.get(0).getType()).isEqualTo(Tool.ParameterType.INTEGER);
        }

        @Test
        void finderFor_usesStringTypeForStringIds() {
            service.register(StringIdEntity.class, StringIdEntityView.class, "String ID entity");
            when(repositories.getRepositoryFor(StringIdEntity.class)).thenReturn(Optional.of(mockRepository));
            when(repositories.getEntityInformationFor(StringIdEntity.class)).thenReturn(entityInfo);
            when(entityInfo.getIdType()).thenReturn((Class) String.class);

            var finder = service.finderFor(StringIdEntity.class);
            var params = finder.getDefinition().getInputSchema().getParameters();

            assertThat(params.get(0).getType()).isEqualTo(Tool.ParameterType.STRING);
            assertThat(params.get(0).getDescription()).contains("UUID");
        }
    }

    @Nested
    class LlmReferenceIntegration {

        @BeforeEach
        void registerView() {
            service.register(TestEntity.class, TestEntityView.class, "Test entity");
            when(repositories.getEntityInformationFor(TestEntity.class)).thenReturn(entityInfo);
            when(repositories.getRepositoryFor(TestEntity.class)).thenReturn(Optional.of(mockRepository));
            // Make transactionTemplate execute the callback directly
            when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                var callback = invocation.getArgument(0, org.springframework.transaction.support.TransactionCallback.class);
                return callback.doInTransaction(null);
            });
        }

        @Test
        void entityView_isInstanceOfLlmReference() {
            var entity = new TestEntity(1L, "Test");
            var view = service.viewOf(entity);

            assertThat(view).isInstanceOf(LlmReference.class);
        }

        @Test
        void getName_returnsEntityClassNameLowercase() {
            var entity = new TestEntity(1L, "Test");
            var view = service.viewOf(entity);

            assertThat(view.getName()).isEqualTo("testentity");
        }

        @Test
        void getDescription_returnsRegisteredDescription() {
            var entity = new TestEntity(1L, "Test");
            var view = service.viewOf(entity);

            assertThat(view.getDescription()).isEqualTo("Test entity");
        }

        @Test
        void notes_reloadsEntityInTransaction() {
            var entity = new TestEntity(1L, "Hello");
            when(entityInfo.getId(entity)).thenReturn(1L);
            when(mockRepository.findById(1L)).thenReturn(Optional.of(entity));
            var view = service.viewOf(entity);

            assertThat(view.notes()).isEqualTo("TestEntity: Hello\nID: 1");
        }

        @Test
        void tools_returnsToolsFromLlmToolMethods() {
            var entity = new TestEntity(1L, "Test");
            when(entityInfo.getId(entity)).thenReturn(1L);
            var view = service.viewOf(entity);

            assertThat(view.tools()).hasSize(2);
            assertThat(view.tools()).extracting(t -> t.getDefinition().getName())
                    .containsExactlyInAnyOrder("greet", "getCreatedDate");
        }

        @Test
        void contribution_includesNameDescriptionAndNotes() {
            var entity = new TestEntity(1L, "Hello");
            when(entityInfo.getId(entity)).thenReturn(1L);
            when(mockRepository.findById(1L)).thenReturn(Optional.of(entity));
            var view = service.viewOf(entity);

            var contribution = view.contribution();

            assertThat(contribution).contains("Reference: testentity");
            assertThat(contribution).contains("Description: Test entity");
            assertThat(contribution).contains("Notes: TestEntity: Hello");
        }
    }

    @Nested
    class EntityToolCreation {

        @BeforeEach
        void setUp() {
            when(repositories.getEntityInformationFor(EntityWithLlmTools.class)).thenReturn(entityInfo);
        }

        @Test
        void createEntityTools_returnsToolsFromEntityLlmToolMethods() {
            var entity = new EntityWithLlmTools(1L, "Test");
            when(entityInfo.getId(entity)).thenReturn(1L);

            var tools = service.createEntityTools(entity);

            assertThat(tools).hasSize(1);
            assertThat(tools.get(0).getDefinition().getName()).isEqualTo("doSomething");
        }

        @Test
        void createEntityTools_includesDescription() {
            var entity = new EntityWithLlmTools(1L, "Test");
            when(entityInfo.getId(entity)).thenReturn(1L);

            var tools = service.createEntityTools(entity);

            assertThat(tools.get(0).getDefinition().getDescription()).isEqualTo("Do something with this entity");
        }

        @Test
        void createEntityTools_returnsEmptyForEntityWithoutTools() {
            when(repositories.getEntityInformationFor(UnregisteredEntity.class)).thenReturn(entityInfo);
            var entity = new UnregisteredEntity(1L);
            when(entityInfo.getId(entity)).thenReturn(1L);

            var tools = service.createEntityTools(entity);

            assertThat(tools).isEmpty();
        }
    }

    @Nested
    class HasEntityToolsTest {

        @Test
        void hasEntityTools_returnsTrueForEntityWithLlmToolMethods() {
            assertThat(service.hasEntityTools(EntityWithLlmTools.class)).isTrue();
        }

        @Test
        void hasEntityTools_returnsFalseForEntityWithoutLlmToolMethods() {
            assertThat(service.hasEntityTools(UnregisteredEntity.class)).isFalse();
        }
    }

    @Nested
    class FinderWithoutView {

        @BeforeEach
        void setUp() {
            when(repositories.getRepositoryFor(EntityWithLlmTools.class)).thenReturn(Optional.of(mockRepository));
            when(repositories.getEntityInformationFor(EntityWithLlmTools.class)).thenReturn(entityInfo);
            when(entityInfo.getIdType()).thenReturn((Class) Long.class);
        }

        @Test
        void finderFor_fallsBackToEntityToolsWhenNoView() {
            var finder = service.finderFor(EntityWithLlmTools.class);

            assertThat(finder).isInstanceOf(MatryoshkaTool.class);
            assertThat(finder.getDefinition().getName()).isEqualTo("find_entitywithllmtools");
        }

        @Test
        void finderFor_throwsWhenNoViewAndNoEntityTools() {
            when(repositories.getRepositoryFor(UnregisteredEntity.class)).thenReturn(Optional.of(mockRepository));

            assertThatThrownBy(() -> service.finderFor(UnregisteredEntity.class))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No EntityView registered and no @LlmTool methods");
        }
    }

    @Nested
    class RepositoryTools {

        @BeforeEach
        void setUp() {
            when(repositories.getRepositoryFor(EntityWithLlmTools.class)).thenReturn(Optional.of(mockRepository));
            when(repositories.getEntityInformationFor(EntityWithLlmTools.class)).thenReturn(entityInfo);
        }

        @Test
        void repositoryToolsFor_discoversCustomFinderMethods() {
            var tools = service.repositoryToolsFor(TestEntityRepository.class);

            assertThat(tools).hasSize(2);
        }

        @Test
        void repositoryToolsFor_toolNameDerivedFromMethodName() {
            var tools = service.repositoryToolsFor(TestEntityRepository.class);

            var toolNames = tools.stream().map(t -> t.getDefinition().getName()).toList();
            assertThat(toolNames).contains("find_entitywithllmtools_by_name");
        }

        @Test
        void repositoryToolsFor_toolHasCorrectParameters() {
            var tools = service.repositoryToolsFor(TestEntityRepository.class);

            var singleFinder = tools.stream()
                    .filter(t -> t.getDefinition().getName().equals("find_entitywithllmtools_by_name"))
                    .findFirst().orElseThrow();
            var params = singleFinder.getDefinition().getInputSchema().getParameters();
            assertThat(params).hasSize(1);
            assertThat(params.get(0).getType()).isEqualTo(Tool.ParameterType.STRING);
        }

        @Test
        void repositoryToolsFor_listReturningFinderHasPluralDescription() {
            var tools = service.repositoryToolsFor(TestEntityRepository.class);

            var listFinder = tools.stream()
                    .filter(t -> t.getDefinition().getName().contains("containing"))
                    .findFirst().orElseThrow();
            assertThat(listFinder.getDefinition().getDescription())
                    .startsWith("Find EntityWithLlmTools records");
        }
    }

    @Nested
    class EntityReference {

        @BeforeEach
        void setUp() {
            when(repositories.getEntityInformationFor(EntityWithLlmTools.class)).thenReturn(entityInfo);
            when(repositories.getRepositoryFor(EntityWithLlmTools.class)).thenReturn(Optional.of(mockRepository));
            when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                var callback = invocation.getArgument(0, org.springframework.transaction.support.TransactionCallback.class);
                return callback.doInTransaction(null);
            });
        }

        @Test
        void entityReferenceFor_createsLlmReference() {
            var entity = new EntityWithLlmTools(1L, "Test");
            when(entityInfo.getId(entity)).thenReturn(1L);
            when(mockRepository.findById(1L)).thenReturn(Optional.of(entity));

            var ref = service.entityReferenceFor(entity);

            assertThat(ref).isInstanceOf(LlmReference.class);
        }

        @Test
        void entityReferenceFor_hasCorrectName() {
            var entity = new EntityWithLlmTools(1L, "Test");
            when(entityInfo.getId(entity)).thenReturn(1L);
            when(mockRepository.findById(1L)).thenReturn(Optional.of(entity));

            var ref = service.entityReferenceFor(entity);

            assertThat(ref.getName()).isEqualTo("entitywithllmtools");
        }

        @Test
        void entityReferenceFor_hasToolsFromEntity() {
            var entity = new EntityWithLlmTools(1L, "Test");
            when(entityInfo.getId(entity)).thenReturn(1L);
            when(mockRepository.findById(1L)).thenReturn(Optional.of(entity));

            var ref = service.entityReferenceFor(entity);

            assertThat(ref.tools()).hasSize(1);
            assertThat(ref.tools().get(0).getDefinition().getName()).isEqualTo("doSomething");
        }

        @Test
        void entityReferenceFor_hasNotesFromDefaultStrategy() {
            var entity = new EntityWithLlmTools(1L, "Test");
            when(entityInfo.getId(entity)).thenReturn(1L);
            when(mockRepository.findById(1L)).thenReturn(Optional.of(entity));

            var ref = service.entityReferenceFor(entity);

            assertThat(ref.notes()).contains("EntityWithLlmTools");
        }
    }

    // Test entities and views

    static class EntityWithLlmTools {
        @Id
        private Long id;
        private String name;

        EntityWithLlmTools(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        public Long getId() { return id; }
        public String getName() { return name; }

        @LlmTool(description = "Do something with this entity")
        public String doSomething() {
            return "Done: " + name;
        }
    }

    interface TestEntityRepository extends CrudRepository<EntityWithLlmTools, Long> {
        EntityWithLlmTools findByName(String name);
        List<EntityWithLlmTools> findByNameContaining(String name);
    }

    static class TestEntity {
        @Id
        private Long id;
        private String name;

        TestEntity(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        public Long getId() { return id; }
        public String getName() { return name; }
    }

    interface TestEntityView extends EntityView<TestEntity> {
        @Override
        default String summary() {
            return "TestEntity: " + getEntity().getName();
        }

        @Override
        default String fullText() {
            return summary() + "\nID: " + getEntity().getId();
        }

        @LlmTool(description = "Greet the entity")
        default String greet(
                @LlmTool.Param(description = "The greeting to use") String greeting
        ) {
            return greeting + ", " + getEntity().getName();
        }

        @LlmTool(description = "Get creation date")
        default LocalDate getCreatedDate() {
            return LocalDate.now();
        }
    }

    static class EntityWithoutTools {
        @Id
        private Long id;

        EntityWithoutTools(Long id) {
            this.id = id;
        }
    }

    interface EntityWithoutToolsView extends EntityView<EntityWithoutTools> {
        @Override
        default String summary() {
            return "No tools";
        }

        @Override
        default String fullText() {
            return summary();
        }
    }

    static class UnregisteredEntity {
        @Id
        private Long id;

        UnregisteredEntity(Long id) {
            this.id = id;
        }
    }

    static class EntityWithoutRepo {
        @Id
        private Long id;

        EntityWithoutRepo(Long id) {
            this.id = id;
        }
    }

    interface EntityWithoutRepoView extends EntityView<EntityWithoutRepo> {
        @Override
        default String summary() { return "No repo"; }

        @Override
        default String fullText() { return summary(); }
    }

    static class StringIdEntity {
        @Id
        private String id;

        StringIdEntity(String id) {
            this.id = id;
        }
    }

    interface StringIdEntityView extends EntityView<StringIdEntity> {
        @Override
        default String summary() { return "String ID: " + getEntity().id; }

        @Override
        default String fullText() { return summary(); }
    }
}
