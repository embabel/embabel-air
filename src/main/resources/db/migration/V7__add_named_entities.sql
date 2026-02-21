CREATE TABLE IF NOT EXISTS named_entities (
    id VARCHAR(255) PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    uri TEXT,
    labels TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    properties JSONB NOT NULL DEFAULT '{}',
    metadata JSONB NOT NULL DEFAULT '{}',
    context_id VARCHAR(255),
    embedding vector(1536)
);

CREATE TABLE IF NOT EXISTS entity_relationships (
    id SERIAL PRIMARY KEY,
    source_id VARCHAR(255) NOT NULL REFERENCES named_entities(id) ON DELETE CASCADE,
    target_id VARCHAR(255) NOT NULL REFERENCES named_entities(id) ON DELETE CASCADE,
    relationship_name VARCHAR(255) NOT NULL,
    properties JSONB NOT NULL DEFAULT '{}',
    UNIQUE(source_id, target_id, relationship_name)
);

CREATE INDEX IF NOT EXISTS idx_entities_labels ON named_entities USING gin (labels);
CREATE INDEX IF NOT EXISTS idx_entities_embedding ON named_entities USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_entities_properties ON named_entities USING gin (properties);
CREATE INDEX IF NOT EXISTS idx_entities_context ON named_entities(context_id);
CREATE INDEX IF NOT EXISTS idx_relationships_source ON entity_relationships(source_id);
CREATE INDEX IF NOT EXISTS idx_relationships_target ON entity_relationships(target_id);
