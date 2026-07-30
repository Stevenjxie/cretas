-- V20261029_33__widen_enum_check_constraints_drift_sweep.sql
--
-- 「枚举加了值, DB CHECK 白名单没跟上」漂移类的第 6/7/8 次发作。
-- 前几次: V20261027_15 / _29 / _34 / _35 / _37 (见 TransferStatusCheckConstraintTest),
-- 以及 V20260822_04 (ck_sdr_status 上一次被扩)。
--
-- 本次不是逐个撞到再修, 而是拿 prod pg_constraint 全量对账 106 个枚举一次扫出来的:
--   scripts/audit/enum_check_constraint_drift.py  (对活库跑, 见该脚本头注释)
-- 扫描结论: 151 条纯白名单式 CHECK, 95 条对齐, 3 条漂移 —— 就是下面这 3 条。
--
-- ┌── 1) sales_delivery_records.ck_sdr_status ── 活跃 P0, prod 日志已 60 次
-- │
-- │ V20261028_87 (prod 2026-07-20 19:05 applied) 引入母子发运单: 加了 record_role /
-- │ parent_delivery_id / shipment_sequence 列和 chk_sdr_record_role 约束, 但漏了扩
-- │ ck_sdr_status —— 而这个 feature 让 SalesDeliveryStatus 从 6 个值长到 11 个。
-- │
-- │ SalesServiceImpl.createDeliveryRecord: 只要请求带 salesOrderId, status 就置
-- │ PENDING_SPLIT。也就是说「销售订单 → 明细 → 新建发货单」这条正常路径自 7/20 起
-- │ 100% 失败, 客户看到的是 500「数据处理异常」(追踪码 200102BA)。
-- │ 铁证: 修此约束前 prod sales_delivery_records 72 行 record_role 全为 LEGACY,
-- │       MASTER / SHIPMENT 一行都没能落库过。
-- │ 另 4 个值 (PARTIALLY_SCHEDULED / FULLY_SCHEDULED / PARTIALLY_SHIPPED) 由
-- │ recomputeParentDeliveryState 写, CANCELLED 由取消路径写 —— 同样会被旧约束拒绝,
-- │ 所以一并放开, 否则修好建单又会卡在下一步。
-- │
-- ├── 2) attachments.chk_att_entity_type ── 潜伏 (prod 日志 0 次, 该路径还没被走过)
-- │
-- │ Attachment.EntityType 有 24 个值, 约束只列 23 个, 缺 CUSTOMER_SUPPLIED_RECEIPT。
-- │ AttachmentServiceImpl.validateEntityBinding 专门为这个类型做了归属校验后才 save,
-- │ 而 SalesOrderSuppliedMaterialRequirementService 又要求「至少存在一张该类型附件」
-- │ 才放行 —— 约束不放开, 客供料收货凭证永远上传不上去, 那道闸也就永远过不了。
-- │
-- └── 3) internal_transfer_items.ck_iti_type ── 潜伏 (prod 日志 0 次)
--
--   TransferItemType 有 3 个值, 约束只列 2 个, 缺 PACKAGING_MATERIAL。
--   TransferServiceImpl:219 直接 setItemType(TransferItemType.valueOf(dto)) 落库,
--   所以客户一做包材调拨就会撞。该约束**不在 Flyway 里**, 来自未被版本管理的
--   database/p3_transfer_pricelist_pg.sql (与 ck_it_status 同一处出处) —— 本次把它
--   纳入 Flyway, 顺带让静态门禁 (EnumCheckConstraintDriftTest) 能看见它。
--
-- 安全性: 三条都是**纯放宽** (新集合 ⊇ 旧集合), 不动一行数据、不动列定义。
--   现存行用的值全部仍在新白名单内, 所以 ADD CONSTRAINT 的全表校验必然通过
--   (已在 prod 用 BEGIN/…/ROLLBACK 干跑验证, 见 PR 描述)。
--
-- ⚠️ to_regclass 守卫 (沿用 V20260822_04 的做法): 这些表是 Hibernate JPA entity,
--   全新 CI DB 上 Flyway 先于 ddl-auto 跑时表还不存在, 裸 ALTER 会报
--   "relation does not exist" 阻断启动。表存在才改, 不存在则跳过。
-- ⚠️ 一律 DROP CONSTRAINT IF EXISTS 后重建, 保证可重复执行 (含已手工放开过的环境)。

