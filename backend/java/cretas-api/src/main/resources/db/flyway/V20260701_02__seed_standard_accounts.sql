-- Sprint 7 T1 F-VOUCHER-2 Phase B: 中国 GAAP 标准科目 seed.
-- factory_id NULL = 系统级跨 factory 共享. 30+ 一级科目 + 关键二级.
-- 来源: 财政部 企业会计准则 (2006) 一级科目表 (核心子集).
-- 幂等: ON CONFLICT DO NOTHING (科目 code 系统级 unique, 已 seed 则跳过).
--
-- Code 规则 (中国 GAAP):
--   1xxx 资产类 (ASSET, DEBIT_NORMAL)
--   2xxx 负债类 (LIABILITY, CREDIT_NORMAL)
--   3xxx 共同类 (skip — 少用)
--   4xxx 所有者权益类 (EQUITY, CREDIT_NORMAL)
--   5xxx 成本类 (COST, DEBIT_NORMAL)
--   6xxx 损益类 (REVENUE for 6001-6099, EXPENSE for 6601-6711)

-- ==================== 1xxx 资产类 ====================

INSERT INTO accounts (id, factory_id, code, name, parent_id, level, category, balance_type, description, active, sort_order)
VALUES
    ('sys-acc-1001', NULL, '1001', '库存现金',     NULL, 1, 'ASSET', 'DEBIT_NORMAL', '出纳保管的现金', TRUE, 10),
    ('sys-acc-1002', NULL, '1002', '银行存款',     NULL, 1, 'ASSET', 'DEBIT_NORMAL', '存入银行的款项', TRUE, 20),
    ('sys-acc-1012', NULL, '1012', '其他货币资金', NULL, 1, 'ASSET', 'DEBIT_NORMAL', '外埠存款 / 银行汇票 / 信用证保证金', TRUE, 30),
    ('sys-acc-1101', NULL, '1101', '交易性金融资产', NULL, 1, 'ASSET', 'DEBIT_NORMAL', '为交易目的持有的金融资产', TRUE, 40),
    ('sys-acc-1121', NULL, '1121', '应收票据',     NULL, 1, 'ASSET', 'DEBIT_NORMAL', '商业汇票 (银行/商业承兑)', TRUE, 50),
    ('sys-acc-1122', NULL, '1122', '应收账款',     NULL, 1, 'ASSET', 'DEBIT_NORMAL', '销售商品/服务应收客户款 (按客户辅助核算)', TRUE, 60),
    ('sys-acc-1123', NULL, '1123', '预付账款',     NULL, 1, 'ASSET', 'DEBIT_NORMAL', '预付给供应商的款项', TRUE, 70),
    ('sys-acc-1131', NULL, '1131', '应收股利',     NULL, 1, 'ASSET', 'DEBIT_NORMAL', '应向被投资单位收取的现金股利', TRUE, 80),
    ('sys-acc-1132', NULL, '1132', '应收利息',     NULL, 1, 'ASSET', 'DEBIT_NORMAL', '应收存款/债券/借款利息', TRUE, 90),
    ('sys-acc-1221', NULL, '1221', '其他应收款',   NULL, 1, 'ASSET', 'DEBIT_NORMAL', '员工备用金 / 其他暂收暂付款', TRUE, 100),
    ('sys-acc-1231', NULL, '1231', '坏账准备',     NULL, 1, 'ASSET', 'CREDIT_NORMAL', '应收账款减值 (备抵科目, 贷方余额)', TRUE, 110),
    ('sys-acc-1401', NULL, '1401', '材料采购',     NULL, 1, 'ASSET', 'DEBIT_NORMAL', '采购在途材料', TRUE, 120),
    ('sys-acc-1402', NULL, '1402', '在途物资',     NULL, 1, 'ASSET', 'DEBIT_NORMAL', '已付款未到货物资', TRUE, 130),
    ('sys-acc-1403', NULL, '1403', '原材料',       NULL, 1, 'ASSET', 'DEBIT_NORMAL', '入库的原材料库存', TRUE, 140),
    ('sys-acc-1404', NULL, '1404', '材料成本差异', NULL, 1, 'ASSET', 'DEBIT_NORMAL', '计划成本 vs 实际成本差额', TRUE, 150),
    ('sys-acc-1405', NULL, '1405', '库存商品',     NULL, 1, 'ASSET', 'DEBIT_NORMAL', '完工入库的产成品', TRUE, 160),
    ('sys-acc-1406', NULL, '1406', '发出商品',     NULL, 1, 'ASSET', 'DEBIT_NORMAL', '发出未确认收入的商品', TRUE, 170),
    ('sys-acc-1411', NULL, '1411', '周转材料',     NULL, 1, 'ASSET', 'DEBIT_NORMAL', '低值易耗品 / 包装物', TRUE, 180),
    ('sys-acc-1471', NULL, '1471', '存货跌价准备', NULL, 1, 'ASSET', 'CREDIT_NORMAL', '存货减值 (备抵科目, 贷方余额)', TRUE, 190),
    ('sys-acc-1601', NULL, '1601', '固定资产',     NULL, 1, 'ASSET', 'DEBIT_NORMAL', '土地 / 房屋 / 设备 / 车辆', TRUE, 200),
    ('sys-acc-1602', NULL, '1602', '累计折旧',     NULL, 1, 'ASSET', 'CREDIT_NORMAL', '固定资产累计折旧 (备抵, 贷方余额)', TRUE, 210),
    ('sys-acc-1603', NULL, '1603', '固定资产减值准备', NULL, 1, 'ASSET', 'CREDIT_NORMAL', '固定资产减值 (备抵)', TRUE, 220),
    ('sys-acc-1604', NULL, '1604', '在建工程',     NULL, 1, 'ASSET', 'DEBIT_NORMAL', '在建工程支出 (建造/安装/试运行)', TRUE, 230),
    ('sys-acc-1701', NULL, '1701', '无形资产',     NULL, 1, 'ASSET', 'DEBIT_NORMAL', '专利权 / 商标权 / 软件', TRUE, 240),
    ('sys-acc-1702', NULL, '1702', '累计摊销',     NULL, 1, 'ASSET', 'CREDIT_NORMAL', '无形资产累计摊销 (备抵)', TRUE, 250),
    ('sys-acc-1801', NULL, '1801', '长期待摊费用', NULL, 1, 'ASSET', 'DEBIT_NORMAL', '摊销期超过 1 年的费用', TRUE, 260)
