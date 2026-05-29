-- Sprint 12 #263 完整 close — soft-delete 5 RESTAURANT_* 业态错配 indicators on F006
--
-- 背景: unified-verify chat (2026-05-29 zero-context subagent + SSH 实测) 发现 #263 premature close.
-- #263 标题 "delete V_23_11 mirror + RESTAURANT_* dup". Phase A (V20260825_01) 只删了 7 个
-- V_23_11 mirror codes (AVG_TICKET_PRICE/TABLE_TURNOVER/...), 漏删 5 个 RESTAURANT_*-前缀的
-- 业态错配 indicator (V20260821_03 初始 seed 落的, 把卤味厂 F006 当餐厅).
--
-- prod 实测 (cretas_prod_db 2026-05-29): 5 个 RESTAURANT_* is_active=t 仍显示, 其中
-- RESTAURANT_WASTAGE_RATE last_value=0.0000 (伪造). 这是 Sprint 11 audit 核心吐槽 (卤味厂显示
-- 翻台率/客单价/菜品毛利). F006 = 六膳门食品科技 (FACTORY 业态), 不该有餐厅指标.
--
-- 卤味业态真指标已由 Phase C FACTORY_LU_* (V20260825_06) 提供, 这 5 个 RESTAURANT_* 是残留.
--
-- Soft-delete (deleted_at + is_active=false): Indicator entity @Where(deleted_at IS NULL) 自动
-- 过滤 → dashboard / list / query 都不再显示. 可逆 + 保留历史 (indicator_versions append-only 不动).
--
-- Idempotent: WHERE deleted_at IS NULL 保证重跑 no-op.

UPDATE indicators
   SET deleted_at = NOW(),
       is_active = false,
       updated_at = NOW()
 WHERE factory_id = 'F006'
   AND code LIKE 'RESTAURANT_%'
   AND deleted_at IS NULL;

-- Verify
DO $$
DECLARE
    remaining_active INT;
BEGIN
    SELECT count(*) INTO remaining_active
      FROM indicators
     WHERE factory_id = 'F006'
       AND code LIKE 'RESTAURANT_%'
       AND deleted_at IS NULL;

    RAISE NOTICE 'Sprint 12 #263: F006 RESTAURANT_* 业态错配 indicators 残留 (active): %/0', remaining_active;

    IF remaining_active > 0 THEN
        RAISE EXCEPTION 'Sprint 12 #263 FAIL: expected 0 active RESTAURANT_* on F006, got %', remaining_active;
    END IF;
END $$;
