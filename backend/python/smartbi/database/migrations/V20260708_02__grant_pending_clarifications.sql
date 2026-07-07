-- 2026-07-08 follow-up to V20260708_01: the migration runner executes as the
-- postgres superuser (apply-smartbi-migrations.sh `sudo -u postgres psql`),
-- so the table it created is not accessible to the app's pool user.
-- Live symptom: "permission denied for table restaurant_pending_clarifications"
-- fail-open warnings on every clarification put/pop — continuation silently
-- disabled. Grant convention mirrors V20260428_03__b_silver_grants.sql.

GRANT SELECT, INSERT, UPDATE, DELETE ON restaurant_pending_clarifications TO smartbi_user;
