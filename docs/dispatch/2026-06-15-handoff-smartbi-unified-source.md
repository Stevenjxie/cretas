# Handoff — SmartBI 全局 Python 收口 + 统一源(新 organizer chat 接手)

> 自包含。新 chat `/organizer` 接手读这一份即可。前序极长 session 已 SHIPPED 两刀到 prod,
> 此文交接**剩余工作 + 用 superpowers 规划 + 分 Codex subagent 执行**。
> 关联: 北极星 spec **PR #867** / memory `project_2026_06_14_smartbi_python_consolidation_northstar`。

## ✅ 已 SHIPPED 到 prod(完整闭环,勿重做)

| 刀 | 内容 | PR | prod 验证 |
|---|---|---|---|
| 收口第一刀 | Java 死代码清理 + **P0 假数据驾驶舱修复**(enrichUnifiedDashboard 进程内伪随机 mock 给客户看假生产/质量数 → 删+删2 mock服务~2400行+死端点) | #877 | green:10020 jar核对 mock类0/0 + 健康UP |
| Codex#1 真 gold | 工厂生产/质量分析读真实 `agg_factory_batch_daily`(替 Phase 2D 占位) | #873 | Python 8083 |
| Phase 0 表/RLS | `smart_bi_pin_mappings`+`smart_bi_mapping_review_queue`(FORCE RLS)+ `consult_promoted_db` DB实时(**无自动晋升**) | #879 | 两库迁移V20261004_02 + force_rls=t |
| Phase 0 mapper | SemanticMapper **Layer 0 确定性 pin**(批量查+GUC事务内+fail-open)+ 受控字典 `domain_standard_fields.py` 单源全域 + LLM出白名单→`review_needed`复核队列 + `domain_inference` 域反推 + excel_async 管线接入(修table_context=None bug)+ 复核API `mapping_review.py` | #885 | test先行8084→prod8083, import smoke全router, 32测试绿 |
| **GRANT 修复** | pin/review 表 GRANT 给 smartbi_user(**live验抓到 permission denied**,fail-open 救了 prod) | (V20261004_03) | 热修 prod+test 已验 smartbi_user 可访问 |

**Phase 0「映射钉死」= 统一源 keystone,已 live 且 functional。** 空 pin 表=fail-open=现有行为;复核队列开始收待标注列。

## ⚠️ 未做的验证(新 chat 第一件事)
- **Phase 0 upload e2e**: 真上传一个 Excel(test 8084),验:列映射用受控vocab / 映不出→review_queue / 域反推 / 同模板重传→pin 命中。用 `smartbi-test-data` + `smartbi-test-e2e` skill。**"单测过≠live好"——GRANT bug 就是 live 验才抓到的。**

## 🔧 剩余工作(organizer 分诊 + Codex subagent)

1. **【验证, Codex/skill】** Phase 0 upload e2e(↑)。先做,确认地基真 work 再往上建。
2. **【UI, Codex/Composer】** 复核队列 **web-admin UI**:操作员看 `GET /api/mobile/{fid}/smart-bi/mapping-review/pending` → 批量确认 canonical(防呆,fool-proof)→ `POST .../{upload_id}/confirm`(写 pin)。**闭合 Phase 0 自改进环**(没 UI 则队列只进不出、pin 永不累积)。API 已就绪。
3. **【设计→Codex, 大】** **Phase 2 统一源融合**(头条价值"免选/智能"): 在稳定映射上把上传按时间融合。**战略岔口(组织者先 superpowers brainstorm 定)**: 方案1 read-time fusion(读侧 union 同factory所有upload按时间,轻,借Phase0稳定vocab) vs 方案2 各域统一类型时序表写穿(durable/scale better, 镜像 smart_bi_finance_data, 但需 per-域 raw→typed 抽取)。⚠️ **审计已纠正 spec 的 Phase 1**: finance_data 是 ETL-only(全upload_id=0)→ filterToLatestUpload 近 no-op,真"选数据源"痛在 **general upload 路径 `smart_bi_dynamic_data` per-upload + UploadSwitcher**,不在 finance。
4. **【deferred, 小】** 复核确认回写当前 upload field_def(大小写匹配); `require_analytics_write` 收紧复核API; 北极星 Java-AI→Python 全量迁移(多季度,另立)。

## 🔒 铁律/教训(新 chat 必守)
- **新 smartbi 表必 GRANT smartbi_user**(本次血的教训)+ FORCE RLS + policy(factory_id=current_setting('app.factory_id',true))。
- pin/review 所有运行时查询 **GUC 事务内** `set_config('app.factory_id',$1,true)`(FORCE RLS,否则0行)。
- **无自动晋升**(learning_promotion 核心不变量)。单源 vocab(domain_standard_fields)。fail-open。
- 部署 **test 先行**→prod;**jar/迁移/force_rls/GRANT 核对**;mapper 影响所有上传→格外稳。
- Codex 卡自包含(无 .claude/rules);🔒红线(迁移/RLS/权限)Codex 只到 PR→Opus 终审从 main 部署。
- 工作树 off origin/main;主工作目录脏(预存别人WIP)不能直接部署→detach 干净 worktree 部。

## 环境速记
- 服务器 47.100.235.168: Java prod 10010/10020(蓝绿), Python prod 8083 / test 8084。DB localhost:5432 smartbi_prod_db(prod)/smartbi_db(test), smartbi_user/smartbi_secure_password_2025, owner cretas_user, 超级用 `sudo -u postgres`。
- 部署: `./scripts/deploy/deploy-backend.sh --env prod`(Java) / `deploy-smartbi-python.sh --env test|prod`(Python, 跑 migration runner)。
- Phase 0 代码: `backend/python/smartbi/services/{semantic_mapper,domain_standard_fields,domain_inference}.py` + `smartbi/api/excel_async.py` + `smartbi_compat/api/mapping_review.py` + 测试 `tests/test_{semantic_mapper_pin,domain_inference,mapping_review_routes}.py`。
