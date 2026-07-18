-- Atomic, command-bound preview confirmation.
-- Existing/legacy PENDING rows intentionally become EXPIRED because they have no immutable
-- tool/version/mode/digest binding and therefore cannot be executed safely.

CREATE TABLE IF NOT EXISTS intent_preview_tokens (
    id BIGSERIAL PRIMARY KEY,
    token VARCHAR(64) NOT NULL UNIQUE,
    factory_id VARCHAR(50) NOT NULL,
    tenant_id VARCHAR(50),
    user_id BIGINT NOT NULL,
    username VARCHAR(100),
    intent_code VARCHAR(100) NOT NULL,
    intent_name VARCHAR(200),
    tool_name VARCHAR(150),
    descriptor_version VARCHAR(64),
    execution_mode VARCHAR(20),
    parameters_hash VARCHAR(64),
    command_digest VARCHAR(64),
    entity_type VARCHAR(50),
    entity_id VARCHAR(100),
    operation VARCHAR(20),
    preview_data TEXT,
    current_values TEXT,
    new_values TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    resolved_at TIMESTAMP,
    claim_id VARCHAR(64),
    claimed_at TIMESTAMP,
    resolution_message VARCHAR(500),
    client_info VARCHAR(200)
);

ALTER TABLE intent_preview_tokens
    ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(50),
    ADD COLUMN IF NOT EXISTS tool_name VARCHAR(150),
    ADD COLUMN IF NOT EXISTS descriptor_version VARCHAR(64),
    ADD COLUMN IF NOT EXISTS execution_mode VARCHAR(20),
    ADD COLUMN IF NOT EXISTS parameters_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS command_digest VARCHAR(64),
    ADD COLUMN IF NOT EXISTS claim_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMP;

-- Legacy migration-pg installed a generic update_timestamp trigger even though this table has
-- no updated_at column. Every UPDATE (including atomic claim) would otherwise fail at runtime.
-- claimed_at/resolved_at already provide the lifecycle timestamps this table needs.
DROP TRIGGER IF EXISTS intent_preview_tokens_update_timestamp ON intent_preview_tokens;

UPDATE intent_preview_tokens
SET status = 'EXPIRED',
    resolved_at = COALESCE(resolved_at, CURRENT_TIMESTAMP),
    resolution_message = '旧版预览令牌未绑定执行命令，已安全失效'
WHERE status = 'PENDING'
  AND (tenant_id IS NULL
       OR tool_name IS NULL
       OR descriptor_version IS NULL
       OR execution_mode IS NULL
       OR parameters_hash IS NULL
       OR command_digest IS NULL);

CREATE INDEX IF NOT EXISTS idx_ipt_atomic_claim
    ON intent_preview_tokens
        (token, factory_id, tenant_id, user_id, command_digest, status, expires_at);

CREATE INDEX IF NOT EXISTS idx_ipt_claim_owner
    ON intent_preview_tokens (token, claim_id, status);

CREATE INDEX IF NOT EXISTS idx_ipt_factory_user
    ON intent_preview_tokens (factory_id, user_id);

CREATE INDEX IF NOT EXISTS idx_ipt_status
    ON intent_preview_tokens (status);

CREATE INDEX IF NOT EXISTS idx_ipt_expires
    ON intent_preview_tokens (expires_at);

-- Hibernate may have generated an unnamed enum CHECK before EXECUTING existed. Remove only
-- status checks on this table that do not admit EXECUTING; do not touch execution_mode/digest
-- constraints or checks on any other relation.
DO $$
DECLARE
    legacy_check RECORD;
BEGIN
    FOR legacy_check IN
        SELECT conname
        FROM pg_constraint
        WHERE conrelid = 'intent_preview_tokens'::regclass
          AND contype = 'c'
          AND lower(pg_get_constraintdef(oid)) ~ '(^|[^a-z_])status([^a-z_]|$)'
          AND lower(pg_get_constraintdef(oid)) NOT LIKE '%execution_mode%'
          AND pg_get_constraintdef(oid) NOT LIKE '%EXECUTING%'
    LOOP
        EXECUTE format(
            'ALTER TABLE intent_preview_tokens DROP CONSTRAINT %I',
            legacy_check.conname
        );
    END LOOP;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_ipt_atomic_status'
          AND conrelid = 'intent_preview_tokens'::regclass
    ) THEN
        ALTER TABLE intent_preview_tokens
            ADD CONSTRAINT ck_ipt_atomic_status
            CHECK (status IN ('PENDING', 'EXECUTING', 'CONFIRMED', 'FAILED', 'CANCELLED', 'EXPIRED'));
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_ipt_execution_mode'
          AND conrelid = 'intent_preview_tokens'::regclass
    ) THEN
        ALTER TABLE intent_preview_tokens
            ADD CONSTRAINT ck_ipt_execution_mode
            CHECK (execution_mode IS NULL OR execution_mode IN ('PREVIEW', 'EXECUTE'));
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_ipt_digest_lengths'
          AND conrelid = 'intent_preview_tokens'::regclass
    ) THEN
        ALTER TABLE intent_preview_tokens
            ADD CONSTRAINT ck_ipt_digest_lengths
            CHECK ((parameters_hash IS NULL OR length(parameters_hash) = 64)
               AND (command_digest IS NULL OR length(command_digest) = 64));
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_ipt_executing_claim'
          AND conrelid = 'intent_preview_tokens'::regclass
    ) THEN
        ALTER TABLE intent_preview_tokens
            ADD CONSTRAINT ck_ipt_executing_claim
            CHECK (status <> 'EXECUTING'
                OR (claim_id IS NOT NULL
                    AND claimed_at IS NOT NULL
                    AND tenant_id IS NOT NULL
                    AND tool_name IS NOT NULL
                    AND descriptor_version IS NOT NULL
                    AND execution_mode = 'EXECUTE'
                    AND parameters_hash IS NOT NULL
                    AND command_digest IS NOT NULL));
    END IF;
END $$;
