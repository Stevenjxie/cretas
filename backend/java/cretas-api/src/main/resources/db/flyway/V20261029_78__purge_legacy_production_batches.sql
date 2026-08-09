-- V20261029_78: 清理 LEGACY 老路遗留的生产数据 (Steve 2026-08-09 拍板)
--
-- V20261029_77 已经堵住"再产生新的 LEGACY", 本迁移清掉存量。
-- prod 实测存量: 17 个批次 + 10 条报工 + 28 条领料消耗 (全部不挂计划)。
--
-- ⛔ 顺序铁律: **先全部备份, 再统一删除**。
--    绝不逐表"备份+删除"交替 —— 父表删除的连带效果会跑在子表备份之前, 子表就备成空表。
--    (2026-08-09 的教训: fact_pos_payment 23.7 万行正是这么备成 0 行的。)
--
-- ⛔ 备份是**整行**副本, 不是只记条数。只记条数的"回滚"是假回滚: 还原不出内容。
--
-- 数据缺席安全: 全部语句在无数据时自然空跑, 不做 "行数 = N" 的严格断言 ——
-- 那种断言在全新空库上必然失败, 会把干净的库直接卡死。

CREATE SCHEMA IF NOT EXISTS legacy_retired;

COMMENT ON SCHEMA legacy_retired IS
  'LEGACY 生产老路下架(2026-08-09)前的整行备份。删除前的原样快照, 仅供追溯。';

-- ---------- 1. 全部备份 (先做完, 一行都不删) ----------

CREATE TABLE IF NOT EXISTS legacy_retired.production_batches_20260809 AS
SELECT * FROM production_batches
 WHERE workflow_selection_mode = 'LEGACY';

CREATE TABLE IF NOT EXISTS legacy_retired.production_reports_20260809 AS
SELECT r.* FROM production_reports r
 WHERE r.batch_id IN (
       SELECT id FROM production_batches WHERE workflow_selection_mode = 'LEGACY');

CREATE TABLE IF NOT EXISTS legacy_retired.material_consumptions_20260809 AS
SELECT m.* FROM material_consumptions m
 WHERE m.production_batch_id IN (
       SELECT id FROM production_batches WHERE workflow_selection_mode = 'LEGACY');

-- ---------- 2. 备份完整性自检: 备份表行数必须 >= 待删行数 ----------
-- 备份表用 IF NOT EXISTS, 重跑时已存在则保持首次快照(行数只会 >= 当前待删数)。

DO $$
DECLARE
  pending_batches      BIGINT;
  pending_reports      BIGINT;
  pending_consumptions BIGINT;
  backed_batches       BIGINT;
  backed_reports       BIGINT;
  backed_consumptions  BIGINT;
BEGIN
  SELECT count(*) INTO pending_batches
    FROM production_batches WHERE workflow_selection_mode = 'LEGACY';
  SELECT count(*) INTO pending_reports
    FROM production_reports
   WHERE batch_id IN (SELECT id FROM production_batches
                       WHERE workflow_selection_mode = 'LEGACY');
  SELECT count(*) INTO pending_consumptions
    FROM material_consumptions
   WHERE production_batch_id IN (SELECT id FROM production_batches
                                  WHERE workflow_selection_mode = 'LEGACY');

  SELECT count(*) INTO backed_batches      FROM legacy_retired.production_batches_20260809;
  SELECT count(*) INTO backed_reports      FROM legacy_retired.production_reports_20260809;
  SELECT count(*) INTO backed_consumptions FROM legacy_retired.material_consumptions_20260809;

  IF backed_batches < pending_batches
     OR backed_reports < pending_reports
     OR backed_consumptions < pending_consumptions THEN
    RAISE EXCEPTION
      'LEGACY 备份不完整, 拒绝删除: batches %/% reports %/% consumptions %/%',
      backed_batches, pending_batches,
      backed_reports, pending_reports,
      backed_consumptions, pending_consumptions;
  END IF;

  RAISE NOTICE 'LEGACY 备份就绪: batches=% reports=% consumptions=%',
    backed_batches, backed_reports, backed_consumptions;
END $$;

-- ---------- 3. 统一删除 (子 → 父) ----------

DELETE FROM production_reports
 WHERE batch_id IN (
       SELECT id FROM production_batches WHERE workflow_selection_mode = 'LEGACY');

DELETE FROM material_consumptions
 WHERE production_batch_id IN (
       SELECT id FROM production_batches WHERE workflow_selection_mode = 'LEGACY');

DELETE FROM production_batches
 WHERE workflow_selection_mode = 'LEGACY';

-- ---------- 4. 收口自检 ----------

DO $$
DECLARE
  remaining BIGINT;
BEGIN
  SELECT count(*) INTO remaining
    FROM production_batches WHERE workflow_selection_mode = 'LEGACY';
  IF remaining <> 0 THEN
    RAISE EXCEPTION 'LEGACY 批次仍剩 % 行, 删除未收口', remaining;
  END IF;
END $$;
