# 餐饮 AI 问答飞轮 — 每周治理 Runbook

**建立**: 2026-07-08（语义层五共识 #5"常态化治理"落地：机制已有，本文补节律）
**频率**: 每周一次，约 15 分钟
**前置**: 飞轮机制见 `docs/superpowers/specs/2026-07-07-restaurant-intent-tiered-routing-design.md` §5 + `smartbi/gold/restaurant_intent_promotion.py` 模块 docstring

---

## 每周动作清单

### 1. 看两个健康指标（prod DB，SSH 到 47 执行）

```bash
ssh root@47.100.235.168
SECRET=$(grep -oP "^SMARTBI_DB_PASSWORD=\K.*" /www/wwwroot/cretas/.env.prod)
PGPASSWORD=$SECRET psql -h localhost -U smartbi_user -d smartbi_prod_db
```

**指标 A：分层占比 + 契约通过率（近 7 天）**

```sql
SELECT agg_meta->>'tier'          AS tier,
       agg_meta->>'source'        AS source,          -- java_entry_delegate vs NULL(chat.py)
       count(*)                   AS n,
       round(avg(CASE WHEN agg_meta->>'contract_pass'='true' THEN 1 ELSE 0 END)::numeric, 3) AS contract_pass_rate
FROM smart_bi_llm_fallback_log
WHERE template_code LIKE 'RESTAURANT_OPS_%'
  AND created_at > now() - interval '7 days'
GROUP BY 1, 2 ORDER BY n DESC;
```

判读：
- **tier=llm 占比**应随晋升下降（初期 ~30% → 目标 <10%）。持续不降 = 晋升没做或说法分布太散。
- **contract_pass_rate < 0.8** = 回答系统性缺东西（常见：某 resolver 不回显时间窗/毛利），按 query 抽样看 missing 的是哪类元素。
- **source=java_entry_delegate 的量**：委托占比异常升高 → Java 侧意图分类质量在退化；归零 → 委托链可能断了（查 Java log "tiered-intent delegate gate failed"）。

**指标 B：澄清率（近 7 天）**

```sql
SELECT count(*) FILTER (WHERE agg_meta->>'clarification_needed'='true')::float
       / GREATEST(count(*), 1) AS clarify_rate
FROM smart_bi_llm_fallback_log
WHERE template_code LIKE 'RESTAURANT_OPS_%'
  AND created_at > now() - interval '7 days';
```

澄清率 >20% = T3 prompt 或意图描述需要调，不是加词表。

### 2. 评审晋升候选（开发机）

```powershell
# 先开 SSH 隧道连 prod DB（脚本头部有环境变量说明）
ssh -L 15432:localhost:5432 root@47.100.235.168
# 另一终端：
python scripts/restaurant-intent-promote.py --list
```

评审原则（人审是唯一闸门，宁缺勿滥）：
- ✅ 收：通用老板说法（"生意咋样""挣着钱没"类），code 无冲突，读起来就是这个意图
- ❌ 拒：带具体门店/菜品名的（太特化）、conflict=true 的、意图本身模糊的
- 拿不准 → 留着下周再看（出现次数会累积）

```powershell
# 认可的存成 approved.json 后：
python scripts/restaurant-intent-promote.py --apply approved.json
# 然后 commit 账本 + 部署（严格按干净 worktree 流程）：
#   git commit -m "chore: 餐饮意图晋升 N 条" -- backend/python/smartbi/data/promoted_restaurant_intent_samples.json
#   cd /c/Users/Steve/cretas-deploy-clean && git fetch origin main -q && git reset --hard origin/main -q && bash scripts/deploy/deploy-smartbi-python.sh --env prod
```

### 3. 抽查 3 条真实差答案（可选，5 分钟）

指标 A 里 contract_pass=false 的 query 挑 3 条，在 demo 里重问一遍看实际观感。契约 missing 但答案其实合格 → 是契约启发式误判，记下来修契约而不是改回答。

---

## 什么时候升级处理

| 信号 | 动作 |
|---|---|
| contract_pass_rate 连续两周 <0.7 | 开修复任务（按 missing 元素类型定位 resolver） |
| llm 占比连续 4 周不降 | 检查晋升是否真的部署生效（`populate_restaurant_ops` 启动日志条数） |
| 委托量归零 | 查 INTERNAL_API_SECRET / Python 端点健康，Java log grep "tiered-intent" |
| 出现新的高频意图类型（候选里反复出现现有 8 码盖不住的问题） | 不加词表——评估加新 RESTAURANT_OPS_* 码 + resolver（走 spec） |
