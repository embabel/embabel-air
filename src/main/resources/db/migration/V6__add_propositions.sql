CREATE TABLE IF NOT EXISTS propositions (
    id VARCHAR(255) PRIMARY KEY,
    context_id VARCHAR(255) NOT NULL,
    text TEXT NOT NULL,
    confidence DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    decay DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    importance DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    reasoning TEXT,
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    level INTEGER NOT NULL DEFAULT 0,
    reinforce_count INTEGER NOT NULL DEFAULT 0,
    created TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    revised TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_accessed TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    mentions JSONB NOT NULL DEFAULT '[]',
    source_ids TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    grounding TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    metadata JSONB NOT NULL DEFAULT '{}',
    uri TEXT,
    embedding vector(1536)
);

CREATE INDEX IF NOT EXISTS idx_propositions_context ON propositions(context_id);
CREATE INDEX IF NOT EXISTS idx_propositions_status ON propositions(status);
CREATE INDEX IF NOT EXISTS idx_propositions_embedding ON propositions USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_propositions_mentions ON propositions USING gin (mentions);
