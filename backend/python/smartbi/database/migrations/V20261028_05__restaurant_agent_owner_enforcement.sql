-- Restaurant agent owner isolation contract phase.
-- Apply only after new Java, new Python and RN are healthy on V03 with the route OFF:
--   route gate OFF -> V03 -> new Java -> new Python -> RN -> health verification
--   -> V05 -> route gate ON.
-- After V05, old code that omits app.user_id is intentionally incompatible. Rollback is
-- route gate OFF plus roll-forward; do not deploy old Java/Python against this contract.

SET LOCAL lock_timeout = '5s';

-- NOT VALID avoids scanning or guessing historical NULL owners. PostgreSQL still
-- enforces this check for every row inserted or updated after the contract is applied.
ALTER TABLE smart_bi_agent_run
    ADD CONSTRAINT smart_bi_agent_run_owner_required_for_new_rows
    CHECK (owner_user_id IS NOT NULL) NOT VALID;

DROP POLICY IF EXISTS smart_bi_agent_run_tenant_select ON smart_bi_agent_run;
CREATE POLICY smart_bi_agent_run_tenant_select ON smart_bi_agent_run FOR SELECT USING (
    factory_id = current_setting('app.factory_id', true)
    AND NULLIF(current_setting('app.user_id', true), '') IS NOT NULL
    AND owner_user_id = NULLIF(current_setting('app.user_id', true), '')
);
DROP POLICY IF EXISTS smart_bi_agent_run_tenant_admin_audit_select ON smart_bi_agent_run;
CREATE POLICY smart_bi_agent_run_tenant_admin_audit_select ON smart_bi_agent_run
FOR SELECT USING (
    factory_id = current_setting('app.factory_id', true)
    AND NULLIF(current_setting('app.user_id', true), '') IS NOT NULL
    AND current_setting('app.agent_ops_audit', true) = 'true'
    AND current_setting('app.actor_role', true) IN (
        'factory_super_admin', 'platform_admin', 'permission_admin',
        'restaurant_manager', 'restaurant_owner'
    )
);
DROP POLICY IF EXISTS smart_bi_agent_run_tenant_insert ON smart_bi_agent_run;
CREATE POLICY smart_bi_agent_run_tenant_insert ON smart_bi_agent_run FOR INSERT WITH CHECK (
    factory_id = current_setting('app.factory_id', true)
    AND NULLIF(current_setting('app.user_id', true), '') IS NOT NULL
    AND owner_user_id = NULLIF(current_setting('app.user_id', true), '')
);
DROP POLICY IF EXISTS smart_bi_agent_run_tenant_update ON smart_bi_agent_run;
CREATE POLICY smart_bi_agent_run_tenant_update ON smart_bi_agent_run FOR UPDATE USING (
    factory_id = current_setting('app.factory_id', true)
    AND NULLIF(current_setting('app.user_id', true), '') IS NOT NULL
    AND owner_user_id = NULLIF(current_setting('app.user_id', true), '')
) WITH CHECK (
    factory_id = current_setting('app.factory_id', true)
    AND NULLIF(current_setting('app.user_id', true), '') IS NOT NULL
    AND owner_user_id = NULLIF(current_setting('app.user_id', true), '')
);
DROP POLICY IF EXISTS smart_bi_agent_event_tenant_select ON smart_bi_agent_event;
CREATE POLICY smart_bi_agent_event_tenant_select ON smart_bi_agent_event FOR SELECT USING (
    factory_id = current_setting('app.factory_id', true)
    AND NULLIF(current_setting('app.user_id', true), '') IS NOT NULL
    AND EXISTS (
        SELECT 1 FROM smart_bi_agent_run owned
        WHERE owned.run_id = smart_bi_agent_event.run_id
          AND owned.factory_id = smart_bi_agent_event.factory_id
          AND owned.owner_user_id = NULLIF(current_setting('app.user_id', true), '')
    )
);
DROP POLICY IF EXISTS smart_bi_agent_event_tenant_admin_audit_select ON smart_bi_agent_event;
CREATE POLICY smart_bi_agent_event_tenant_admin_audit_select ON smart_bi_agent_event
FOR SELECT USING (
    factory_id = current_setting('app.factory_id', true)
    AND NULLIF(current_setting('app.user_id', true), '') IS NOT NULL
    AND current_setting('app.agent_ops_audit', true) = 'true'
    AND current_setting('app.actor_role', true) IN (
        'factory_super_admin', 'platform_admin', 'permission_admin',
        'restaurant_manager', 'restaurant_owner'
    )
);
DROP POLICY IF EXISTS smart_bi_agent_event_tenant_insert ON smart_bi_agent_event;
CREATE POLICY smart_bi_agent_event_tenant_insert ON smart_bi_agent_event FOR INSERT WITH CHECK (
    factory_id = current_setting('app.factory_id', true)
    AND NULLIF(current_setting('app.user_id', true), '') IS NOT NULL
    AND EXISTS (
        SELECT 1 FROM smart_bi_agent_run owned
        WHERE owned.run_id = smart_bi_agent_event.run_id
          AND owned.factory_id = smart_bi_agent_event.factory_id
          AND owned.owner_user_id = NULLIF(current_setting('app.user_id', true), '')
    )
);

COMMENT ON CONSTRAINT smart_bi_agent_run_owner_required_for_new_rows
    ON smart_bi_agent_run IS
    'Contract phase: historical NULL owners remain unvalidated and invisible; all new or updated rows require an owner.';
