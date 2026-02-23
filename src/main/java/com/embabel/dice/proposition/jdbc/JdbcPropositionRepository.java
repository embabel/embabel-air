package com.embabel.dice.proposition.jdbc;

import com.embabel.agent.core.ContextId;
import com.embabel.agent.rag.service.RetrievableIdentifier;
import com.embabel.common.ai.model.EmbeddingService;
import com.embabel.common.core.types.SimilarityResult;
import com.embabel.common.core.types.TextSimilaritySearchRequest;
import com.embabel.dice.proposition.EntityMention;
import com.embabel.dice.proposition.MentionRole;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionRepository;
import com.embabel.dice.proposition.PropositionStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * JDBC/pgvector-backed implementation of {@link PropositionRepository}.
 * Uses {@link JdbcClient} for direct SQL operations against the propositions table.
 */
public class JdbcPropositionRepository implements PropositionRepository {

    private static final Logger logger = LoggerFactory.getLogger(JdbcPropositionRepository.class);

    private final JdbcClient jdbcClient;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper;

    public JdbcPropositionRepository(JdbcClient jdbcClient, @Nullable EmbeddingService embeddingService) {
        this.jdbcClient = jdbcClient;
        this.embeddingService = embeddingService;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @Override
    public Proposition save(Proposition proposition) {
        String embedding = null;
        if (embeddingService != null) {
            float[] vec = embeddingService.embed(proposition.getText());
            embedding = floatArrayToString(vec);
        }

        String mentionsJson;
        String metadataJson;
        try {
            mentionsJson = objectMapper.writeValueAsString(proposition.getMentions());
            metadataJson = objectMapper.writeValueAsString(proposition.getMetadata());
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize proposition data", e);
        }

        jdbcClient.sql("""
                INSERT INTO propositions (id, context_id, text, confidence, decay, importance, reasoning,
                    status, level, reinforce_count, created, revised, last_accessed,
                    mentions, source_ids, grounding, metadata, uri, embedding)
                VALUES (:id, :contextId, :text, :confidence, :decay, :importance, :reasoning,
                    :status, :level, :reinforceCount, :created, :revised, :lastAccessed,
                    :mentions::jsonb, :sourceIds, :grounding, :metadata::jsonb, :uri, CAST(:embedding AS vector))
                ON CONFLICT (id) DO UPDATE SET
                    context_id = EXCLUDED.context_id,
                    text = EXCLUDED.text,
                    confidence = EXCLUDED.confidence,
                    decay = EXCLUDED.decay,
                    importance = EXCLUDED.importance,
                    reasoning = EXCLUDED.reasoning,
                    status = EXCLUDED.status,
                    level = EXCLUDED.level,
                    reinforce_count = EXCLUDED.reinforce_count,
                    revised = EXCLUDED.revised,
                    last_accessed = EXCLUDED.last_accessed,
                    mentions = EXCLUDED.mentions,
                    source_ids = EXCLUDED.source_ids,
                    grounding = EXCLUDED.grounding,
                    metadata = EXCLUDED.metadata,
                    uri = EXCLUDED.uri,
                    embedding = EXCLUDED.embedding
                """)
                .param("id", proposition.getId())
                .param("contextId", proposition.getContextIdValue())
                .param("text", proposition.getText())
                .param("confidence", proposition.getConfidence())
                .param("decay", proposition.getDecay())
                .param("importance", proposition.getImportance())
                .param("reasoning", proposition.getReasoning())
                .param("status", proposition.getStatus().name())
                .param("level", proposition.getLevel())
                .param("reinforceCount", proposition.getReinforceCount())
                .param("created", Timestamp.from(proposition.getCreated()))
                .param("revised", Timestamp.from(proposition.getRevised()))
                .param("lastAccessed", Timestamp.from(proposition.getLastAccessed()))
                .param("mentions", mentionsJson)
                .param("sourceIds", proposition.getSourceIds().toArray(new String[0]))
                .param("grounding", proposition.getGrounding().toArray(new String[0]))
                .param("metadata", metadataJson)
                .param("uri", proposition.getUri())
                .param("embedding", embedding)
                .update();

        return proposition;
    }

    @Override
    public Proposition findById(String id) {
        return jdbcClient.sql("SELECT * FROM propositions WHERE id = :id")
                .param("id", id)
                .query(new PropositionRowMapper())
                .optional()
                .orElse(null);
    }

    @Override
    public List<Proposition> findAll() {
        return jdbcClient.sql("SELECT * FROM propositions ORDER BY created DESC")
                .query(new PropositionRowMapper())
                .list();
    }

    @Override
    public boolean delete(String id) {
        int count = jdbcClient.sql("DELETE FROM propositions WHERE id = :id")
                .param("id", id)
                .update();
        return count > 0;
    }

    @Override
    public int count() {
        return jdbcClient.sql("SELECT COUNT(*) FROM propositions")
                .query(Integer.class)
                .single();
    }

    @Override
    public List<Proposition> findByEntity(RetrievableIdentifier entityIdentifier) {
        return jdbcClient.sql("""
                SELECT * FROM propositions
                WHERE mentions @> :mentionFilter::jsonb
                ORDER BY created DESC
                """)
                .param("mentionFilter", "[{\"resolvedId\":\"" + entityIdentifier.getId() + "\"}]")
                .query(new PropositionRowMapper())
                .list();
    }

    @Override
    public List<SimilarityResult<Proposition>> findSimilarWithScores(TextSimilaritySearchRequest request) {
        if (embeddingService == null) {
            logger.warn("Vector search requested but no embedding service configured");
            return Collections.emptyList();
        }

        float[] queryVec = embeddingService.embed(request.getQuery());
        String embedding = floatArrayToString(queryVec);

        return jdbcClient.sql("""
                SELECT *, (1 - (embedding <=> CAST(:embedding AS vector))) AS score
                FROM propositions
                WHERE embedding IS NOT NULL
                ORDER BY embedding <=> CAST(:embedding AS vector)
                LIMIT :topK
                """)
                .param("embedding", embedding)
                .param("topK", request.getTopK())
                .query((rs, rowNum) -> {
                    Proposition prop = new PropositionRowMapper().mapRow(rs, rowNum);
                    double score = rs.getDouble("score");
                    return SimilarityResult.create(prop, score);
                })
                .list()
                .stream()
                .filter(r -> r.getScore() >= request.getSimilarityThreshold())
                .collect(Collectors.toList());
    }

    @Override
    public List<Proposition> findByStatus(PropositionStatus status) {
        return jdbcClient.sql("SELECT * FROM propositions WHERE status = :status ORDER BY created DESC")
                .param("status", status.name())
                .query(new PropositionRowMapper())
                .list();
    }

    @Override
    public List<Proposition> findByGrounding(String chunkId) {
        return jdbcClient.sql("SELECT * FROM propositions WHERE :chunkId = ANY(grounding) ORDER BY created DESC")
                .param("chunkId", chunkId)
                .query(new PropositionRowMapper())
                .list();
    }

    @Override
    public List<Proposition> findByMinLevel(int minLevel) {
        return jdbcClient.sql("SELECT * FROM propositions WHERE level >= :minLevel ORDER BY created DESC")
                .param("minLevel", minLevel)
                .query(new PropositionRowMapper())
                .list();
    }

    @Override
    public List<Proposition> findByContextIdValue(String contextIdValue) {
        return jdbcClient.sql("SELECT * FROM propositions WHERE context_id = :contextId ORDER BY created DESC")
                .param("contextId", contextIdValue)
                .query(new PropositionRowMapper())
                .list();
    }

    @Override
    public boolean supportsType(String type) {
        return "Proposition".equals(type);
    }

    @Override
    public String getLuceneSyntaxNotes() {
        return "PostgreSQL pgvector cosine similarity search on proposition text embeddings";
    }

    // === Helpers ===

    private String floatArrayToString(float[] arr) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(arr[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    private class PropositionRowMapper implements RowMapper<Proposition> {

        @Override
        public Proposition mapRow(ResultSet rs, int rowNum) throws SQLException {
            String mentionsJson = rs.getString("mentions");
            List<EntityMention> mentions;
            try {
                if (mentionsJson == null || mentionsJson.isBlank()) {
                    mentions = Collections.emptyList();
                } else {
                    List<Map<String, Object>> mentionMaps = objectMapper.readValue(
                            mentionsJson, new TypeReference<>() {});
                    mentions = mentionMaps.stream().map(m -> new EntityMention(
                            (String) m.getOrDefault("span", ""),
                            (String) m.getOrDefault("type", "Entity"),
                            (String) m.get("resolvedId"),
                            MentionRole.valueOf(
                                    (String) m.getOrDefault("role", "OTHER")),
                            Collections.emptyMap()
                    )).collect(Collectors.toList());
                }
            } catch (Exception e) {
                logger.warn("Failed to parse mentions JSON: {}", e.getMessage());
                mentions = Collections.emptyList();
            }

            java.sql.Array sourceIdsArray = rs.getArray("source_ids");
            List<String> sourceIds = sourceIdsArray != null
                    ? Arrays.asList((String[]) sourceIdsArray.getArray())
                    : Collections.emptyList();

            java.sql.Array groundingArray = rs.getArray("grounding");
            List<String> grounding = groundingArray != null
                    ? Arrays.asList((String[]) groundingArray.getArray())
                    : Collections.emptyList();

            String metadataJson = rs.getString("metadata");
            Map<String, Object> metadata;
            try {
                metadata = (metadataJson == null || metadataJson.isBlank())
                        ? Collections.emptyMap()
                        : objectMapper.readValue(metadataJson, new TypeReference<>() {});
            } catch (Exception e) {
                metadata = Collections.emptyMap();
            }

            Timestamp created = rs.getTimestamp("created");
            Timestamp revised = rs.getTimestamp("revised");
            Timestamp lastAccessed = rs.getTimestamp("last_accessed");

            return Proposition.create(
                    rs.getString("id"),
                    rs.getString("context_id"),
                    rs.getString("text"),
                    mentions,
                    rs.getDouble("confidence"),
                    rs.getDouble("decay"),
                    rs.getDouble("importance"),
                    rs.getString("reasoning"),
                    grounding,
                    created != null ? created.toInstant() : Instant.now(),
                    revised != null ? revised.toInstant() : Instant.now(),
                    lastAccessed != null ? lastAccessed.toInstant() : Instant.now(),
                    PropositionStatus.valueOf(rs.getString("status")),
                    rs.getInt("level"),
                    sourceIds,
                    rs.getInt("reinforce_count"),
                    metadata,
                    rs.getString("uri")
            );
        }
    }
}
