package com.embabel.air.ai.config;

import com.embabel.agent.rag.ingestion.transform.AddTitlesChunkTransformer;
import com.embabel.agent.rag.pgvector.PgVectorStore;
import com.embabel.agent.rag.pgvector.PgVectorStoreBuilder;
import com.embabel.air.ai.AirProperties;
import com.embabel.common.ai.model.DefaultModelSelectionCriteria;
import com.embabel.common.ai.model.ModelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
@EnableConfigurationProperties(AirProperties.class)
class RagConfiguration {

    private final Logger logger = LoggerFactory.getLogger(RagConfiguration.class);

    @Bean
    PgVectorStore pgVectorStore(
            ModelProvider modelProvider,
            DataSource dataSource,
            AirProperties properties) {
        var embeddingService = modelProvider.getEmbeddingService(DefaultModelSelectionCriteria.INSTANCE);
        var store = new PgVectorStoreBuilder()
                .withName("docs")
                .withDataSource(dataSource)
                .withEmbeddingService(embeddingService)
                .withChunkerConfig(properties.chunkerConfig())
                .withChunkTransformer(AddTitlesChunkTransformer.INSTANCE)
                .build();
        logger.info("Loaded {} chunks into Lucene RAG store", store.info().getChunkCount());
        return store;
    }

}
