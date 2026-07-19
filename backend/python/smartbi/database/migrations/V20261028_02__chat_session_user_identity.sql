-- Bind SmartBI conversation memory to the authenticated user as well as the
-- tenant. Anonymous legacy rows cannot be assigned safely, so delete them
-- before making user_id mandatory. This migration is intentionally repeatable:
-- constraint discovery uses catalog truth instead of assuming the original
-- inline UNIQUE constraint retained a particular generated name.
--
-- Expand/contract order (mandatory): first deploy the backward-compatible
-- Python writer in this change with migrations disabled, verify it, and drain
-- every old Python process. Only then apply this migration with the migration
-- runner. Applying the migration before old writers are drained is unsupported.

-- Prevent an old application instance from inserting another anonymous row
-- between cleanup and SET NOT NULL during a rolling release.
SET LOCAL lock_timeout = '5s';
LOCK TABLE smart_bi_chat_session IN ACCESS EXCLUSIVE MODE;

DELETE FROM smart_bi_chat_session
WHERE user_id IS NULL;

ALTER TABLE smart_bi_chat_session
    ALTER COLUMN user_id SET NOT NULL;

-- V20260426_02 declared `session_id ... UNIQUE`. Drop any single-column unique
-- constraint whose constrained column is exactly session_id, regardless of its
-- generated/name-overridden constraint name. Do not touch a composite identity
-- constraint if this migration is re-run.
DO $$
DECLARE
    legacy_constraint record;
    session_attnum smallint;
BEGIN
    SELECT attnum
      INTO session_attnum
      FROM pg_attribute
     WHERE attrelid = 'smart_bi_chat_session'::regclass
       AND attname = 'session_id'
       AND NOT attisdropped;

    FOR legacy_constraint IN
        SELECT conname
          FROM pg_constraint
         WHERE conrelid = 'smart_bi_chat_session'::regclass
           AND contype = 'u'
           AND conkey = ARRAY[session_attnum]::smallint[]
    LOOP
        EXECUTE format(
            'ALTER TABLE smart_bi_chat_session DROP CONSTRAINT %I',
            legacy_constraint.conname
        );
    END LOOP;
END
$$;

-- PostgreSQL has no portable ADD CONSTRAINT IF NOT EXISTS form. Discover an
-- equivalent three-column UNIQUE constraint by attnum set, then add our stable
-- name only when none exists.
DO $$
DECLARE
    identity_attnums smallint[];
BEGIN
    SELECT array_agg(attnum::smallint ORDER BY attname)
      INTO identity_attnums
      FROM pg_attribute
     WHERE attrelid = 'smart_bi_chat_session'::regclass
       AND attname IN ('factory_id', 'session_id', 'user_id')
       AND NOT attisdropped;

    IF coalesce(array_length(identity_attnums, 1), 0) <> 3 THEN
        RAISE EXCEPTION 'smart_bi_chat_session identity columns are incomplete';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = 'smart_bi_chat_session'::regclass
           AND contype = 'u'
           AND conkey @> identity_attnums
           AND conkey <@ identity_attnums
    ) THEN
        ALTER TABLE smart_bi_chat_session
            ADD CONSTRAINT uq_chat_session_factory_user_session
            UNIQUE (factory_id, user_id, session_id);
    END IF;
END
$$;

COMMENT ON COLUMN smart_bi_chat_session.user_id IS
    'Authenticated user identity; required for exact (factory,user,session) memory isolation.';
