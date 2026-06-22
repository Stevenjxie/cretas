-- M67 卤牛肉 demo 数据 (喂现成 OrderYieldController.getOrderYieldSummary)
-- 租户: DEMO_FACTORY (demo, 绝不碰 F006/六膳门 真客户)
-- 链路: 修油→滚揉→焯水→熟制→气调→包装, 全 kg (出成率可比); 盒数(1787)在 batch.quantity
-- 数据复刻客户 M67 v5.0 6-16 批真实出成率: 修油90.7/滚揉119.9(注水)/焯水72.8/熟制74/气调99.4 → 整批58.2%
-- 幂等: 按 marker 'M67DEMO-' 删除自己的行再插入。FK 真实: created_by/worker_id=1635, customer=DF_c1, product_type=DF_pt10
-- 跑法: PGPASSWORD=cretas123 psql -h 127.0.0.1 -U cretas_user -d cretas_prod_db -v ON_ERROR_STOP=1 -f m67_demo_seed.sql
BEGIN;
-- 幂等清理 (FK 安全顺序: 消耗→关联→报工→批次→物料批→计划→订单)
DELETE FROM material_consumptions WHERE factory_id='DEMO_FACTORY' AND (batch_id LIKE 'M67DEMO-MB-%' OR production_batch_id IN (SELECT id FROM production_batches WHERE factory_id='DEMO_FACTORY' AND batch_number LIKE 'M67DEMO-%'));
DELETE FROM batch_relations WHERE id LIKE 'M67DEMO-BR-%';
DELETE FROM production_reports WHERE factory_id='DEMO_FACTORY' AND batch_id IN (SELECT id FROM production_batches WHERE factory_id='DEMO_FACTORY' AND batch_number LIKE 'M67DEMO-%');
DELETE FROM production_batches WHERE factory_id='DEMO_FACTORY' AND batch_number LIKE 'M67DEMO-%';
DELETE FROM material_batches WHERE id LIKE 'M67DEMO-MB-%';
DELETE FROM production_plans WHERE factory_id='DEMO_FACTORY' AND plan_number LIKE 'M67DEMO-%';
DELETE FROM sales_orders WHERE factory_id='DEMO_FACTORY' AND order_number LIKE 'M67DEMO-%';

INSERT INTO sales_orders(id,factory_id,order_number,customer_id,order_date,total_amount,status,created_by,created_at,updated_at,vflag,version)
VALUES('SO-M67DEMO-001','DEMO_FACTORY','M67DEMO-SO-001','DF_c1','2026-06-16',15000,'COMPLETED',1635,NOW(),NOW(),'CREATED',0);

INSERT INTO production_plans(id,factory_id,plan_number,product_type_id,planned_quantity,status,created_by,source_order_id,created_at,updated_at)
VALUES('PP-M67DEMO-001','DEMO_FACTORY','M67DEMO-PP-001','DF_pt10',1787,'COMPLETED',1635,'SO-M67DEMO-001',NOW(),NOW());

WITH b AS (
  INSERT INTO production_batches(factory_id,batch_number,product_type_id,production_plan_id,quantity,unit,status,created_at,updated_at)
  VALUES('DEMO_FACTORY','M67DEMO-PB-001','DF_pt10','PP-M67DEMO-001',1787,'盒','COMPLETED',NOW(),NOW())
  RETURNING id
)
INSERT INTO production_reports(factory_id,batch_id,worker_id,report_type,report_date,report_mode,process_order,work_process_task_id,process_category,product_name,input_quantity,input_unit,output_quantity,output_unit,labor_cost,material_cost,total_work_minutes,total_workers,byproducts,sample_retain_quantity,waste_quantity,cost_category,packaging_detail,aux_pot_no,aux_pot_total_cost,aux_alloc_method,created_at,updated_at,version)
SELECT 'DEMO_FACTORY', b.id, 1635,'YIELD','2026-06-16','MODE_1', v.po, v.wpt, v.cat,'M67卤牛肉', v.inq,'kg', v.outq,'kg', v.lc, v.mc, v.wm, v.tw, v.bp::jsonb, v.sr, v.wq, v.cc, v.pd::jsonb, v.apn, v.aptc, v.aam, NOW(),NOW(),0
FROM b,(VALUES
  (1,101::bigint,'修油',307.0,278.5,624.0,0.0,1440,8, '[{"name":"肥油","quantity":36,"unit":"kg","unitPrice":8}]', NULL::int, NULL::numeric, 'RAW_MATERIAL', NULL, NULL, NULL::numeric, NULL),  -- v5.0 实测: 出库307→产出278.5(出成率90.72%)+肥油36kg(客户肥油出成率口径; 单价¥8为占位,客户无肥油单价); 人工24h×26=¥624
  (2,102::bigint,'滚揉',278.5,334.0,143.0,0.0,330,2, NULL, NULL::int, NULL::numeric, NULL, NULL, NULL, NULL::numeric, NULL),         -- 注水增重 119.9% (无材料成本)
  (3,103::bigint,'焯水',334.0,243.0,39.0,0.0,90,1, NULL, NULL::int, NULL::numeric, NULL, NULL, NULL, NULL::numeric, NULL),
  (4,104::bigint,'熟制',243.0,179.8,39.0,980.0,90,1, NULL, NULL::int, NULL::numeric, 'SEASONING', NULL, 'POT-M67DEMO-01', 980.0::numeric, 'BY_OUTPUT'),         -- CALC-003 显式调料(卤汤); AUDIT-004 共享锅(本批为唯一成员→分摊100%=¥980; 多批时按产出量分摊)
  (5,105::bigint,'气调',179.8,179.8,359.0,0.0,828,3, NULL, NULL::int, NULL::numeric, NULL, NULL, NULL, NULL::numeric, NULL),         -- v5.0: 气调出成率=1 (无损耗); 产出=投入179.8
  (6,106::bigint,'包装',179.8,179.8,130.0,880.0,300,4, NULL, 3::int, NULL::numeric, 'PACKAGING', '[{"name":"膜","cost":300},{"name":"气体","cost":180},{"name":"标签","cost":120},{"name":"其他","cost":280}]', NULL, NULL::numeric, NULL)        -- CALC-003 显式包装; AUDIT-002 包装明细; v5.0 留样3盒; 包装不改重→末道产出179.8 → 总出成率179.8/307=58.57% 与 v5.0 一致
) AS v(po,wpt,cat,inq,outq,lc,mc,wm,tw,bp,sr,wq,cc,pd,apn,aptc,aam);

