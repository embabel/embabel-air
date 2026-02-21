package com.embabel.air.ai.memory;

import com.embabel.agent.api.common.Ai;
import com.embabel.agent.core.DataDictionary;
import com.embabel.agent.rag.model.NamedEntity;
import com.embabel.agent.rag.pgvector.JpaNamedEntityDataRepository;
import com.embabel.agent.rag.pgvector.NativeEntityLookup;
import com.embabel.agent.rag.service.NamedEntityDataRepository;
import com.embabel.air.ai.AirProperties;
import com.embabel.air.backend.City;
import com.embabel.air.backend.Country;
import com.embabel.air.backend.Customer;
import com.embabel.air.backend.CustomerRepository;
import com.embabel.dice.common.EntityResolver;
import com.embabel.dice.common.KnowledgeType;
import com.embabel.dice.common.Relations;
import com.embabel.dice.common.resolver.BakeoffPromptStrategies;
import com.embabel.dice.common.resolver.EscalatingEntityResolver;
import com.embabel.dice.common.resolver.LlmCandidateBakeoff;
import com.embabel.dice.incremental.ChunkHistoryStore;
import com.embabel.dice.incremental.InMemoryChunkHistoryStore;
import com.embabel.dice.pipeline.PropositionPipeline;
import com.embabel.dice.projection.graph.*;
import com.embabel.dice.projection.memory.MemoryProjector;
import com.embabel.dice.projection.memory.support.DefaultMemoryProjector;
import com.embabel.dice.projection.memory.support.RelationBasedKnowledgeTypeClassifier;
import com.embabel.dice.proposition.PropositionRepository;
import com.embabel.dice.proposition.extraction.IncrementalPropositionExtraction;
import com.embabel.dice.proposition.extraction.LlmPropositionExtractor;
import com.embabel.dice.proposition.jdbc.JdbcPropositionRepository;
import com.embabel.dice.proposition.revision.LlmPropositionReviser;
import com.embabel.dice.proposition.revision.PropositionReviser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Assembles the full DICE memory pipeline for Embabel Air:
 * proposition extraction, entity resolution, graph projection, and memory recall.
 */
@Configuration
@EnableAsync
public class MemoryConfiguration {

    @Bean
    @Primary
    DataDictionary airSchema() {
        return DataDictionary.fromClasses("embabel-air", Customer.class)
                .plus(NamedEntity.dataDictionaryFromPackages("com.embabel.air.backend"));
    }

    @Bean
    Relations airRelations() {
        return Relations.empty()
                .withPredicatesForSubject(Customer.class, KnowledgeType.SEMANTIC,
                        "prefers", "likes", "dislikes", "lives_in", "travels_to", "is_member_of")
                .withPredicatesForSubject(City.class, KnowledgeType.SEMANTIC,
                        "is_in", "has_airport")
                .withPredicatesForSubject(Country.class, KnowledgeType.SEMANTIC,
                        "contains");
    }

    @Bean
    JdbcPropositionRepository jpaPropositionRepository(JdbcClient jdbcClient, Ai ai) {
        return new JdbcPropositionRepository(jdbcClient, ai.withDefaultEmbeddingService());
    }

    @Bean
    NamedEntityDataRepository namedEntityDataRepository(
            JdbcClient jdbcClient, Ai ai, DataDictionary dataDictionary,
            CustomerRepository customerRepository) {
        return JpaNamedEntityDataRepository.builder()
                .withJdbcClient(jdbcClient)
                .withDataDictionary(dataDictionary)
                .withEmbeddingService(ai.withDefaultEmbeddingService())
                .withNativeLookup(Customer.class, new NativeEntityLookup<>() {
                    @Override
                    public Customer findById(String id) {
                        return customerRepository.findById(id).orElse(null);
                    }

                    @Override
                    public java.util.List<Customer> findAll() {
                        return customerRepository.findAll();
                    }
                })
                .build();
    }

    @Bean
    EntityResolver entityResolver(NamedEntityDataRepository repository, Ai ai, AirProperties properties) {
        var bakeoff = LlmCandidateBakeoff
                .withLlm(properties.memory() != null && properties.memory().getExtractionLlm() != null
                        ? properties.memory().getExtractionLlm()
                        : properties.chatLlm())
                .withAi(ai)
                .withPromptStrategy(BakeoffPromptStrategies.FULL);
        return EscalatingEntityResolver.create(repository, bakeoff);
    }

    @Bean
    LlmPropositionExtractor llmPropositionExtractor(Ai ai, AirProperties properties) {
        var llm = properties.memory() != null && properties.memory().getExtractionLlm() != null
                ? properties.memory().getExtractionLlm()
                : properties.chatLlm();
        return LlmPropositionExtractor
                .withLlm(llm)
                .withAi(ai)
                .withTemplate("dice/extract_air_propositions");
    }

    @Bean
    PropositionReviser propositionReviser(Ai ai, AirProperties properties) {
        var llm = properties.memory() != null && properties.memory().getExtractionLlm() != null
                ? properties.memory().getExtractionLlm()
                : properties.chatLlm();
        return LlmPropositionReviser
                .withLlm(llm)
                .withAi(ai);
    }

    @Bean
    PropositionPipeline propositionPipeline(
            LlmPropositionExtractor extractor,
            PropositionReviser reviser,
            JdbcPropositionRepository repository) {
        return PropositionPipeline
                .withExtractor(extractor)
                .withRevision(reviser, repository);
    }

    @Bean
    GraphProjector graphProjector(Relations relations, Ai ai, AirProperties properties) {
        var llm = properties.memory() != null && properties.memory().getExtractionLlm() != null
                ? properties.memory().getExtractionLlm()
                : properties.chatLlm();
        return LlmGraphProjector
                .withLlm(llm)
                .withAi(ai)
                .withRelations(relations)
                .withLenientPolicy();
    }

    @Bean
    GraphRelationshipPersister graphRelationshipPersister(NamedEntityDataRepository repository) {
        return new NamedEntityDataRepositoryGraphRelationshipPersister(repository);
    }

    @Bean
    GraphProjectionService graphProjectionService(
            GraphProjector graphProjector,
            GraphRelationshipPersister persister,
            DataDictionary dataDictionary) {
        return GraphProjectionService.create(graphProjector, persister, dataDictionary);
    }

    @Bean
    MemoryProjector memoryProjector(Relations relations) {
        return DefaultMemoryProjector
                .withKnowledgeTypeClassifier(new RelationBasedKnowledgeTypeClassifier(relations));
    }

    @Bean
    ChunkHistoryStore chunkHistoryStore() {
        return new InMemoryChunkHistoryStore();
    }

    @Bean
    IncrementalPropositionExtraction incrementalPropositionExtraction(
            PropositionPipeline pipeline,
            ChunkHistoryStore chunkHistoryStore,
            DataDictionary dataDictionary,
            Relations relations,
            PropositionRepository propositionRepository,
            NamedEntityDataRepository entityRepository,
            EntityResolver entityResolver,
            GraphProjectionService graphProjectionService,
            AirProperties properties) {
        return new IncrementalPropositionExtraction(
                pipeline,
                chunkHistoryStore,
                dataDictionary,
                relations,
                propositionRepository,
                entityRepository,
                entityResolver,
                graphProjectionService,
                properties.memory(),
                user -> user.getId(),
                user -> java.util.Map.of("customer", user)
        );
    }
}
