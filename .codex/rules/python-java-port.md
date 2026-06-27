# Java→Python Port 规范

**最后更新**: 2026-05-02
**适用范围**: Phase 2A SmartBI analysis endpoint port + 未来 Phase 2B+ 任何 Java→Python 字节级 parity port。

---

## 背景

Phase 2A 把 50 个 Java SmartBI analysis endpoint port 到 Python，要求 byte-shape parity（dict-eq 容忍 numeric `0` vs `0.0` 等价；前端层潜在 strict-byte 仍是隐患）。

历史 audit 反复抓到的同类 bug —— 这些 bug 不是 endpoint-specific，而是 Java 语义 vs Python 习惯差异。提升到项目级 rule，避免每个 sister chat 重新 audit。

参见 audit 历史：
- profit chat audit 9 项（2026-04-30，spec commit `e624d4203`）
- payable chat 3 项 reusable findings（merged PR #18）

---

## ⛔ Rule 1: Null fallback 必须用 `is not None` 三元，禁 Python `or`

### 反 pattern（Python falsy ≠ Java null）

```python
# ❌ BAD: Python `Decimal("0") or X` 返 X（Decimal("0") 是 falsy）
total_cost = sum(
    abs(_to_decimal(r.get("total_cost") or r.get("actual_amount")))
    for r in cost_records
)
# 当 total_cost = Decimal("0") 且 actual_amount = Decimal("5000") 时，
# Python 取 5000；Java `r.getTotalCost() != null` 取 Decimal("0")。
# 累计差异在生产数据上爆炸。

# ❌ BAD: 同类型 net_profit
net_profit = sum(...) or None  # 空集合返 None ✓ 但 sum=Decimal("0") 也返 None ✗
```

### 正 pattern

```python
# ✅ GOOD: 显式 is not None 三元
total_cost = sum(
    abs(_to_decimal(
        r["total_cost"] if r.get("total_cost") is not None else r.get("actual_amount")
    ))
    for r in cost_records
    if r.get("total_cost") is not None or r.get("actual_amount") is not None
)

# ✅ GOOD: net_profit 主路径用 reduce 语义（空集合返 Decimal("0")，对齐 Java reduce(BigDecimal.ZERO, +)）
net_profit = sum(
    (_to_decimal(r["actual_amount"]) for r in revenue_records
     if r.get("category") and "净利" in r["category"]),
    Decimal("0"),  # initial value，空 generator 返这个
)
# fallback path 才显式 net_profit = None（Java line 404 行为）
```

### 何时这个 rule 适用

- 任何 Java port 涉及 `Decimal`、`int(0)`、`""`、`[]`、`False` 等 Python falsy 但 Java 非 null 的值
- 源头：审 Java 的 `!= null` 检查，**逐字翻译为 Python `is not None`**
- 不要简化为 `or`
- **不仅仅是 fallback 场景** —— 任何 Java `if (x != null)` 都翻成 Python `if x is not None:`

### 另一种常见错误模式（cost-style，I-2 fix）

即便没有 `or` fallback，也容易踩 `if r.get("col"):` 而不是 `if r.get("col") is not None:`：

```python
# ❌ BAD: Decimal("0") row 会被 dict.get truthy-check 跳过
for r in cost_records:
    if r.get("material_cost"):  # Decimal("0") 是 falsy → 整行 skip
        total += abs(_to_decimal(r["material_cost"]))

# ✅ GOOD: 显式 None-check，Decimal("0") 行参与累加
for r in cost_records:
    if r.get("material_cost") is not None:
        total += abs(_to_decimal(r["material_cost"]))
```

Java `r.getMaterialCost() != null` 在 `BigDecimal.ZERO` 时仍然 True；Python `if r.get("material_cost"):` 把 `Decimal("0")` 误判为 None。

### Why（背景 + 修复历史）

profit chat self-review 抓到 `or None`；reviewer audit 又抓到 `or` falsy on `total_cost`；cost chat reviewer audit 提示这同一根源还有 `if x:` truthy-check 形式。**三次同根源不同表象**。

---

## ⛔ Rule 2: WEEK period key 用 calendar year，不是 ISO year

### 反 pattern

```python
# ❌ BAD: ISO year 跟 Java date.getYear() 不一致
def get_period_key(d, period):
    if period == "WEEK":
        iso_year, iso_week, _ = d.isocalendar()
        return f"{iso_year}-W{iso_week:02d}"  # 跨年 boundary 跟 Java 偏差
```

### 正 pattern

```python
# ✅ GOOD: calendar year 1:1 mirror Java line 1478 (date.getYear())
def get_period_key(d, period):
    if period == "WEEK":
        _iso_year, iso_week, _iso_day = d.isocalendar()
        return f"{d.year}-W{iso_week:02d}"  # calendar year + ISO week
```

### 何时这个 rule 适用

任何 Java port 涉及 `LocalDate.getYear()` 或 `WeekFields.ISO.weekOfYear()` 边界处理。

### Why

跨年 boundary 例（2024-12-30 周一）：
- Java `date.getYear()` = 2024，ISO week = `01` → `"2024-W01"`
- Python `isocalendar()[0]` = 2025（ISO year），ISO week = 01 → `"2025-W01"`

测试数据通常不踩边界，bug 直到生产某个跨年报表才暴雷。

---

## ⛔ Rule 3: Python 函数签名 1:1 mirror Java，不要包 wrapper

### 反 pattern