-- 多批混锅溯源边 (batch_relations): 本批(熟制) 来自 2 个上游焯水批次 → 喂现成 /batch-relations/trace/backward
-- 前端「多批混锅溯源」桑基图数据驱动来源。production_batch_id 按 batch_number 解析(可复现)。
INSERT INTO batch_relations(id,factory_id,material_batch_id,production_batch_id,relation_type,quantity_used,unit,stage,used_at,created_at,updated_at)
SELECT 'M67DEMO-BR-001','DEMO_FACTORY','焯水0613 (原料A链)',pb.id,'SEMI_FINISHED',78,'kg','熟制',NOW(),NOW(),NOW()
FROM production_batches pb WHERE pb.factory_id='DEMO_FACTORY' AND pb.batch_number='M67DEMO-PB-001'
UNION ALL
SELECT 'M67DEMO-BR-002','DEMO_FACTORY','焯水0614 (原料B链)',pb.id,'SEMI_FINISHED',22,'kg','熟制',NOW(),NOW(),NOW()
FROM production_batches pb WHERE pb.factory_id='DEMO_FACTORY' AND pb.batch_number='M67DEMO-PB-001';

-- 混批成本闭环: 2 个上游来源批次 + 消耗记录(异质累计单价) → 喂现成 /processing/material-consumptions/batch/{id}
-- 前端「混批成本拆分」+ 单盒成本原料口径数据驱动来源。total_cost=该链累计成本(原料+加工至焯水)。
INSERT INTO material_batches(id,factory_id,batch_number,material_type_id,warehouse_id,created_by,quantity_unit,inbound_date,receipt_quantity,reserved_quantity,used_quantity,status,unit_price,created_at,updated_at)
VALUES
 ('M67DEMO-MB-0613','DEMO_FACTORY','焯水0613 (原料A链)','DF_rmt1','DF_fw1',1635,'kg','2026-06-13',78,0,78,'DEPLETED',91.92,NOW(),NOW()),
 ('M67DEMO-MB-0614','DEMO_FACTORY','焯水0614 (原料B链)','DF_rmt1','DF_fw1',1635,'kg','2026-06-14',22,0,22,'DEPLETED',170.00,NOW(),NOW()),
 ('M67DEMO-MB-RAWA','DEMO_FACTORY','原料A 生牛腩','DF_rmt1','DF_fw1',1635,'kg','2026-06-12',100,0,100,'DEPLETED',71.70,NOW(),NOW());
INSERT INTO material_consumptions(factory_id,production_batch_id,batch_id,material_type_id,quantity,unit_price,total_cost,source_type,consumption_time,consumed_at,recorded_by,created_at,updated_at)
SELECT 'DEMO_FACTORY',pb.id,'M67DEMO-MB-0613','DF_rmt1',78,91.92,7170,'SEMI_FINISHED',NOW(),NOW(),1635,NOW(),NOW() FROM production_batches pb WHERE pb.factory_id='DEMO_FACTORY' AND pb.batch_number='M67DEMO-PB-001'
UNION ALL
SELECT 'DEMO_FACTORY',pb.id,'M67DEMO-MB-0614','DF_rmt1',22,170.00,3740,'SEMI_FINISHED',NOW(),NOW(),1635,NOW(),NOW() FROM production_batches pb WHERE pb.factory_id='DEMO_FACTORY' AND pb.batch_number='M67DEMO-PB-001';

-- 多级递归 demo (depth=2): 焯水0613 半成品 ← 其生产批次(WIP, 无 plan 不进订单聚合) ← 原料A 生牛腩。
-- 触发 OrderCostBreakdownService 的 T1 递归回溯到原料层 (sources[焯水0613].depth=2)。
WITH ub AS (
  INSERT INTO production_batches(factory_id,batch_number,product_type_id,quantity,unit,status,created_at,updated_at)
  VALUES('DEMO_FACTORY','M67DEMO-PB-CS0613','DF_pt10',78,'kg','COMPLETED',NOW(),NOW()) RETURNING id
)
INSERT INTO material_consumptions(factory_id,production_batch_id,batch_id,material_type_id,quantity,unit_price,total_cost,source_type,consumption_time,consumed_at,recorded_by,created_at,updated_at)
SELECT 'DEMO_FACTORY', ub.id,'M67DEMO-MB-RAWA','DF_rmt1',100,71.70,7170,'RAW_MATERIAL',NOW(),NOW(),1635,NOW(),NOW() FROM ub;
UPDATE material_batches SET source_doc_type='PRODUCTION_BATCH',
  source_doc_id=(SELECT id::text FROM production_batches WHERE factory_id='DEMO_FACTORY' AND batch_number='M67DEMO-PB-CS0613')
WHERE id='M67DEMO-MB-0613';
COMMIT;
