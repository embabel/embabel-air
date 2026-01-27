package com.embabel.air.ai.rag;

import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.LlmReference;
import com.embabel.agent.rag.ingestion.transform.AddTitlesChunkTransformer;
import com.embabel.agent.rag.pgvector.PgVectorStore;
import com.embabel.agent.rag.pgvector.PgVectorStoreBuilder;
import com.embabel.agent.rag.service.SearchOperations;
import com.embabel.agent.rag.tools.ToolishRag;
import com.embabel.air.ai.AirProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
@EnableConfigurationProperties(AirProperties.class)
public class RagConfiguration {

    private final Logger logger = LoggerFactory.getLogger(RagConfiguration.class);

    @Bean
    PgVectorStore pgVectorStore(
            Ai ai,
            DataSource dataSource,
            AirProperties properties) {
        var store = new PgVectorStoreBuilder()
                .withName("docs")
                .withDataSource(dataSource)
                .withEmbeddingService(ai.withDefaultEmbeddingService())
                .withChunkerConfig(properties.chunkerConfig())
                .withChunkTransformer(AddTitlesChunkTransformer.INSTANCE)
                .build();
        logger.info("Loaded {} chunks into Lucene RAG store", store.info().getChunkCount());
        return store;
    }

    @Bean
    AirlinePolicies airlinePolicies(SearchOperations searchOperations) {
        return new AirlinePolicies(
                new ToolishRag("policies", "Embabel Air policies", searchOperations)
                        .asMatryoshka()
        );
    }

    /**
     * Typed wrapper for airline policies ToolishRag - enables clean injection via @Provided.
     */
    public record AirlinePolicies(LlmReference reference) {
    }

}
