package com.embabel.air.config;

import com.embabel.common.ai.model.EmbeddingService;
import com.embabel.common.ai.model.SpringAiEmbeddingService;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

/**
 * Test configuration that provides a fake embedding model.
 * Uses random vectors so vector search won't be semantically meaningful,
 * but with a 0 threshold it will still return results.
 */
@Configuration
public class FakeEmbeddingConfig {

    @Bean
    @Primary
    EmbeddingService fakeEmbeddingService() {
        return new SpringAiEmbeddingService(
                "text-embedding-3-small",
                "Fake",
                new FakeEmbeddingModel(),
                DIMENSIONS
        );
    }

    private static final int DIMENSIONS = 1536;

    private static class FakeEmbeddingModel implements EmbeddingModel {
        private final Random random = new Random(42); // Fixed seed for reproducibility

        @Override
        public float[] embed(Document document) {
            return generateRandomFloatArray();
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            var result = new ArrayList<float[]>();
            for (int i = 0; i < texts.size(); i++) {
                result.add(generateRandomFloatArray());
            }
            return result;
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
            var array = new float[DIMENSIONS];
            for (int i = 0; i < DIMENSIONS; i++) {
                array[i] = random.nextFloat();
            }
            return array;
        }
    }
}
