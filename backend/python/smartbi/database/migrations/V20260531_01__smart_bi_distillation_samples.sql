-- 蒸馏数据管线 — 训练样本沉淀表 (vertical-model distillation corpus)
--
-- 目的: 为未来蒸馏 / 微调垂直行业模型积累 (输入 → 强模型输出) 语料。
-- 主要来源:
--   1. source='materialization' — Fix2 物化时 generate_llm_insights() 的
--      (结构化 KPI/数据 prompt) → (qwen3-max 富洞察 JSON) 对。最干净的蒸馏数据:
--      输入结构化、输出强模型生成、每次物化自动产生。
--   2. source='fallback'        — 长尾开放问题 (已另存 smart_bi_llm_fallback_log,
--      未来可 ETL 进本表统一格式; MVP 先只接 materialization)。
--
-- ⚠️ 故意 NO RLS: 这是内部 ML 训练 sink (派生聚合, 非客户原始数据),
--    仅由内部物化任务写入 + 管理员导出脚本读取, 从不经任何租户端点暴露,
--    且训练集构建本身需要跨业态/跨工厂读取。表名 + 本注释明示其内部用途。
--    任何未来若要给租户端点读它, 必须先加 RLS。
--
-- 迁移 runner 以 postgres 超级用户跑 (apply-smartbi-migrations.sh sudo -u postgres),
-- 表归 postgres → 必须 GRANT 给 app 用户 smartbi_user。

CREATE TABLE IF NOT EXISTS smart_bi_distillation_samples (
    id              BIGSERIAL PRIMARY KEY,
    created_at      TIMESTAMP   NOT NULL DEFAULT NOW(),
    source          VARCHAR(32) NOT NULL,                       -- 'materialization' | 'fallback' | 'manual'
    business_type   VARCHAR(32) NOT NULL DEFAULT 'unknown',     -- 业态分桶: 'restaurant' | 'manufacturing' | 'unknown' (= materializer domain)
    factory_id      VARCHAR(50),
    task_type       VARCHAR(32) NOT NULL,                       -- 'insights' | 'analysis' | ...
    template_codes  TEXT,                                       -- 逗号拼接的适用模板 code (materialization)
    system_prompt   TEXT,                                       -- teacher 的 system role
    input_text      TEXT        NOT NULL,                       -- 喂给 teacher 的 user/结构化 prompt
    teacher_model   VARCHAR(64),                                -- e.g. 'qwen3-max' (INSIGHTS 槽主模型; 极少数 zhipu fallback 见 metadata)
    teacher_output  TEXT        NOT NULL,                       -- teacher 原始响应 (insights 为 JSON 字符串)
    input_hash      VARCHAR(64) NOT NULL,                       -- sha256(input_text) — 防重物化重复 + 导出去重
    quality         SMALLINT,                                   -- nullable; 未来 👍/👎 或自动评分 (curation 用)
    metadata        JSONB
);

-- 防重物化重复: 同一 upload 同数据重物化 → 同 input_hash → 刷新 teacher_output
-- (例如换更强 teacher 重跑), 不堆叠近似重复样本。
CREATE UNIQUE INDEX IF NOT EXISTS uq_distill_input_hash
  ON smart_bi_distillation_samples (input_hash);

-- 导出主查询: 按业态分桶 + 按 source/时间。
CREATE INDEX IF NOT EXISTS idx_distill_bucket
  ON smart_bi_distillation_samples (business_type, source, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_distill_factory
  ON smart_bi_distillation_samples (factory_id);

-- app 用户读写 (表归 postgres)。
GRANT SELECT, INSERT, UPDATE, DELETE ON smart_bi_distillation_samples TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE smart_bi_distillation_samples_id_seq TO smartbi_user;

COMMENT ON TABLE smart_bi_distillation_samples IS
  '垂直模型蒸馏训练语料 sink。source=materialization 存 Fix2 物化时 '
  '(结构化输入 → qwen3-max 富洞察) 对; 按 business_type 分桶。内部 ML 用途, '
  '故意 NO RLS (跨业态训练集构建 + 不经租户端点暴露)。导出: '
  'backend/python/scripts/export_distillation_dataset.py。Added 2026-05-31 '
  '(Steve 蒸馏数据管线; 攒够阈值再 LoRA/蒸馏, 见 memory '
  'project_2026_05_31_vertical_model_strategy_verdict)。';
