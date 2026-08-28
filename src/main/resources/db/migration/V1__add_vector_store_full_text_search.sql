-- Adopt existing development schemas at baseline 0; never rebuild ingested data.
-- Also allow a fresh test database to migrate before Spring AI initializes its store.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS public.vector_store (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content TEXT,
    metadata JSONB,
    embedding VECTOR(768)
);

-- Stored generation backfills existing rows and follows future content/filename updates.
ALTER TABLE public.vector_store
    ADD COLUMN search_vector TSVECTOR GENERATED ALWAYS AS (
        setweight(to_tsvector('french'::regconfig, coalesce(metadata->>'source', '')), 'A') ||
        setweight(to_tsvector('french'::regconfig, coalesce(content, '')), 'D')
    ) STORED;

CREATE INDEX idx_vector_store_search_vector_gin
    ON public.vector_store USING GIN (search_vector);
