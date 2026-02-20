package com.embabel.air;

import com.embabel.agent.spi.LlmService;
import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import com.embabel.common.ai.model.DefaultOptionsConverter;
import com.embabel.common.ai.model.EmbeddingService;
import com.embabel.common.ai.model.SpringAiEmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import static org.mockito.Mockito.mock;

/**
 * Test configuration that provides mock LLM services.
 * Enables tests to run without OPENAI_API_KEY or ANTHROPIC_API_KEY.
 */
@TestConfiguration
public class TestAiConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(TestAiConfiguration.class);

    public TestAiConfiguration() {
        logger.info("Using test AI configuration with mock models");
    }

    @Bean
    LlmService<?> defaultLlm() {
        return new SpringAiLlmService(
                "gpt-4.1-mini",
                "OpenAI",
                mock(ChatModel.class),
                DefaultOptionsConverter.INSTANCE
        );
    }

    @Bean
    LlmService<?> bestLlm() {
        return new SpringAiLlmService(
                "gpt-4.1",
                "OpenAI",
                mock(ChatModel.class),
                DefaultOptionsConverter.INSTANCE
        );
    }

    @Bean
    EmbeddingService embeddingService() {
        return new SpringAiEmbeddingService(
                "text-embedding-3-small",
                "OpenAI",
                new FakeEmbeddingModel(1536),
                1536
        );
    }

    /**
     * Primary ObjectMapper to resolve conflict between embabelJacksonObjectMapper
     * and hillaEndpointObjectMapper.
     */
    @Bean
    @Primary
    ObjectMapper primaryObjectMapper(Jackson2ObjectMapperBuilder builder) {
        return builder.build();
    }

    /**
     * Simple embedding model that returns random embeddings for testing.
     */
    private static class FakeEmbeddingModel implements EmbeddingModel {
        private final int dimensions;
        private final Random random = new Random(42);

        FakeEmbeddingModel(int dimensions) {
            this.dimensions = dimensions;
        }

        @Override
        public float[] embed(Document document) {
            return generateRandomFloatArray();
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            return texts.stream()
                    .map(text -> generateRandomFloatArray())
                    .toList();
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            var output = new LinkedList<Embedding>();
            for (int i = 0; i < request.getInstructions().size(); i++) {
                output.add(new Embedding(generateRandomFloatArray(), i));
            }
            return new EmbeddingResponse(output);
        }

        private float[] generateRandomFloatArray() {
            float[] array = new float[dimensions];
            for (int i = 0; i < dimensions; i++) {
                array[i] = random.nextFloat();
            }
            return array;
        }
    }
}
