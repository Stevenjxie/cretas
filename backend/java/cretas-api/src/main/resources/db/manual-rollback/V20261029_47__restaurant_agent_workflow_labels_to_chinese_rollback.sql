-- =============================================================================
-- V20261029_47 回滚: 把餐饮 Agent 审核工作流的节点名还原成英文
--
-- 用法 (手动执行, 不由 Flyway 跑):
--   sudo -u postgres psql -d cretas_prod_db \
--     -f V20261029_47__restaurant_agent_workflow_labels_to_chinese_rollback.sql
--
-- 🔴 回滚这条**必须同时回滚代码**(Provisioner 的 NODES_JSON/DESCRIPTION)。
--    isCanonical() 逐字比对二者, 只回滚一边 → 37 行全部 non-canonical →
--    503 RESTAURANT_AGENT_ACTION_WORKFLOW_INVALID。单独回滚数据比不回滚更糟。
--
-- ⚠️ 只还原**还是中文 canonical**的行: 迁移之后若有租户又自己改过, 不覆盖他。
-- =============================================================================

\echo '--- 回滚前: 台账多少行, 其中多少仍可安全还原 ---'
SELECT count(*) AS 台账总数,
       count(*) FILTER (WHERE w.nodes_json::text LIKE '%复核菜品成本数据%') AS 可还原,
       count(*) FILTER (WHERE w.nodes_json::text NOT LIKE '%复核菜品成本数据%') AS 已被改过跳过
FROM backup_restaurant_agent_wf_20260802 b
JOIN approval_workflows w ON w.id = b.workflow_id;

UPDATE approval_workflows w
SET nodes_json  = b.old_nodes_json::jsonb,
    description = b.old_description,
    updated_at  = NOW()
FROM backup_restaurant_agent_wf_20260802 b
WHERE w.id = b.workflow_id
  AND w.nodes_json::text LIKE '%复核菜品成本数据%';

\echo '--- 回滚后核对 (应全部回到英文 label) ---'
SELECT count(*) AS 已还原为英文
FROM backup_restaurant_agent_wf_20260802 b
JOIN approval_workflows w ON w.id = b.workflow_id
WHERE w.nodes_json::text LIKE '%Review dish cost data%';

-- 台账刻意保留, 使回滚可重复执行。确认不再需要后手动:
--   DROP TABLE backup_restaurant_agent_wf_20260802;