```python
# ❌ BAD: 包了 DateRange wrapper，跟 Java getProfitMetrics(factoryId, startDate, endDate) 不一致
async def _get_profit_metrics(factory_id: str, range_: DateRange) -> list[dict]:
    ...
```

### 正 pattern

```python
# ✅ GOOD: 直接 mirror Java
async def _get_profit_metrics(factory_id: str, start_date: date, end_date: date) -> list[dict]:
    ...
```

### 何时这个 rule 适用

任何 Java service method port 到 Python 异步函数。

### Why

- Java 端 `LocalDate startDate, LocalDate endDate` 是 byte-shape parity 的契约一部分
- 包 wrapper 增加 caller 心智负担（每次调用要 `range_ = DateRange.custom(start, end)`）
- Sister chats 看到不一致签名会困惑（payable 是 `(factory_id, end_date)`，profit 是 `(factory_id, range_)` —— 哪个对？）
- 一致的签名让 audit / mock / test 全部对齐

`DateRange` 仍然有用 —— 但作为 envelope-level 工具（如 dashboard composite 的 `dateRange` 字段），不是 service method 参数。

---

## ⛔ Rule 4: BigDecimal 序列化用 `_decimal_to_number`

### 反 pattern

```python
# ❌ BAD: 直接返 Decimal，FastAPI JSONResponse 把 Decimal 序列化为字符串 "0.00"
return {
    "value": gross_profit.quantize(Decimal("0.01"), ROUND_HALF_UP),
}
# 输出: {"value": "0.00"}  ← Java 输出 0.00（数值），dict-eq fail
```

### 正 pattern

```python
# ✅ GOOD: 用 _decimal_to_number helper（profit chat 已落地在 analysis_finance.py）
return {
    "value": _decimal_to_number(gross_profit.quantize(Decimal("0.01"), ROUND_HALF_UP)),
}
# 输出: {"value": 0}  ← Python int，dict-eq 跟 Java 0.0 / 0.00 等价
```

### Helper 实现（参考）

```python
def _decimal_to_number(v: Decimal) -> Any:
    """Convert Decimal to int (if integral) or float, JSON-safe.

    FastAPI 默认把 Decimal 序列化为 string，跟 Java Jackson 数值序列化不 parity。
    """
    if v == v.to_integral_value():
        return int(v)
    return float(v)
```

### 何时这个 rule 适用

任何 Java port 输出 `BigDecimal` 字段（金额、率、百分比、天数等）。

### Why

- FastAPI default `Decimal → str`（精度安全但破坏 byte parity）
- Java Jackson default `BigDecimal → number`（保留 scale 但精度依赖 Java 端 setScale）
- `_decimal_to_number` 在 dict-eq gate 下 OK；strict-byte gate 下还需 canonical compare（Phase 2A backlog）

### Phase 2A dict-eq gate — official standard (2026-05-07, confirmed by PR #122 + raw-body fetch)

**Phase 2A 锁定 dict-eq gate, NOT strict-byte.** 以下 byte-shape divergence 是 expected behavior, 不需要 fix:

#### Pattern A: integer-valued Decimal int-collapse (H1 confirmed)

`_decimal_to_number(Decimal("100.00"))` returns `int(100)` because `Decimal("100.00") == Decimal("100.00").to_integral_value()` is True. Java `BigDecimal("100.00")` Jackson serialize → `100.00` (scale-preserved). Per-occurrence delta: **+3 chars** Java-bigger.

```diff
# Java prod 10020 vs Python prod 8083, F001 budget endpoint, 2026-05-07 reproduction:
< "actual": 2103829.00,        # Java BigDecimal scale-preserved
---
> "actual": 2103829,            # Python int(v) collapse
< "value": 0.00                 # Java BigDecimal.ZERO setScale(2)
---
> "value": 0                    # Python int(0)
```

#### Pattern A2: scale-4 Decimal trailing-zero collapse to float

`_decimal_to_number(Decimal("99.9900"))` returns `float(99.99)`. Java `BigDecimal("99.9900")` → `99.9900`. Per-occurrence delta: **+2 to +4 chars** depending on how many trailing zeros.

```diff
< "executionRate": 99.9900,     # Java scale-4 preserved
---
> "executionRate": 99.99,       # Python float trailing-zero loss
< "executionRate": 100.0000,    # Java scale-4 preserved
---
> "executionRate": 100,         # Python int collapse (also Pattern A)
```

#### Why dict-eq, not strict-byte?

dict-eq gate compares semantic numeric equality (`Decimal("100") == Decimal("100.00")` after parse → match). strict-byte compares string length (6 chars vs 3 chars → diverge).

Phase 2A scope: Java→Python port 50 SmartBI analysis endpoints. Decimal/Date/Map.of 等语言习惯差异让 strict-byte 不可达 (会 require 重写 Java side 或包 wrapper at Python). dict-eq 容忍 numeric `0` ≡ `0.0` ≡ `0.00`, frontend 解析后 dict equality 一致, business 行为完全一致。

**T6.1 dryrun 99.945% match rate is the official Phase 2A parity standard。** T6.3+ cutover GO criteria use dict-eq match rate, not strict-byte.

#### Acceptance criteria

| Scenario | Phase 2A action | Phase 3+ consideration |
|---|---|---|
| Pattern A/A2 byte delta in dict-eq match | **Accept** — expected, not a bug | Re-evaluate when strict-byte gate adopted |
| User-facing field semantically wrong | Fix (per existing Rule 4 正 pattern) | Same |
| Pattern B (Java legacy fallback structural divergence) | NOT dict-eq scope — fix per chat 2 PR investigation Option 1 | Same |
| Microsecond / String.format / Map.of order divergence | Fix (Rules 11 / 12 / 8) — those ARE byte-strict scoped | Same |

