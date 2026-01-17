package com.embabel.air;

import com.embabel.agent.rag.model.Chunk;
import com.embabel.agent.rag.pgvector.PgVectorStore;
import com.embabel.common.core.types.TextSimilaritySearchRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for PgVectorStore using Testcontainers.
 * Tests actual search functionality against the rebooking policy document
 * that is loaded on startup by DocumentLoader.
 */
@SpringBootTest
@Testcontainers
class PgVectorStoreIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @Autowired
    private PgVectorStore store;

    @Test
    void documentIsLoaded() {
        var info = store.info();

        assertThat(info.getChunkCount()).isGreaterThan(0);
        assertThat(info.getDocumentCount()).isGreaterThan(0);
    }

    @Test
    void textSearch_findsRebookingPolicy() {
        var request = TextSimilaritySearchRequest.create("rebooking fee", 0.0, 5);

        var results = store.textSearch(request, Chunk.class);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).getMatch().getText()).containsIgnoringCase("rebooking");
    }

    @Test
    void textSearch_findsLoyaltyProgram() {
        var request = TextSimilaritySearchRequest.create("loyalty tier", 0.0, 5);

        var results = store.textSearch(request, Chunk.class);

        assertThat(results).isNotEmpty();
    }

    @Test
    void vectorSearch_findsSemanticallyRelatedContent() {
        var request = TextSimilaritySearchRequest.create("how do I change my flight", 0.0, 5);

        var results = store.vectorSearch(request, Chunk.class);

        assertThat(results).isNotEmpty();
        // Vector search should find content about modifications/changes
        var topResultText = results.get(0).getMatch().getText().toLowerCase();
        assertThat(topResultText).containsAnyOf("change", "modification", "rebooking");
    }

    @Test
    void vectorSearch_returnsScoresBetweenZeroAndOne() {
        var request = TextSimilaritySearchRequest.create("cancellation policy", 0.0, 3);

        var results = store.vectorSearch(request, Chunk.class);

        assertThat(results).isNotEmpty();
        for (var result : results) {
            assertThat(result.getScore()).isBetween(0.0, 1.0);
        }
    }

    @Test
    void hybridSearch_combinesTextAndVectorResults() {
        var request = TextSimilaritySearchRequest.create("refund eligibility", 0.0, 5);

        var results = store.hybridSearch(request, Chunk.class);

        assertThat(results).isNotEmpty();
    }
}