ON CONFLICT DO NOTHING;

-- ==================== 2xxx 负债类 ====================

INSERT INTO accounts (id, factory_id, code, name, parent_id, level, category, balance_type, description, active, sort_order)
VALUES
    ('sys-acc-2001', NULL, '2001', '短期借款',     NULL, 1, 'LIABILITY', 'CREDIT_NORMAL', '1 年内 (含 1 年) 到期的借款', TRUE, 300),
    ('sys-acc-2201', NULL, '2201', '应付票据',     NULL, 1, 'LIABILITY', 'CREDIT_NORMAL', '签发的商业汇票', TRUE, 310),
    ('sys-acc-2202', NULL, '2202', '应付账款',     NULL, 1, 'LIABILITY', 'CREDIT_NORMAL', '采购商品/服务应付供应商款 (按供应商辅助核算)', TRUE, 320),
    ('sys-acc-2203', NULL, '2203', '预收账款',     NULL, 1, 'LIABILITY', 'CREDIT_NORMAL', '预收客户的款项 (合同负债)', TRUE, 330),
    ('sys-acc-2211', NULL, '2211', '应付职工薪酬', NULL, 1, 'LIABILITY', 'CREDIT_NORMAL', '应付工资 / 奖金 / 社保 / 福利 (按员工辅助核算)', TRUE, 340),
    ('sys-acc-2221', NULL, '2221', '应交税费',     NULL, 1, 'LIABILITY', 'CREDIT_NORMAL', '增值税 / 企业所得税 / 个税 等', TRUE, 350),
    ('sys-acc-2231', NULL, '2231', '应付利息',     NULL, 1, 'LIABILITY', 'CREDIT_NORMAL', '应付未付的借款利息', TRUE, 360),
    ('sys-acc-2232', NULL, '2232', '应付股利',     NULL, 1, 'LIABILITY', 'CREDIT_NORMAL', '已宣告未支付的现金股利', TRUE, 370),
    ('sys-acc-2241', NULL, '2241', '其他应付款',   NULL, 1, 'LIABILITY', 'CREDIT_NORMAL', '其他暂付款 / 押金 / 保证金', TRUE, 380),
    ('sys-acc-2401', NULL, '2401', '预提费用',     NULL, 1, 'LIABILITY', 'CREDIT_NORMAL', '已发生未支付的费用 (新准则下并入应付)', TRUE, 390),
    ('sys-acc-2501', NULL, '2501', '长期借款',     NULL, 1, 'LIABILITY', 'CREDIT_NORMAL', '偿还期超过 1 年的借款', TRUE, 400),
    ('sys-acc-2701', NULL, '2701', '长期应付款',   NULL, 1, 'LIABILITY', 'CREDIT_NORMAL', '租赁负债 / 应付融资款', TRUE, 410)
ON CONFLICT DO NOTHING;

-- ==================== 4xxx 所有者权益类 ====================

INSERT INTO accounts (id, factory_id, code, name, parent_id, level, category, balance_type, description, active, sort_order)
VALUES
    ('sys-acc-4001', NULL, '4001', '实收资本',     NULL, 1, 'EQUITY', 'CREDIT_NORMAL', '股东投入资本 / 注册资本', TRUE, 500),
    ('sys-acc-4002', NULL, '4002', '资本公积',     NULL, 1, 'EQUITY', 'CREDIT_NORMAL', '资本溢价 / 其他资本公积', TRUE, 510),
    ('sys-acc-4101', NULL, '4101', '盈余公积',     NULL, 1, 'EQUITY', 'CREDIT_NORMAL', '法定盈余公积 / 任意盈余公积', TRUE, 520),
    ('sys-acc-4103', NULL, '4103', '本年利润',     NULL, 1, 'EQUITY', 'CREDIT_NORMAL', '当期净利润累计 (年末转入未分配利润)', TRUE, 530),
    ('sys-acc-4104', NULL, '4104', '利润分配',     NULL, 1, 'EQUITY', 'CREDIT_NORMAL', '未分配利润 / 已分配股利', TRUE, 540)
