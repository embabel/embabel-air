package com.embabel.air.ai;

import com.embabel.agent.rag.ingestion.ContentChunker;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.dice.proposition.extraction.PropositionExtractionProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Properties for chatbot
 *
 * @param chatLlm       LLM model and hyperparameters to use
 * @param chunkerConfig configuration for ingestion
 * @param memory        memory extraction properties
 */
@ConfigurationProperties(prefix = "embabel-air")
public record AirProperties(
        boolean showChatPrompts,
        @NestedConfigurationProperty LlmOptions chatLlm,
        @NestedConfigurationProperty LlmOptions triageLlm,
        @NestedConfigurationProperty ContentChunker.Config chunkerConfig,
        @NestedConfigurationProperty PropositionExtractionProperties memory
) {
}


