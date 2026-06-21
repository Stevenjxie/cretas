-- 工序成本配置→报工自动继承 的 live 验证链 (独立 demo 产品 DFCFG_pt, 不动 M67 订单/DF_pt10)。
-- 链: product_type → work_process → product_work_process(带成本配置) → production_batch(已开工) → work_process_task。
-- 验证: POST 报工(不传成本字段) → 保存的 production_reports 行应继承 pwp 的 cost_category/packaging_detail/aux_alloc_method。
-- 租户: DEMO_FACTORY (demo, 绝不碰 F006/六膳门真客户)。幂等: 先 FK 安全删自己的行再插。
-- 跑法: PGPASSWORD=*** psql -h 127.0.0.1 -U cretas_user -d cretas_prod_db -v ON_ERROR_STOP=1 -f m67_config_inherit_demo_seed.sql
BEGIN;

-- 幂等清理 (FK 安全顺序: reports → tasks → batches → pwp; product_type/work_process upsert 保留)
DELETE FROM production_reports WHERE factory_id='DEMO_FACTORY'
  AND batch_id IN (SELECT id FROM production_batches WHERE factory_id='DEMO_FACTORY' AND batch_number='DFCFG-PB-001');
DELETE FROM work_process_tasks WHERE factory_id='DEMO_FACTORY' AND product_type_id='DFCFG_pt';
DELETE FROM production_batches WHERE factory_id='DEMO_FACTORY' AND batch_number='DFCFG-PB-001';
DELETE FROM product_work_processes WHERE factory_id='DEMO_FACTORY' AND product_type_id='DFCFG_pt';

-- 1. 独立产品类型
INSERT INTO product_types(id, code, name, unit, factory_id, created_by, is_active, created_at, updated_at)
VALUES ('DFCFG_pt','DFCFG-001','配置继承测试品','kg','DEMO_FACTORY',1635,true,NOW(),NOW())
ON CONFLICT (id) DO NOTHING;

-- 2. 工序目录
INSERT INTO work_processes(id, factory_id, process_name, created_at, updated_at)
VALUES ('DFCFG_WP1','DEMO_FACTORY','熟制(配置测试)',NOW(),NOW())
ON CONFLICT (id) DO NOTHING;

-- 3. 产品工序定义 + 成本配置 (报工将继承)
INSERT INTO product_work_processes(factory_id, product_type_id, work_process_id, process_order, is_active, reporting_required,
       default_cost_category, packaging_template, aux_alloc_method, created_at, updated_at)
VALUES ('DEMO_FACTORY','DFCFG_pt','DFCFG_WP1',1,true,true,
       'SEASONING','[{"name":"膜","cost":30},{"name":"标签","cost":10}]'::jsonb,'BY_OUTPUT',NOW(),NOW());

-- 4. 生产批次 (已开工, 通过 assertBatchStartedForReport)
INSERT INTO production_batches(batch_number, factory_id, product_type_id, quantity, status, unit, created_at, updated_at)
VALUES ('DFCFG-PB-001','DEMO_FACTORY','DFCFG_pt',100,'IN_PROGRESS','kg',NOW(),NOW());

-- 5. 工序任务 (报工经 workProcessTaskId 引用; product_work_process_id 指向带配置的定义)
INSERT INTO work_process_tasks(factory_id, production_batch_id, product_work_process_id, work_process_id, product_type_id,
       process_order, status, assigned_to, created_at, updated_at)
SELECT 'DEMO_FACTORY', b.id, pwp.id, 'DFCFG_WP1','DFCFG_pt',1,'IN_PROGRESS',1635,NOW(),NOW()
FROM production_batches b, product_work_processes pwp
WHERE b.factory_id='DEMO_FACTORY' AND b.batch_number='DFCFG-PB-001'
  AND pwp.factory_id='DEMO_FACTORY' AND pwp.product_type_id='DFCFG_pt' AND pwp.work_process_id='DFCFG_WP1';

COMMIT;

-- 返回 报工 API 所需 id
SELECT t.id AS task_id, t.production_batch_id AS batch_id
FROM work_process_tasks t
WHERE t.factory_id='DEMO_FACTORY' AND t.product_type_id='DFCFG_pt' AND t.work_process_id='DFCFG_WP1';