#### When to upgrade to strict-byte (Phase 3+)

- 客户面 frontend 直接 hash 比对 raw JSON 字符串
- 第三方 integration contract 要求 byte-identical
- API contract 写明 strict serialization (e.g. `application/vnd.api+json` strict mode)

Phase 2A 没这些约束, dict-eq sufficient。

#### Confirmed by

- PR #122 chat 1 audit (M=0 in budget path) → H1 hypothesis
- 2026-05-07 raw-body reproduction at server 47: Java prod 10020 vs Python prod 8083 for F001 budget endpoint shows 36 trailing-zero Decimal occurrences accounting for ~106B delta, 0 Rule 11/12/structural divergence
- Evidence doc: `docs/qa-audits/2026-05-07-h1-confirm-raw-body-evidence.md`
- Cross-reference: chat 2 PR #119 T6.1 dryrun 11/1144 budget diverges all explained; chat 2 finance composite +4531B is **separate** (Pattern B, not dict-eq scope)

---

## ⛔ Rule 5: 共享 SQL helpers 用 `SELECT *`，不裸列字段

### 反 pattern

```python
# ❌ BAD: 显式 24 列
sql = """
    SELECT id, factory_id, upload_id, record_date, record_type,
           department, category, customer_name, supplier_name,
           material_cost, labor_cost, overhead_cost, total_cost,
           ...
    FROM smart_bi_finance_data WHERE ...
"""
```

### 正 pattern

```python
# ✅ GOOD: SELECT *，consumer 用 dict.get 容忍未来加列
sql = """
    SELECT *
    FROM smart_bi_finance_data
    WHERE factory_id = $1 AND record_type = $2 AND record_date BETWEEN $3 AND $4
"""
```

### 何时这个 rule 适用

**新建跨 sister chats 共享的 SQL helper**（如 `_query_finance_data` 给 cost / receivable / budget 复用）。

### Legacy 例外（I-1 fix）

`_query_finance_payable_data`（PR #18 已 merge，single-record-type specialized）保留 explicit columns，**不动**。理由：
- 已 merge 的代码改 SELECT * 是无谓 churn（功能相同）
- 它本来就只服务 payable，不需要扩展性
- 名字含"payable"明示其特化用途，跟通用 `_query_finance_data` 区分清楚

新 helper 用 SELECT *；老 helper 保留各自 explicit。

### Why

- Sister chats 各自需要不同列（cost 需要 material/labor/overhead；receivable 需要 receivable_amount/aging_days；payable 需要 payable_amount/payment_amount）
- 显式列表逼迫每加一个 sister 修改 SQL → 共享 helper 反 pattern
- Schema 演进（加列）不破坏共享 helper
- consumer side `dict.get("col_name")` 容忍未来加列

---

## ⛔ Rule 6: 输入边界 None-check 拒绝静默零结果

### 反 pattern

```python
# ❌ BAD: asyncpg 把 None 转 NULL，BETWEEN NULL AND NULL 返 0 行，调用方拿到空 list 一脸懵
async def _query_finance_data(factory_id, record_type, start_date, end_date):
    sql = "SELECT * FROM smart_bi_finance_data WHERE record_date BETWEEN $1 AND $2"
    return await conn.fetch(sql, start_date, end_date)  # 静默零结果
```

### 正 pattern

```python
# ✅ GOOD: 显式 precondition assertion
async def _query_finance_data(factory_id, record_type, start_date, end_date):
    if start_date is None or end_date is None:
        raise ValueError(
            f"_query_finance_data: start_date/end_date required (got {start_date}, {end_date})"
        )
    ...
```

### 何时这个 rule 适用 (I-3 fix — narrowed scope)

**新建** SQL helper / query function 必须显式 None-check。**已有** helper（如 `_query_finance_payable_data`）由 caller 保证非 None 即可，不需要回填 retroactive precondition assertion。

判断标准：
- 是 sister chat 共享的新 helper → 加 precondition
- 是某 endpoint 路径专用的 helper，且 caller 已经从 controller path-param parsing 拿到 validated 输入 → 不强制

### Why

- Java `findByFactoryIdAndRecordTypeAndRecordDateBetween(...)` 传 null 抛 NPE，Controller catch-all 返 500
- Python asyncpg 静默把 None 转 NULL，SQL `BETWEEN NULL AND NULL` 永远 false，返 0 行
- 静默零结果是最难 debug 的 bug —— 给调用方一个误以为"没数据"的假象

---

## ⛔ Rule 7: 浮点阈值比较用 `Decimal`，整数阈值才用 `float()`

### 反 pattern

```python
# ❌ BAD: 阈值 75.5，浮点边界 corner case
def determine_alert(value: Decimal) -> str:
    v = float(value)
    if v < 75.5:  # 0.1 + 0.2 = 0.30000000000000004 类问题
        return "RED"
```

### 正 pattern

```python
# ✅ GOOD: Decimal 比较
def determine_alert(value: Decimal) -> str:
    if value < Decimal("75.5"):
        return "RED"
```

### 何时这个 rule 适用

任何 alert level / threshold / 边界判断，**只要阈值不是整数**。

### Why

- profit 的阈值是整数 15 / 25 / 0 / 20 → `float(Decimal)` 跟 Java `BigDecimal.doubleValue()` 一致
- 餐饮 / 库存 / 质量等子域可能用 75.5 / 0.95 等非整数阈值 → 浮点边界 corner case 在 Python `float()` 跟 Java `doubleValue()` 之间会偶尔 divergence

