package com.embabel.springdata;

import com.embabel.agent.api.annotation.LlmTool;
import com.embabel.agent.api.tool.MatryoshkaTool;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EntityNavigationServiceTest {

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private Repositories repositories;

    @Mock
    private CrudRepository<Object, Object> mockRepository;

    @Mock
    private EntityInformation<Object, Object> entityInfo;

    private EntityNavigationService service;

    @BeforeEach
    void setUp() {
        service = new EntityNavigationService(transactionTemplate, repositories);
        // Default: no repository for any class (override in specific tests)
        when(repositories.getRepositoryFor(any())).thenReturn(Optional.empty());
    }

    @Test
    void createDirectTools_returnsToolsForLlmToolMethods() {
        var entity = new EntityWithTools(1L);

        when(repositories.getEntityInformationFor(EntityWithTools.class)).thenReturn(entityInfo);
        when(entityInfo.getId(entity)).thenReturn(1L);

        var tools = service.createDirectTools(entity);

        assertThat(tools).hasSize(2);
        assertThat(tools).extracting(t -> t.getDefinition().getName())
                .containsExactlyInAnyOrder("doSomething", "doSomethingElse");
    }

    @Test
    void createDirectTools_returnsEmptyListForEntityWithoutTools() {
        var entity = new EntityWithoutTools(1L);

        var tools = service.createDirectTools(entity);

        assertThat(tools).isEmpty();
    }

    @Test
    void hasLlmToolMethods_returnsTrueForEntityWithTools() {
        assertThat(service.hasLlmToolMethods(EntityWithTools.class)).isTrue();
    }

    @Test
    void hasLlmToolMethods_returnsFalseForEntityWithoutTools() {
        assertThat(service.hasLlmToolMethods(EntityWithoutTools.class)).isFalse();
    }

    @Test
    void createRelationMatryoshkaTools_createsToolForRelationWithLlmToolMethods() {
        var relatedEntity = new EntityWithTools(2L);
        var parentEntity = new ParentEntityWithToolRelation(1L, relatedEntity);

        when(repositories.getRepositoryFor(EntityWithTools.class)).thenReturn(Optional.of(mockRepository));
        when(repositories.getEntityInformationFor(EntityWithTools.class)).thenReturn(entityInfo);
        when(entityInfo.getId(relatedEntity)).thenReturn(2L);

        var tools = service.createRelationMatryoshkaTools(parentEntity);

        assertThat(tools).hasSize(1);
        assertThat(tools.get(0)).isInstanceOf(MatryoshkaTool.class);
        assertThat(tools.get(0).getDefinition().getName()).isEqualTo("relatedEntity_tools");
    }

    @Test
    void createRelationMatryoshkaTools_skipsRelationWithoutLlmToolMethods() {
        var relatedEntity = new EntityWithoutTools(2L);
        var parentEntity = new ParentEntityWithNoToolRelation(1L, relatedEntity);

        when(repositories.getRepositoryFor(EntityWithoutTools.class)).thenReturn(Optional.of(mockRepository));

        var tools = service.createRelationMatryoshkaTools(parentEntity);

        assertThat(tools).isEmpty();
    }

    @Test
    void createRelationMatryoshkaTools_skipsNullRelation() {
        var parentEntity = new ParentEntityWithToolRelation(1L, null);

        when(repositories.getRepositoryFor(EntityWithTools.class)).thenReturn(Optional.of(mockRepository));

        var tools = service.createRelationMatryoshkaTools(parentEntity);

        assertThat(tools).isEmpty();
    }

    @Test
    void createRelationMatryoshkaTools_skipsNonEntityFields() {
        var parentEntity = new EntityWithNonEntityRelation(1L, "not an entity");

        var tools = service.createRelationMatryoshkaTools(parentEntity);

        assertThat(tools).isEmpty();
    }

    @Test
    void makeNavigableTools_combinesDirectToolsAndMatryoshka() {
        var relatedEntity = new EntityWithTools(2L);
        var parentEntity = new ParentWithToolsAndRelation(1L, relatedEntity);

        when(repositories.getEntityInformationFor(ParentWithToolsAndRelation.class)).thenReturn(entityInfo);
        when(entityInfo.getId(parentEntity)).thenReturn(1L);
        when(repositories.getRepositoryFor(EntityWithTools.class)).thenReturn(Optional.of(mockRepository));
        when(repositories.getEntityInformationFor(EntityWithTools.class)).thenReturn(entityInfo);
        when(entityInfo.getId(relatedEntity)).thenReturn(2L);

        var tools = service.makeNavigable(parentEntity);

        // Should have 1 direct tool + 1 MatryoshkaTool for the relation
        assertThat(tools).hasSize(2);

        var directTools = tools.stream()
                .filter(t -> !(t instanceof MatryoshkaTool))
                .toList();
        var matryoshkaTools = tools.stream()
                .filter(t -> t instanceof MatryoshkaTool)
                .toList();

        assertThat(directTools).hasSize(1);
        assertThat(directTools.get(0).getDefinition().getName()).isEqualTo("parentAction");

        assertThat(matryoshkaTools).hasSize(1);
        assertThat(matryoshkaTools.get(0).getDefinition().getName()).isEqualTo("relatedEntity_tools");
    }

    // Test entities

    static class EntityWithTools {
        @Id
        private Long id;

        EntityWithTools(Long id) {
            this.id = id;
        }

        @LlmTool(description = "Does something")
        public String doSomething() {
            return "done";
        }

        @LlmTool(description = "Does something else")
        public String doSomethingElse() {
            return "done else";
        }
    }

    static class EntityWithoutTools {
        @Id
        private Long id;

        EntityWithoutTools(Long id) {
            this.id = id;
        }

        public String regularMethod() {
            return "not a tool";
        }
    }

    static class ParentEntityWithToolRelation {
        @Id
        private Long id;

        @ManyToOne
        private EntityWithTools relatedEntity;

        ParentEntityWithToolRelation(Long id, EntityWithTools relatedEntity) {
            this.id = id;
            this.relatedEntity = relatedEntity;
        }
    }

    static class ParentEntityWithNoToolRelation {
        @Id
        private Long id;

        @ManyToOne
        private EntityWithoutTools relatedEntity;

        ParentEntityWithNoToolRelation(Long id, EntityWithoutTools relatedEntity) {
            this.id = id;
            this.relatedEntity = relatedEntity;
        }
    }

    static class EntityWithNonEntityRelation {
        @Id
        private Long id;

        private String notAnEntity;

        EntityWithNonEntityRelation(Long id, String notAnEntity) {
            this.id = id;
            this.notAnEntity = notAnEntity;
        }
    }

    static class ParentWithToolsAndRelation {
        @Id
        private Long id;

        @ManyToOne
        private EntityWithTools relatedEntity;

        ParentWithToolsAndRelation(Long id, EntityWithTools relatedEntity) {
            this.id = id;
            this.relatedEntity = relatedEntity;
        }

        @LlmTool(description = "Parent action")
        public String parentAction() {
            return "parent did something";
        }
    }
}
