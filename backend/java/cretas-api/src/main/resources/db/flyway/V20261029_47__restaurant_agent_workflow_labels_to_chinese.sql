-- =============================================================================
-- V20261029_47: 餐饮 Agent 审核工作流的节点名改中文
--
-- 背景
--   RestaurantAgentActionWorkflowProvisioner 在代码里就把英文 label 种进去
--   (Submit review / Review dish cost data / Approved + 英文 description),
--   而这些**直接显示在 OA 审批中心的「当前节点」列**给中文用户看。
--   prod 实测 37 个工厂全中招。
--
-- 🔴 为什么必须和代码改动同一个 PR 一起上
--   provisionIfEligible 只在**缺失时**创建 (existsBy... → "already exists;
--   preserving tenant config" 直接 return), 所以改常量不会自动更新存量;
--   而 isCanonical() 把存量 nodes_json 与常量**逐字比对**
--   (objectMapper.readTree(NODES_JSON).equals(readTree(workflow.getNodesJson()))).
--   只改常量 → 37 行全部 non-canonical →
--   RestaurantAgentActionWorkflowService.requireCanonicalWorkflow 抛
--   503 RESTAURANT_AGENT_ACTION_WORKFLOW_INVALID → 整个功能对这 37 个工厂不可用。
--   只跑迁移不改常量, 结果对称地一样坏。两者必须同时。
--
-- 影响面 (2026-08-02 prod cretas_prod_db 实测)
--   37 行 / 37 个工厂, nodes_json **内容完全一致**(同一个 md5 a819af5e…),
--   description 也只有一种 —— 没有任何租户自定义过, 可以整体替换。
--
-- ⛔ 只改**原样未改**的行: WHERE 用旧 canonical 的特征串限定。
--    若某租户自己改过 label, 它不匹配 → 不动它 (它本来就已经是 non-canonical,
--    这条迁移不该顺手"修正"用户的配置)。
--
-- ⚠️ 下面的 JSON 必须与 Provisioner.NODES_JSON **逐字一致**(含空格),
--    否则 isCanonical 仍为 false。改动其一必须同步另一处。
--
-- 回滚
--   scripts: db/manual-rollback/V20261029_47__..._rollback.sql (从台账原样还原)
-- =============================================================================

CREATE TABLE IF NOT EXISTS backup_restaurant_agent_wf_20260802 (
    workflow_id     VARCHAR(64) PRIMARY KEY,
    factory_id      VARCHAR(64),
    old_nodes_json  TEXT,
    old_description TEXT,
    backed_up_at    TIMESTAMP DEFAULT NOW()
);

INSERT INTO backup_restaurant_agent_wf_20260802 (workflow_id, factory_id, old_nodes_json, old_description)
SELECT w.id, w.factory_id, w.nodes_json::text, w.description
FROM approval_workflows w
WHERE w.deleted_at IS NULL
  AND w.decision_type = 'RESTAURANT_AGENT_ACTION_REVIEW'
  AND w.nodes_json::text LIKE '%Review dish cost data%'
ON CONFLICT (workflow_id) DO NOTHING;

-- 行数校验: 干跑时是 37。部署那一刻少了(有人删/改)或多了(新开通租户又被种英文)都要看一眼,
-- 但**不阻断** —— 新租户被种英文正是本次要治的, 数量变化不代表现状被破坏。
DO $$
DECLARE n INTEGER;
BEGIN
    SELECT count(*) INTO n FROM backup_restaurant_agent_wf_20260802;
    RAISE NOTICE 'V20261029_47: 台账 % 行 (2026-08-02 prod 干跑为 37)', n;
    IF n = 0 THEN
        RAISE NOTICE 'V20261029_47: 没有需要改的行 —— 可能已执行过, 跳过';
    END IF;
END $$;

UPDATE approval_workflows w
SET nodes_json = $json$[
  {"id":"start","type":"start","label":"提交复核","position":{"x":60,"y":120},"config":{}},
  {"id":"human_review","type":"approval","label":"复核菜品成本数据","position":{"x":320,"y":120},"config":{"approverRoles":["restaurant_owner","restaurant_manager","finance_manager"],"requiredApprovers":1,"timeoutMinutes":1440}},
  {"id":"approved","type":"end","label":"已通过","position":{"x":600,"y":120},"config":{"outcome":"APPROVED"}}
]$json$::jsonb,
    description = '人工复核缺失的菜品成本数据。审批通过仅解锁跳转到配方数据页，不改动任何数据。',
    updated_at = NOW()
FROM backup_restaurant_agent_wf_20260802 b
WHERE w.id = b.workflow_id;

COMMENT ON TABLE backup_restaurant_agent_wf_20260802 IS
    'V20261029_47 台账: 餐饮 Agent 审核工作流节点名改中文前的原值, 供回滚使用。';
