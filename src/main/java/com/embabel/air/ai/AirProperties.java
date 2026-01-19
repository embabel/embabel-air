package com.embabel.air.ai;

import com.embabel.agent.rag.ingestion.ContentChunker;
import com.embabel.common.ai.model.LlmOptions;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Properties for chatbot
 *
 * @param chatLlm       LLM model and hyperparameters to use
 * @param chunkerConfig configuration for ingestion
 */
@ConfigurationProperties(prefix = "embabel-air")
public record AirProperties(
        boolean showChatPrompts,
        @NestedConfigurationProperty LlmOptions chatLlm,
        @NestedConfigurationProperty ContentChunker.Config chunkerConfig
) {
}