### Profit chat 现状

profit `_determine_gross_margin_alert` 用 `float()`（OK，整数阈值）。Sister chats 改非整数阈值时**必须**改 Decimal 比较。

---

## ⛔ Rule 8: `Map.of(N)` 序列化 key order 不可凭直觉，必须录 golden 反推

### 反 pattern（Python dict insertion order ≠ Java Map.of Jackson order）

```java
// Java: Jackson 序列化 Map.of 时, key order 由 Map.of 内部 hash 决定，
// 同一组 key 在 Map.of(2)/Map.of(3)/Map.of(4) 下产生不同 order，
// 跟传入 Map.of 的参数顺序 **无关**。
return Map.of(
    "type", "BAR",
    "name", "销售额",
    "yAxisIndex", 0,
    "color", "#5470c6"
);
// Jackson 实际输出 (Map.of(4)): {"yAxisIndex": 0, "type": "BAR", "name": "...", "color": "..."}
//                                  ^^^^^^^^^^^ hash 决定的 order, 不是传入 order
```

```python
# ❌ BAD: 凭直觉按 Java 源码顺序写 dict
def _new_series_entry(type_, name, y_axis_index, color):
    return {
        "type": type_,
        "name": name,
        "yAxisIndex": y_axis_index,
        "color": color,
    }
# Python 3.8+ dict 保 insertion order → 输出 {"type":..., "name":..., "yAxisIndex":..., "color":...}
# 跟 Java 实际 Jackson 输出 {"yAxisIndex":..., "type":..., "name":..., "color":...} byte-shape 不一致
```

### 正 pattern

```python
# ✅ GOOD: 录 golden 反推 Java 真实 order, Python literal 严格 mirror
def _new_series_entry(type_, name, y_axis_index, color):
    return {
        "yAxisIndex": y_axis_index,  # Map.of(4) hash order
        "type": type_,
        "name": name,
        "color": color,
    }
```

### 录 golden 反推 order 的步骤

1. **跑 Java 端真实 endpoint**（dev/staging/prod）拿 raw JSON response：
   ```bash
   ./scripts/record-java-golden.sh F999 <factoryId> <endpoint> <args> > tests/fixtures/java-smartbi-golden/<name>.json
   ```
2. **检查 golden 中每个 Map.of(N) 出现位置的 key order**（用 jq 或肉眼）。
3. **Python literal dict 按 golden order 重写**，不是按 Java 源码 Map.of 参数顺序。
4. **F999 byte-shape gate** dict-eq 比较通过即 OK。**strict-byte gate** 必须 char-by-char identical（Phase 2A 暂用 dict-eq，但 Phase 3+ 上 strict 时 order 必须严格）。

### 何时这个 rule 适用

- Java 端**任何** `Map.of(N)` / `Map.entry(...)` / `LinkedHashMap` 直接 return 给 Jackson 序列化的位置
- 特别注意 `Map.of(2)` / `Map.of(3)` / `Map.of(4)` 各自 hash 算法不同, 同一组 key 跨 N 也不同
- `LinkedHashMap` 保 insertion order, 反而是 Python literal dict 直接 mirror 的标准 case
- **不适用**：Java 端用 DTO class（Lombok `@Data`） + Jackson `@JsonPropertyOrder` 显式标注的 — 那种 order 是 deterministic 按 annotation

### 跨 spec patterns（已踩过的坑）

| Spec | Map.of(N) 位置 | 实际 Java order |
|---|---|---|
| sub-endpoints `_get_yoy_mom_chart` | yAxis | `Map.of(2)`: `[position, name]` |
| sub-endpoints `_get_yoy_mom_chart` | series | `Map.of(4)`: `[yAxisIndex, type, name, color]` |
| sub-endpoints `_get_yoy_mom_chart` | summary | `Map.of(3)`: `[totalYoYGrowthRate, compareTotal, currentTotal]` |
| profit chart | `_new_yaxis_entry` helper | `[name, position]` (跟 sub-endpoints `Map.of(2)` 不同！) |

⚠️ **不同 chart 函数的 Map.of(2) 输出可能不同 order** — `_new_yaxis_entry` helper 跟 sub-endpoints 直接 inline 的 yAxis 是不同 Java 调用站点, hash 可能不同 carry-over。**不要假设可复用 helper 的 order**, 各自录 golden 验证。

### Why

- Java `Map.of(N)` 内部用 `MapN<K,V>` 类（N=1..10），hash 算法跟 N 绑定，N 不同 hash table 不同
- Jackson 序列化时按 hash table iteration order 输出
- Python `dict` 自 3.7 起保证 insertion order — 直接 literal 写出的 order 才是 actual order
- Java 源码看到的 `Map.of("a", 1, "b", 2)` 参数顺序 `a, b` **不等于** Jackson 输出顺序

### 跟 Rule 4 的关系

Rule 4（Decimal serialization）+ Rule 8（key order）合起来才能完整描述 byte-shape parity 输出层。Rule 4 管 value 序列化形式，Rule 8 管 key 顺序。

### Audit 来源

sub-endpoints PR #32（2026-05-01 ship）— Chat 3 在 impl 阶段录 F999/F001 goldens 时发现 3 个 Map.of(N) 都被 Python literal dict insertion order 写错。修法：dict literal 按 golden 实际 order 重写，留 `_new_yaxis_entry` helper 不动（它服务 profit chart, order 跟 sub-endpoints 不同, 不能 cross-use）。

