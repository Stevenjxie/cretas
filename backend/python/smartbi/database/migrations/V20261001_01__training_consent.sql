-- 训练授权基座 — 数据主权技术落地 (training-consent foundation)
--
-- 目的: 让"正向数据飞轮"的数据主权承诺成为**技术强制**, 而非仅 pitch 口号。
--
-- 承诺 (pitch s4d + Steve):
--   1. 客户真实数据**默认不直接用于训练** (无授权行 = 'none' = 排除出共享训练集)。
--   2. 需客户**明确授权**后才进入训练路径:
--        - 'private'      → 单租户私有模型: 该工厂数据**原始**直接用 (不脱敏),
--                           仅用于该工厂自己的私有模型, 不进跨租户共享集。
--        - 'shared_anon'  → 跨租户共享模型: 数据先经**脱敏** (数值分桶 +
--                           实体伪名化 + 按业态桶) 再进共享训练集。
--   3. 合成语料 (SEED_R / SEED_F 哨兵, metadata.source_kind='synthetic_seed')
--        **无条件可用** — 不属于任何真实租户, 不受本表门控。
--
-- 强制点: backend/python/scripts/export_distillation_dataset.py 导出语料时
--   按本表 consent_scope 门控 (default-deny: 无行/'none' → 排除真实行)。
--
-- ⚠️ 默认拒绝 (default-deny): 一个工厂**没有行** = 视同 'none' = 真实数据
--   NOT 进共享训练集。授权是显式的 opt-in, 不是 opt-out。
--
-- 迁移 runner 以 postgres 超级用户跑 (apply-smartbi-migrations.sh sudo -u postgres),
-- 表归 postgres → 必须 GRANT 给 app 用户 smartbi_user。

CREATE TABLE IF NOT EXISTS training_consent (
    factory_id      VARCHAR(50)  PRIMARY KEY,                    -- 租户 (工厂/餐饮) id
    consent_scope   VARCHAR(16)  NOT NULL DEFAULT 'none'
                    CHECK (consent_scope IN ('none', 'private', 'shared_anon')),
    -- 'none'        = 默认; 真实数据不进任何训练集 (default-deny)
    -- 'private'     = 授权单租户私有模型: 原始数据, 仅自己的私有模型
    -- 'shared_anon' = 授权跨租户共享模型: 脱敏后进共享训练集
    authorized_at   TIMESTAMP,                                   -- 授权时间
    authorized_by   VARCHAR(64),                                 -- 授权操作人 (审计追溯)
    note            TEXT,                                        -- 授权备注 / 合同引用等
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- app 用户读写 (表归 postgres)。
GRANT SELECT, INSERT, UPDATE, DELETE ON training_consent TO smartbi_user;

COMMENT ON TABLE training_consent IS
  '训练授权基座 — 数据主权技术强制。consent_scope: none(默认,真实数据不进训练) '
  '| private(单租户私有模型,原始数据) | shared_anon(跨租户共享模型,脱敏后). '
  '无行=none=default-deny. 合成语料(SEED_R/SEED_F)不受本表门控,无条件可用. '
  '强制点: export_distillation_dataset.py 导出时按本表门控. Added 2026-06-11 '
  '(Steve 正向数据飞轮 数据主权技术落地)。';