ON CONFLICT DO NOTHING;

-- ==================== 5xxx 成本类 ====================

INSERT INTO accounts (id, factory_id, code, name, parent_id, level, category, balance_type, description, active, sort_order)
VALUES
    ('sys-acc-5001', NULL, '5001', '生产成本',     NULL, 1, 'COST', 'DEBIT_NORMAL', '产品生产过程发生的直接/间接成本', TRUE, 600),
    ('sys-acc-5101', NULL, '5101', '制造费用',     NULL, 1, 'COST', 'DEBIT_NORMAL', '车间一般性费用 (折旧/水电/管理人员工资)', TRUE, 610),
    ('sys-acc-5201', NULL, '5201', '劳务成本',     NULL, 1, 'COST', 'DEBIT_NORMAL', '提供劳务发生的成本', TRUE, 620),
    ('sys-acc-5301', NULL, '5301', '研发支出',     NULL, 1, 'COST', 'DEBIT_NORMAL', '内部研发项目支出 (按项目辅助核算)', TRUE, 630)
ON CONFLICT DO NOTHING;

-- ==================== 6xxx 收入类 (REVENUE) ====================

INSERT INTO accounts (id, factory_id, code, name, parent_id, level, category, balance_type, description, active, sort_order)
VALUES
    ('sys-acc-6001', NULL, '6001', '主营业务收入', NULL, 1, 'REVENUE', 'CREDIT_NORMAL', '销售商品/提供主营业务服务的收入', TRUE, 700),
    ('sys-acc-6051', NULL, '6051', '其他业务收入', NULL, 1, 'REVENUE', 'CREDIT_NORMAL', '主营业务以外的小额持续收入 (出租 / 包装物销售)', TRUE, 710),
    ('sys-acc-6101', NULL, '6101', '投资收益',     NULL, 1, 'REVENUE', 'CREDIT_NORMAL', '股权投资 / 债权投资收益', TRUE, 720),
    ('sys-acc-6301', NULL, '6301', '营业外收入',   NULL, 1, 'REVENUE', 'CREDIT_NORMAL', '非经营性收入 (政府补助 / 资产处置利得)', TRUE, 730)
ON CONFLICT DO NOTHING;

-- ==================== 6xxx 费用类 (EXPENSE) ====================

INSERT INTO accounts (id, factory_id, code, name, parent_id, level, category, balance_type, description, active, sort_order)
VALUES
    ('sys-acc-6401', NULL, '6401', '主营业务成本', NULL, 1, 'EXPENSE', 'DEBIT_NORMAL', '已确认主营业务收入对应的成本', TRUE, 800),
    ('sys-acc-6402', NULL, '6402', '其他业务成本', NULL, 1, 'EXPENSE', 'DEBIT_NORMAL', '其他业务收入对应的成本', TRUE, 810),
    ('sys-acc-6403', NULL, '6403', '营业税金及附加', NULL, 1, 'EXPENSE', 'DEBIT_NORMAL', '消费税 / 城建税 / 教育费附加 等', TRUE, 820),
    ('sys-acc-6601', NULL, '6601', '销售费用',     NULL, 1, 'EXPENSE', 'DEBIT_NORMAL', '销售部门发生的费用 (差旅/广告/销售人员工资)', TRUE, 830),
    ('sys-acc-6602', NULL, '6602', '管理费用',     NULL, 1, 'EXPENSE', 'DEBIT_NORMAL', '管理部门发生的费用 (行政人员工资/办公/折旧)', TRUE, 840),
    ('sys-acc-6603', NULL, '6603', '财务费用',     NULL, 1, 'EXPENSE', 'DEBIT_NORMAL', '利息支出 / 汇兑损益 / 手续费', TRUE, 850),
    ('sys-acc-6701', NULL, '6701', '资产减值损失', NULL, 1, 'EXPENSE', 'DEBIT_NORMAL', '存货跌价 / 坏账 / 固定资产减值', TRUE, 860),
    ('sys-acc-6711', NULL, '6711', '营业外支出',   NULL, 1, 'EXPENSE', 'DEBIT_NORMAL', '非经营性支出 (公益捐赠 / 罚款 / 资产处置损失)', TRUE, 870),
    ('sys-acc-6801', NULL, '6801', '所得税费用',   NULL, 1, 'EXPENSE', 'DEBIT_NORMAL', '当期应交企业所得税', TRUE, 880)
ON CONFLICT DO NOTHING;
