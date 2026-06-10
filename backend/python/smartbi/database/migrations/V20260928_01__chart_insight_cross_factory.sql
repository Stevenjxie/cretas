-- V20260928_01__chart_insight_cross_factory.sql
-- Chart-Insight 签名去 factoryId (U1.8 — 跨租户模板共享)。
--
-- 背景: 原 UNIQUE(signature_hash, factory_id) 让每个工厂各自蒸馏自己的模板库，
--   飞轮收敛极慢（threshold=3 需要同一工厂同图访问 3 次才能学习）。
--   U1.8 决策（Steve 拍板）：签名去掉 factoryId → 跨租户共享模板 → 快速收敛。
--
-- 安全论证（spec §2.1 §2.6 审计确认）：
--   1. insight_template 只含 {slot} 占位符 — 零租户数据（找不到任何 ¥ 字面量或店名）。
--   2. required_permission + permission_tier（服务端推导）双门控确保 RBAC by construction。
--   3. _safe_fill 兜底：任何未填充的 {slot} → 该字段返 null，绝不透出原始占位符给用户。
--
-- 迁移内容：
--   a. 删除旧 UNIQUE 约束 uk_ait_sig_factory (signature_hash, factory_id)
--   b. 添加新 UNIQUE 约束 uk_ait_sig (signature_hash) — 单列，跨租户唯一
--   c. 清理重复行（按 proposal_count 降序保留最优一行/signature_hash）
--   d. 更新 ai_insight_templates 的 ON CONFLICT 路径已在应用层 Python 代码同步修改
--
-- 幂等保护: DROP CONSTRAINT IF EXISTS + ADD CONSTRAINT IF NOT EXISTS (PostgreSQL 支持).
--
-- collision check: git ls-tree origin/main backend/python/smartbi/database/migrations/ |
--   grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | tail -1 → V20260927_01 (frontier V20260927).
--   V20260928_01 严格高于现有最大版本.
--
-- Rollback:
--   ALTER TABLE ai_insight_templates DROP CONSTRAINT IF EXISTS uk_ait_sig;
--   ALTER TABLE ai_insight_templates ADD CONSTRAINT uk_ait_sig_factory
--       UNIQUE (signature_hash, factory_id);

BEGIN;

-- Step 1: deduplicate rows with duplicate signature_hash (keep highest proposal_count per sig)
-- 先删除低质量重复行，保留每个 signature_hash 的最优行（proposal_count 最高/最新）
DELETE FROM ai_insight_templates a
USING (
    SELECT signature_hash,
           MIN(id) AS keep_id
    FROM (
        SELECT id, signature_hash,
               ROW_NUMBER() OVER (
                   PARTITION BY signature_hash
                   ORDER BY proposal_count DESC, updated_at DESC, id
               ) AS rn
        FROM ai_insight_templates
    ) ranked
    WHERE rn > 1
    GROUP BY signature_hash
) dups
WHERE a.signature_hash = dups.signature_hash
  AND a.id NOT IN (
      SELECT DISTINCT ON (signature_hash) id
      FROM ai_insight_templates
      ORDER BY signature_hash, proposal_count DESC, updated_at DESC, id
  );

-- Step 2: drop old compound unique constraint (if exists)
ALTER TABLE ai_insight_templates
    DROP CONSTRAINT IF EXISTS uk_ait_sig_factory;

-- Step 3: add new single-column unique constraint on signature_hash only
-- DO $$ wrapping to handle "already exists" gracefully (idempotent re-run)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'ai_insight_templates'::regclass
          AND conname = 'uk_ait_sig'
    ) THEN
        ALTER TABLE ai_insight_templates
            ADD CONSTRAINT uk_ait_sig UNIQUE (signature_hash);
    END IF;
END;
$$;

COMMIT;
