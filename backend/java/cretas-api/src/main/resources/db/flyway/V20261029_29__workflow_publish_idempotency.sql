-- Durable idempotency identity for atomic Workflow BOM-sync/publish/activation.
-- Historical published rows intentionally remain NULL because no request key can
-- be reconstructed safely after the fact.

ALTER TABLE product_process_workflows
    ADD COLUMN IF NOT EXISTS last_publish_idempotency_key VARCHAR(128),
    ADD COLUMN IF NOT EXISTS last_publish_revision_id BIGINT,
    ADD COLUMN IF NOT EXISTS last_publish_revision_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS last_publish_definition_version INTEGER;

CREATE UNIQUE INDEX IF NOT EXISTS uk_ppw_publish_idempotency_factory_key
    ON product_process_workflows(factory_id, last_publish_idempotency_key)
    WHERE deleted_at IS NULL
      AND last_publish_idempotency_key IS NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'fk_ppw_last_publish_revision'
    ) THEN
        ALTER TABLE product_process_workflows
            ADD CONSTRAINT fk_ppw_last_publish_revision
            FOREIGN KEY (last_publish_revision_id)
            REFERENCES product_process_workflow_revisions(id)
            ON DELETE RESTRICT;
    END IF;
END $$;

COMMENT ON COLUMN product_process_workflows.last_publish_idempotency_key IS
    'Factory-scoped durable key of the successfully completed publish-and-activate command';
COMMENT ON COLUMN product_process_workflows.last_publish_revision_id IS
    'Immutable Workflow revision identity bound to last_publish_idempotency_key';