---

## ⛔ Rule 9: Lombok + Jackson 序列化 quirks — 字段名 / null emit / 派生 getter 全 mirror golden

### 反 pattern（spec 凭 Java 源码假设字段名 / null 行为 / 字段数量）

```java
// Java DTO with Lombok @Data:
@Data
public class ChartConfig {
    private String xAxisField;   // ← 源码 camelCase, getter 是 getXAxisField()
    private String yAxisField;
    private String seriesField;
    private List<Map<String, Object>> data;
    private Map<String, Object> options;
    // 注意: 没有 @JsonInclude(NON_NULL) annotation
}
```

```python
# ❌ BAD: 凭 Java 源码假设
def _create_empty_chart(chart_type, title):
    return {
        "chartType": chart_type,
        "title": title,
        "xAxisField": None,    # ← 假设 camelCase
        "yAxisField": None,
        # 假设其他 None 字段会被 Jackson 跳过, 只 emit 4 字段
    }
# Java Jackson 实际输出 (Lombok 派生 getter + 无 @JsonInclude):
# {"chartType":"...", "title":"...", "xaxisField":null, "yaxisField":null, "seriesField":null, "data":[], "options":null}
#                                     ^^^^^^^^^^^ lowercase!     ^^^^^^^^^^^ 全 emit nulls!
```

### 正 pattern

```python
# ✅ GOOD: golden truth, Lombok-Jackson 反直觉但稳定
def _create_empty_chart(chart_type, title):
    return {
        "chartType":   chart_type,
        "title":       title,
        "xaxisField":  None,    # ← lowercase 'a' (Introspector.decapitalize quirk)
        "yaxisField":  None,
        "seriesField": None,    # ← 全 emit (无 @JsonInclude)
        "data":        [],
        "options":     None,
    }
```

### 三个 sub-pattern（全部由 sister chat 独立确认）

#### 9.1 Lombok getter naming + Jackson `Introspector.decapitalize`

`java.beans.Introspector.decapitalize` 处理 **连续大写字母** 时的特殊规则：

| Java 源码 field | Lombok 派生 getter | Jackson 序列化 key |
|---|---|---|
| `name` | `getName()` | `"name"` |
| `xAxisField` | `getXAxisField()` | **`"xaxisField"`** ← 注意小写 'a' |
| `yAxisField` | `getYAxisField()` | **`"yaxisField"`** ← 同样 |
| `URLPath` | `getURLPath()` | **`"URLPath"`** ← 全大写不 decapitalize |
| `aBField` | `getABField()` | `"aBField"` |

规则: **首字母 + 连续大写时，第一个大写降为小写后**剩余字母**也降为小写**, 直到下一个小写字母。`xAxisField`: `xAxis` → `xaxis`, 后跟 `Field` 保留 → `xaxisField`.

Python dict literal: 录 golden 取实际 key, 不要凭 camelCase 假设。

#### 9.2 DTO 无 `@JsonInclude(NON_NULL)` → Jackson emit nulls 显式

| DTO 状态 | Jackson 行为 | Python 必须 |
|---|---|---|
| 有 `@JsonInclude(JsonInclude.Include.NON_NULL)` | null 字段被跳过 | dict 不含 null 字段 |
| **无 `@JsonInclude` (Phase 2A 大部分 DTO)** | **null 字段显式 emit `"field": null`** | **dict 全 emit None** |

确认方式: `grep -n "@JsonInclude" backend/java/.../entity/{DTO}.java` — 0 hit 即默认行为 (emit nulls)。

实际 case (sister chat 独立确认):
- `ChartConfig` (department / region / inventory specs)
- `DateRange` (department spec)
- `MetricResult` (region spec)
- `DashboardResponse` (inventory PR-B spec)
- `AIInsight` (inventory PR-B spec)

#### 9.3 Lombok `@Data` 派生 boolean / computed getter → Jackson 多 emit 字段

Lombok `@Data` 自动生成 getter, 包括 `is*` boolean methods。Jackson 把这些当字段 emit:

| Java field | 源码 getter | Lombok 派生 | Jackson emit |
|---|---|---|---|
| `int days` | `getDays()` | `getDays()` | `"days": <int>` |
| `boolean valid` | `isValid()` | `isValid()` | `"valid": <bool>` |
| `String name` | `getName()` | `getName()` | `"name": <str>` |
| (no field, only `getDaysBetween()`) | manual method | (Lombok 不动) | `"daysBetween": <int>` (如果非 abstract) |

实际 case (department spec): `DateRange` 源码看似 5 字段, golden truth 是 7 字段 (含 `days` 派生 + `valid` boolean)。

确认方式: `grep -nE "public.*get[A-Z]|public.*is[A-Z]" backend/java/.../entity/{DTO}.java` — 列出全部 getter, golden 必含每一个对应字段。

### 何时这个 rule 适用

- 任何 Java port 涉及 Lombok `@Data` / `@Getter` / `@Builder` 注解的 DTO
- 字段名含 **连续大写字母** (xAxisField, ySeriesField, xPosition 等)
- DTO 无 `@JsonInclude(NON_NULL)` 时 (默认 Jackson 行为, Phase 2A 大部分 DTO)
- 派生 getter (boolean is*, computed get*) 改变实际序列化字段数量

### 实操 checklist (写 spec / impl 时)