DO $$
BEGIN
    ----------------------------------------------------------------- 1) 发货单
    IF to_regclass('public.sales_delivery_records') IS NOT NULL THEN
        ALTER TABLE sales_delivery_records DROP CONSTRAINT IF EXISTS ck_sdr_status;
        ALTER TABLE sales_delivery_records ADD CONSTRAINT ck_sdr_status
            CHECK (status::text = ANY (ARRAY[
                'DRAFT'::text,
                'PENDING_WAREHOUSE_CONFIRM'::text,
                'PICKED'::text,
                'PENDING_SPLIT'::text,
                'PARTIALLY_SCHEDULED'::text,
                'FULLY_SCHEDULED'::text,
                'PARTIALLY_SHIPPED'::text,
                'SHIPPED'::text,
                'DELIVERED'::text,
                'CANCELLED'::text,
                'RETURNED'::text
            ]));
        COMMENT ON CONSTRAINT ck_sdr_status ON sales_delivery_records
            IS '与 SalesDeliveryStatus 全量对齐 (V20261028_87 母子发运单漂移修复, 追踪码 200102BA)。改枚举必须同步改这里, EnumCheckConstraintDriftTest 会挡。';
    END IF;

    ------------------------------------------------------------------- 2) 附件
    IF to_regclass('public.attachments') IS NOT NULL THEN
        ALTER TABLE attachments DROP CONSTRAINT IF EXISTS chk_att_entity_type;
        ALTER TABLE attachments ADD CONSTRAINT chk_att_entity_type
            CHECK (entity_type::text = ANY (ARRAY[
                'CUSTOMER'::text,
                'CUSTOMER_TRACKING'::text,
                'PURCHASE_ORDER'::text,
                'PURCHASE_RECEIPT'::text,
                'CUSTOMER_SUPPLIED_RECEIPT'::text,
                'QUALITY_CHECK'::text,
                'PRODUCTION_BATCH'::text,
                'PAYMENT_VOUCHER'::text,
                'INVOICE'::text,
                'RD_SAMPLE'::text,
                'RECEIPT'::text,
                'RETURN_ORDER'::text,
                'SHIPMENT'::text,
                'WASTAGE_RECORD'::text,
                'GROUP_LEADER_REPORT'::text,
                'EXPENSE_REPORT'::text,
                'LEAVE_REQUEST'::text,
                'TIMECLOCK_PHOTO'::text,
                'SALES_ORDER'::text,
                'INVENTORY'::text,
                'ECN'::text,
                'CALL_RECORD'::text,
                'PRODUCTION_REPORT'::text,
                'GENERIC'::text
            ]));
        COMMENT ON CONSTRAINT chk_att_entity_type ON attachments
            IS '与 Attachment.EntityType 全量对齐 (补 CUSTOMER_SUPPLIED_RECEIPT)。改枚举必须同步改这里, EnumCheckConstraintDriftTest 会挡。';
    END IF;

    --------------------------------------------------------------- 3) 调拨明细
    IF to_regclass('public.internal_transfer_items') IS NOT NULL THEN
        ALTER TABLE internal_transfer_items DROP CONSTRAINT IF EXISTS ck_iti_type;
        ALTER TABLE internal_transfer_items ADD CONSTRAINT ck_iti_type
            CHECK (item_type::text = ANY (ARRAY[
                'RAW_MATERIAL'::text,
                'FINISHED_GOODS'::text,
                'PACKAGING_MATERIAL'::text
            ]));
        COMMENT ON CONSTRAINT ck_iti_type ON internal_transfer_items
            IS '与 TransferItemType 全量对齐 (补 PACKAGING_MATERIAL)。原出处 database/p3_transfer_pricelist_pg.sql 不受版本管理, 本次纳入 Flyway。';
    END IF;
END $$;
