-- =============================================================================
-- ONDA Agentic RAG — PostgreSQL 17 Initialization Script
-- Executed once by docker-entrypoint-initdb.d on first container startup.
--
-- Sequence:
--   1. Enable extensions
--   2. Create domain tables (users, conversations, chat_messages, documents)
--   3. Create Spring AI vector_store table
--   4. Create indexes (FK indexes, HNSW, GIN)
--   5. Add unique constraints
-- =============================================================================

-- =============================================================================
-- 1. Extensions
-- =============================================================================
CREATE EXTENSION IF NOT EXISTS vector;       -- pgvector: HNSW similarity search
CREATE EXTENSION IF NOT EXISTS "uuid-ossp"; -- UUID generation helper

-- =============================================================================
-- 2. Domain Tables
-- =============================================================================

-- ----------------------------------------------------------------------------
-- users
--   Mirrors Keycloak user identity. keycloak_id links JWT sub claim.
--   BaseEntity audit fields (created_by, last_modified_by) map to String/username.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
                                     id                UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    keycloak_id       VARCHAR(255) NOT NULL UNIQUE,
    username          VARCHAR(50)  NOT NULL UNIQUE,
    email             VARCHAR(255) NOT NULL UNIQUE,
    first_name        VARCHAR(100) NOT NULL,
    last_name         VARCHAR(100) NOT NULL,
    -- BaseEntity audit columns
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by        VARCHAR(255),
    last_modified_at  TIMESTAMP,
    last_modified_by  VARCHAR(255)
    );

-- ----------------------------------------------------------------------------
-- conversations
--   A conversation thread is owned by one user.
--   The backend system generates the UUID — never the client.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS conversations (
                                             id                UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id           UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title             VARCHAR(255) NOT NULL DEFAULT 'New Conversation',
    -- BaseEntity audit columns
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by        VARCHAR(255),
    last_modified_at  TIMESTAMP,
    last_modified_by  VARCHAR(255)
    );

-- ----------------------------------------------------------------------------
-- chat_messages
--   Ordered message history linked to a conversation.
--   sequence_number guarantees deterministic ordering.
--   UNIQUE(conversation_id, sequence_number) prevents duplicate positions.
--   metadata JSONB stores only safe execution facts:
--     { "tools_used": [...], "cited_sources": [...], "token_usage": {...} }
--   Agent internal reasoning / chain-of-thought must NOT be stored here.
-- ----------------------------------------------------------------------------
CREATE TYPE message_type AS ENUM ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL');

CREATE TABLE IF NOT EXISTS chat_messages (
                                             id                UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    conversation_id   UUID         NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    sequence_number   INTEGER      NOT NULL,
    message_type      message_type NOT NULL,
    content           TEXT         NOT NULL,
    metadata          JSONB,
    -- BaseEntity audit columns (only created_at is meaningful here; messages are immutable)
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by        VARCHAR(255),
    last_modified_at  TIMESTAMP,
    last_modified_by  VARCHAR(255),
    -- Deterministic ordering constraint
    CONSTRAINT uq_chat_messages_conversation_sequence UNIQUE (conversation_id, sequence_number)
    );

-- ----------------------------------------------------------------------------
-- documents
--   Tracks ingested knowledge base files.
--   Status lifecycle: PROCESSING → COMPLETED | FAILED
--   uploaded_by → users.id (simple ownership; no sharing model for now).
--   Vectors linked via metadata->>'document_id' in vector_store.
-- ----------------------------------------------------------------------------
CREATE TYPE document_status AS ENUM ('PROCESSING', 'COMPLETED', 'FAILED');

CREATE TABLE IF NOT EXISTS documents (
                                         id                UUID            PRIMARY KEY DEFAULT uuid_generate_v4(),
    name              VARCHAR(500)    NOT NULL,
    content_type      VARCHAR(100)    NOT NULL,
    size_bytes        BIGINT          NOT NULL,
    status            document_status NOT NULL DEFAULT 'PROCESSING',
    uploaded_by       UUID            NOT NULL REFERENCES users(id),
    -- BaseEntity audit columns
    created_at        TIMESTAMP       NOT NULL DEFAULT NOW(),
    created_by        VARCHAR(255),
    last_modified_at  TIMESTAMP,
    last_modified_by  VARCHAR(255)
    );

-- =============================================================================
-- 3. Spring AI Vector Store Table
--    Spring AI pgvector auto-creates this if spring.ai.vectorstore.pgvector.initialize-schema=true.
--    Defined here explicitly for transparency and documentation.
--    Vector dimension: 768
--    DO NOT modify this schema unless the EmbeddingModel dimension changes.
--
--    metadata structure (JSONB):
--    {
--      "document_id": "uuid-of-documentEntity-record",
--      "chunk_index": 12,
--      "source":      "onda-security-policy.pdf"
--    }
-- =============================================================================
CREATE TABLE IF NOT EXISTS vector_store (
                                            id        UUID    PRIMARY KEY DEFAULT uuid_generate_v4(),
    content   TEXT    NOT NULL,
    metadata  JSONB   NOT NULL DEFAULT '{}',
    embedding VECTOR(768) NOT NULL
    );

-- =============================================================================
-- 4. Indexes
-- =============================================================================

-- --- FK Indexes (critical for WHERE user_id = ?, WHERE conversation_id = ?) ---
CREATE INDEX IF NOT EXISTS idx_conversations_user_id
    ON conversations(user_id);

CREATE INDEX IF NOT EXISTS idx_chat_messages_conversation_id
    ON chat_messages(conversation_id);

CREATE INDEX IF NOT EXISTS idx_documents_uploaded_by
    ON documents(uploaded_by);

-- --- Chat message ordering index (sequence_number ASC lookups) ---
CREATE INDEX IF NOT EXISTS idx_chat_messages_conversation_sequence
    ON chat_messages(conversation_id, sequence_number ASC);

-- --- Vector store: HNSW index for approximate nearest-neighbour search ---
--    m=16: max connections per layer
--    ef_construction=64: build-time search width (higher = better recall, slower build)
CREATE INDEX IF NOT EXISTS idx_vector_store_hnsw
    ON vector_store
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- --- Vector store: GIN index on metadata for document_id lookups ---
CREATE INDEX IF NOT EXISTS idx_vector_store_metadata_gin
    ON vector_store
    USING gin (metadata);

-- --- Vector store: fast lookup of all chunks belonging to a documentEntity ---
CREATE INDEX IF NOT EXISTS idx_vector_store_document_id
    ON vector_store ((metadata->>'document_id'));