1. **录 golden 优先** — Rule 8 + Rule 9 联合: golden 是 byte truth, 源码假设是 brittle
2. **grep `@JsonInclude`** in DTO file — 决定 null 行为
3. **grep getter 列表** in DTO file — 决定字段数量
4. **dict literal 按 golden** — 字段名 / 顺序 / null 完全镜像

### Why（背景 + 修复历史）

3 个独立 sister chat 在 Phase 2A Tier 2 impl 阶段全部踩同坑：

- **inventory PR-A (Chat 1, PR #53, 2026-05-02)**: spec 假设 ChartConfig empty case 3 字段, 实际 7 字段; xaxisField/yaxisField lowercase 而非 camelCase
- **department PR-A (Chat 4, PR #52, 2026-05-02)**: 同 4 处 spec drift, baked 进 commit `845329468` with `⚠️ Spec §X.Y was WRONG` annotations
- **region PR-A (Chat 2, in flight)**: 独立确认 F1 (xaxisField lowercase) + F2 (ChartConfig empty 7-field) + F7 (MetricResult 11-field changeValue null)

3 次独立确认即 graduate hard-rule 阈值。

### 跟 Rule 4 / Rule 8 的关系

- Rule 4 (Decimal serialization): value 形式
- Rule 8 (Map.of key order): hash-based 不可预测 key 顺序
- **Rule 9 (Lombok + Jackson)**: DTO 字段名 / null emit / 派生 getter

三个合起来描述 byte-shape parity 输出层全部决定因素。

### Audit 来源

| 来源 | 发现 |
|---|---|
| inventory PR #47 spec audit cycle 4 | spec drift caught by impl-reviewer (Chat 4 audit on Chat 4-written spec) |
| inventory PR #53 impl Task 10 | spec drift surfaced via golden-truth comparison (Chat 1) |
| department PR #52 impl golden recording | 4 spec inaccuracies baked-fixed inline (Chat 4) |
| region PR #56 impl Task 2 (in flight) | F1/F2/F7 independent confirmation (Chat 2) |
| ChartConfig.java line 32 | 验证无 `@JsonInclude` annotation |
| DateRange.java | 验证 7 字段含 days + valid Lombok 派生 |
| MetricResult.java | 验证 11 字段含 changeValue null between changeDirection / alertLevel |

---

## ⛔ Rule 10: BigDecimal `divide(scale,rounding).multiply(K)` ≠ Python `(n/d*K).quantize(scale)`

### 反 pattern (compounded rounding error)

```python
# ❌ BAD: 一次性算完再 quantize, 跟 Java 中间步 round 不一致
def calculate_completion_rate(actual: Decimal, target: Decimal) -> Decimal:
    return (actual / target * 100).quantize(Decimal("0.01"), ROUND_HALF_UP)
# 1/3 * 100 = 33.333333..., quantize(scale=2) → 33.33
# Java actual: BigDecimal.ONE.divide(THREE, 4, HALF_UP).multiply(100) = 0.3333 * 100 = 33.3300
#                                                                       ↑ 4-digit quantize first
```

具体踩坑值（real PR-M-2 audit 2026-05-06 抓到 4 个 endpoint）：
- alerts: 14.91 (Java) vs 14.9074 (Python wrong)
- category-comparison: 15.28 vs 15.29
- procurement: 46.6% vs 46.5%
- sales: 15.28 vs 15.29

### 正 pattern (mirror Java intermediate-round-then-multiply)

```python
# ✅ GOOD: divide 阶段先 quantize 到 4 位 (Java 第一参数 scale=4), 再 multiply, 再 final quantize
def calculate_completion_rate(actual: Decimal, target: Decimal) -> Decimal:
    if target == 0:
        return Decimal("0")
    intermediate = (actual / target).quantize(Decimal("0.0001"), ROUND_HALF_UP)  # 4-digit
    result = intermediate * Decimal("100")
    return result.quantize(Decimal("0.01"), ROUND_HALF_UP)  # final scale 2
# 1/3 = 0.3333 (quantize 4) * 100 = 33.3300 (quantize 2 no-op since exact) ✓
```

### 何时这个 rule 适用

任何 Java port 涉及：
1. `BigDecimal.divide(divisor, scale, RoundingMode)` 接 `.multiply(K)` 接最终 quantize
2. 百分比 / 比率 / 增长率计算 (completion rate, MoM growth, YoY ratio, profit margin, supplier concentration)
3. **Python 端写 `(n / d * K).quantize(scale_2)` 的任何 site** (Decimal arithmetic 默认 28 位 precision，溢出末位)

### Audit 来源

- PR-M-2 2026-05-06 (chat 4 ship, commit `d61e1b46b` PR #94)
- 4 个 sister chat 各自踩一次 (alerts / category-comparison / procurement / sales) → graduate hard-rule 阈值满足
- Latent sites still pending audit per chat 4 finding: `analysis_finance.py:1666, 1679, 1695, 1832, 1896, 2095, 2163, 2195, 2636, 2651, 2830` + `_safe_growth_rate` + `_calculate_metric_from_sales` (F001 数据下不爆，其他 factory 可能踩)

### 跟 Rule 4 的关系

Rule 4 管 BigDecimal **序列化** (Decimal → number 不是 string)。Rule 10 管 BigDecimal **算术** (divide-multiply 中间步 round)。两个不同位置，都必须 mirror Java。

---

## ⛔ Rule 11: Java Jackson `LocalDateTime` drops trailing-zero microseconds

### 反 pattern

```python
# ❌ BAD: Python datetime.isoformat() pads microsecond to 6 digits
import datetime
dt = datetime.datetime(2026, 5, 6, 10, 30, 0, 150710)
print(dt.isoformat())
# Python: '2026-05-06T10:30:00.150710'
# Java :  '2026-05-06T10:30:00.15071'   ← 末位 0 被 Jackson trim 掉
# byte-shape diff!
```

具体踩坑 case (PR-M-7 audit 2026-05-06):
- query-templates: `data[0].createdAt` 在 cretas_db 数据 happens-to 整 6 位时 match by luck, 但其他 factory 数据 .xxxx0 会爆
- datasource/list F001: 7 个 timestamp 字段全踩 (test env 数据 scale=5 居多)
- Latent prod risk affecting **~50 endpoints** emitting any `LocalDateTime`

### 正 pattern

```python
# ✅ GOOD: 用 _java_isoformat 共用 helper (定义 in backend/python/smartbi_compat/schema_compat.py)
from smartbi_compat.schema_compat import _java_isoformat

dt = datetime.datetime(2026, 5, 6, 10, 30, 0, 150710)
print(_java_isoformat(dt))
# '2026-05-06T10:30:00.15071'  ← rstrip("0") on fraction part, drop dot if frac empty
```

### Helper impl reference

```python
def _java_isoformat(dt) -> Optional[str]:
    """Mirror Jackson LocalDateTime/LocalDate ISO-8601 output."""
    if dt is None:
        return None
    s = dt.isoformat()
    if "." not in s:
        return s   # LocalDate or whole-second LocalDateTime
    head, frac = s.rsplit(".", 1)
    if not frac.isdigit():
        return s   # defensive: timezone offset
    frac = frac.rstrip("0")
    if not frac:
        return head   # all zeros → Java drops dot entirely
    return f"{head}.{frac}"
```

### 何时这个 rule 适用

任何 Python 端 `.isoformat()` call on datetime (LocalDateTime mirror)：
- Audit `grep -rn "datetime.now\(\).isoformat\(\)\|\.isoformat\(\)" backend/python/smartbi_compat/`
- Replace ALL with `_java_isoformat(dt)`
- 例外：log timestamps / console output / non-Java-mirrored 用途 — 留 default isoformat

### Audit 来源

- PR-M-7 2026-05-06 (chat 3 ship, commit `e2a527326` PR #93)
- Sister D investigation (PR-M doc commit `5c7c35222`) Cat G 首次 identified
- 8 files updated in PR-M-7 across smartbi_compat (analysis.py / analysis_finance.py / analysis_sales.py / datasource.py / incentive_plan.py / query_templates_write.py / schema_compat.py + tests)

### 跟 Rule 8 / Rule 9 的关系

Rule 8 (Map.of key order) + Rule 9 (Lombok null emit) + Rule 11 (LocalDateTime microsecond) — 三个互补的 Jackson 序列化 quirk。任何 byte-shape parity port 必须三个全 mirror。

---

## ⛔ Rule 12: Java `String.format("%.Nf", d)` HALF_UP vs Python f-string `:.Nf` 银行家舍入

### 反 pattern (Python f-string banker's rounding)

```python
# ❌ BAD: Python f-string :.Nf uses banker's rounding (round half to even, IEEE 754)
formatted_value = f"{float(concentration):.1f}%"
# concentration = Decimal("46.55")  → "46.5" (banker)
# Java String.format("%.1f", concentration) → "46.6" (HALF_UP)
```

具体踩坑值（PR-N-1 closer fix 2026-05-06 抓到）:
- procurement supplier concentration: Java "46.6%" vs Python "46.5%"
  - 内部 concentration value = `Decimal("46.5500")` (scale 4 from Rule 10 fix)
  - `float(46.55)` 在 IEEE 754 下 = 46.549999... → f-string `:.1f` 取 "46.5"
  - Java 用 BigDecimal.toPlainString + manual format / String.format HALF_UP → "46.6"

### 正 pattern (Decimal.quantize HALF_UP)

```python
# ✅ GOOD: Decimal.quantize(scale, ROUND_HALF_UP) gives Java-equivalent result
from decimal import Decimal, ROUND_HALF_UP
formatted_value = f"{concentration.quantize(Decimal('0.1'), rounding=ROUND_HALF_UP)}%"
# concentration = Decimal("46.55") → quantize(0.1, HALF_UP) → Decimal("46.6") → "46.6"

# OR use shared helper (preferred for new code):
from smartbi_compat._java_compat import _format_decimal_half_up
formatted_value = f"{_format_decimal_half_up(concentration, 1)}%"
# Helper bakes Decimal conversion + HALF_UP at scale `places`.
```

### 何时这个 rule 适用

**任何**显示格式化 Java 端用 `String.format("%.Nf", d)` / `BigDecimal.setScale(N, HALF_UP).toPlainString()` 等的 site，Python 这边都不能用：
- `f"{float(d):.Nf}"` (banker's)
- `f"{d:.Nf}"` directly on Decimal — 也是 banker's via `__format__`
- `"%.Nf" % d` (banker's via printf-style)
- `format(d, '.Nf')` (same)

**例外 pre-quantize HALF_UP at exact target scale**:

```python
# This pattern is SAFE — pre-quantize to HALF_UP at scale N matches f-string output
quantized = value.quantize(Decimal("0.1"), rounding=ROUND_HALF_UP)  # already HALF_UP'd
return f"{float(quantized):.1f}%"  # f-string just renders, no re-rounding (boundary already resolved)
```

The pre-quantize step makes the value land exactly at the f-string scale boundary, avoiding the banker's-rounding zone. `analysis_sales.py:_format_completion_pct` follows this pattern, safe.

### 注意 Decimal.quantize 默认舍入模式 = ROUND_HALF_EVEN

```python
# ❌ BAD — Decimal.quantize default rounding is ROUND_HALF_EVEN (banker's)
quantized = value.quantize(Decimal("0.01"))  # NO rounding param → banker's

# ✅ GOOD — explicit ROUND_HALF_UP for Java parity
quantized = value.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
```

This was the Rule 12 latent gap in `analysis_drilldown.py:_build_kpi_card` (fix in commit `69b46f4d5` 2026-05-07).

### Audit 来源

- 1st hit: PR-N-1 closer (organizer commit `0982195cf` 2026-05-06) — procurement
  supplier concentration 46.55 → "46.6" Java vs "46.5" Python at f-string display
- 2nd hit: 12 defensive proactive fixes commit `69b46f4d5` 2026-05-07 — across
  analysis_inventory.py (11 sites: turnover_rate / inventory_days / expiry_risk /
  slow_moving / loss_rate / health_score + 5 insight messages) +
  analysis_drilldown.py (1 site: _build_kpi_card)
- Helper added: `_format_decimal_half_up(d, places)` in
  `backend/python/smartbi_compat/_java_compat.py`

### 跟 Rule 4 / Rule 10 的关系

Rule 4 (Decimal serialization int vs float vs str): JSON 数值层面，控制 emit 是 number 还是 string。
Rule 10 (Java BigDecimal divide-then-multiply 中间步 round): 算术层面，控制中间精度。
Rule 12 (Java String.format HALF_UP vs Python banker's): 显示格式化层面，控制 final string 的舍入。

三个独立 layer 都要 mirror Java semantic 才能 byte-shape parity。Rule 10 修中间精度后还可能因 Rule 12 在最后一步显示时 diverge。

---

## 工具 + 配置 reference

### `_decimal_to_number` helper

定义位置：`backend/python/smartbi_compat/api/analysis_finance.py`（profit PR-A 落地，sister chats 直接 import）

### `record-java-golden.sh` 录制脚本

定义位置：`scripts/record-java-golden.sh`（profit PR-A 落地）

CLI: 参见 `docs/superpowers/specs/2026-04-30-phase2a-analysis-finance-profit-design.md` §5.5

### Golden 命名 convention

`tests/fixtures/java-smartbi-golden/analysis-finance-{F999|F001}-{type}.json`

无 `_meta` envelope，raw response only。

### Test mock pattern

```python
async def fake_query(factory_id, record_type, start, end):
    # 合成 row 对应 specific 算术分支
    return [...]

monkeypatch.setattr(
    "smartbi_compat.api.analysis_finance._query_finance_data",
    fake_query,
)
```

---

## 何时新增 rule

每个 audit 抓到的**普适**问题（≥2 个 sister chat 都会踩）应该 graduate 到这个文件。Endpoint-specific 的留 spec doc。

判断标准：
- 是 Python vs Java 语言习惯差异 → rule
- 是数据库/序列化/字段命名习惯 → rule
- 是某 endpoint 的 shape 决定 → spec
- 是 Phase 2A 一次性 migration 决定 → spec（如 byte gate 是 dict-eq 而非 strict-byte）

---

## Audit 历史

| 来源 | Rule 抓到 |
|---|---|
| profit chat self-review (2026-04-30) | Rule 1（`or None` net_profit） |
| profit chat reviewer audit (2026-04-30) | Rule 1（`or` falsy total_cost）/ Rule 2 / Rule 3 / Rule 5 / Rule 6 / Rule 7 |
| payable PR #18 retrospect | Rule 4（Decimal serialization 一致性） |
| cost chat reviewer audit (2026-04-30) | Rule 1（`if x:` truthy-check 形式）/ Rule 5（legacy 例外）/ Rule 6（narrowed scope） |
| sub-endpoints PR #32 impl (2026-05-01) | Rule 8（Map.of(N) Jackson hash order — 3 个不同 N 全踩坑） |
| inventory PR #53 + department PR #52 + region in-flight (2026-05-02) | Rule 9（Lombok + Jackson 序列化 quirks — 3 个 sister chat 独立确认 3 个 sub-pattern） |
| T6.1 dryrun pre-flight + PR-M-2 (2026-05-06) | Rule 10（BigDecimal divide-then-multiply 中间步 round — 4 sister chat 各踩一次：alerts/category-comparison/procurement/sales） |
| Sister D PR-M doc + PR-M-7 (2026-05-06) | Rule 11（Java Jackson LocalDateTime trailing-zero microsecond — datasource/list F001 7 timestamps 踩；latent prod risk on ~50 endpoints） |
| Procurement display closer (commit 0982195cf 2026-05-06) + 12 defensive fixes (commit 69b46f4d5 2026-05-07) | Rule 12（Java String.format HALF_UP vs Python f-string banker's — 1 active hit + 12 latent sites swept） |
| PR #122 chat 1 audit + raw-body reproduction (2026-05-07) | Rule 4 Phase 2A dict-eq gate official entry — chat 2 PR #119 11 budget +107B diverges 全部 explained by Pattern A/A2 integer-Decimal int-collapse + scale-4 trailing-zero loss; T6.1 dryrun 99.945% match rate 是 Phase 2A parity 标准 |

后续 sister chats（receivable / budget / 9 个分析子域）应跑过 reviewer audit；新发现 graduate 到这里。
