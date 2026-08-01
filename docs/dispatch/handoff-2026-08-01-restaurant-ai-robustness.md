# 交接：餐饮 AI 健壮性 —— 越权泄露已修，口语 0/6 差最后两步（2026-08-01）

**起点**：`handoff-2026-07-31-release-pipeline-and-restaurant-repair.md`
**终点**：`origin/main = a711b86149`，本轮 10 个 PR 全部合入

---

## ⚠️ 接手先读这条

**本文和上一份一样，写的是「我知道的状态」。** 本 session 亲身在自己的判据上错了
**八次**，每次都是被测试 / 变异检验 / 自查拦下来的。所以：

```bash
git fetch origin && git log origin/main --oneline -5
gh pr list --state open
gh run list --branch main --limit 5
```

所有数字都标了「实测 / 推算」。未标实测的当推测。

---

## 一、最该知道的：一个已修复的线上越权泄露

后厨角色（`restaurant_chef`，不在 `PRICE_VIEW_ROLES`）能通过 AI 问出全店金额。
prod 实拍（MOCK_REST，同一问句只换角色）：

| 问句 | 老板 | 后厨（修前 → 修后） |
|---|---|---|
| 哪家店毛利最好 | ¥34,959,425 | 看不到 → 看不到（本来就有门） |
| 损耗金额最高的食材 | ¥278,254.85 | **¥278,254.85** → 看不到 |
| 采购花了多少钱 | ¥7,094,935.77 | **¥7,094,935.77** → 看不到 |
| 盘点亏了多少 | ¥5,836.21 | **¥5,836.21** → 看不到 |

**根因是机制不是疏忽**：`resolve_by_code` 按签名过滤 kwargs，没声明 `role` 的
resolver 拿不到它 —— 这写在它自己的 docstring 里（"legacy resolvers silently
ignore role"）。与 #2076 丢 `date_range` **同一个机制**。

修法（#2119）：`_MONEY_BEARING_INTENTS` 登记表 + 中央闸，不是给 4 个 resolver
各加一个参数（那样第 15 个还会犯）。最关键的用例是
`test_self_masking_resolvers_can_actually_see_the_role` —— 声明「自己脱敏」的
resolver 签名里**必须**有 `role`。

⚠️ 我 08-01 把盘亏从数量改成金额口径（#2112），等于给这个口子又加了一条。
**做能力增强时要顺手问一次「这条路上有没有权限闸」。**

---

## 二、诚实基线：对抗性 50-57%，模板化 100%

`backend/python/scripts/restaurant_adversarial_audit.py`（44 条 × 九维 × 四角色）

    MOCK_REST      通过 18/36 = 50.0%   无数据 8/44
    RES_3101_009   通过 16/28 = 57.1%   无数据 16/44

对照：`restaurant_capability_audit.py` 的 21 条**全是同一个模板**
（`全部门店` + `最近30天` + 标准术语），所以它量的是「理想措辞下能不能答」，
21/21 说明不了线上问得出答案。

**两份审计的分工**：capability = 能力还在不在（每日 timer，有 `--fail-under`）；
adversarial = 换个说法还答不答得对（按需跑，产出**失败分类**）。

### 三个数据事实

1. **prod 没有任何租户能验完整能力** —— 结论成立, 但 ⚠️ **原因我当时写错了**。

   ❌ 我原写「MOCK_REST 最近 30 天没有 POS 流水」。**这是错的**, 而且错法很典型:
   我的依据是 **AI 自己的回答**(「最近30天没有可用的营收和订单数据」), 我把
   **系统行为当成了数据事实**, 从没直查过库。

   ✅ [实测 2026-08-01, superuser 绕过 RLS + 带租户 GUC 双向确认]:

   | 租户 | POS 总行 | 最近30天 POS | agg_daily 近30天 | 后厨领料 | 后厨损耗 | 菜品成本 |
   |---|---|---|---|---|---|---|
   | MOCK_REST | 100,862 (06-29→07-31) | **94,862** | 300 行 / ¥3416 万 | 4,290 | 5,978 | **0** |
   | RES_3101_009 | 591,026 | 63,260 | 883 行 / ¥1050 万 | 3 | 10 | 136 |

   **真正缺的是菜品成本**(`agg_restaurant_product_cost` MOCK_REST 0 行), 不是 POS。
   方向完全不同: 前者要补 POS 接入(不需要), 后者只要补成本数据。

   🔴 **而且这反过来暴露一个真缺陷**: 数据齐全, AI 却答「没有可用的营收和订单数据」。
   我的对抗性审计把这类回答归为「诚实说没数据」并计入「无数据」而非失败 ——
   **所以那个 50-57% 的基线是高估的**。
2. 🔴 **`smartbi/gold/queries.py` 的助手函数不自己设租户 GUC** [实测 2026-08-01]

   `restaurant_ops_router.py` 里 **14 处**显式
   `set_config('app.factory_id', ...)`; 而 `gold/queries.py` **31 个 async 函数
   里只有 3 处**设 —— `finance_summary`(营收/单量的唯一来源)**不在其中**。

   它依赖调用方碰巧在这条连接上设过。实测后果是**非确定性**: 同一份代码、同一个
   date_range, 两次跑一次返回 ¥34,160,545.84 / 94,862 单, 一次返回全 0
   (而同一个池上的直查始终正确 —— 300 行, 排除了 RLS 本身)。

   ⚠️ 这与 #2076(签名没声明就静默丢弃 date_range)、2026-08-01 RBAC 泄露
   (签名没 role 就拿不到角色)**同一族**: 契约靠调用方记得, 而「忘了」不报错、
   只是**静默返回空**。修法方向是让这些助手自己保证租户上下文, 而不是靠约定。
   ⛔ 未修 —— 需要改 resolver 并上 prod, 按卡片约束只做到实现+PR, 本轮先记账。

3. **口语族 0/6 是跨两个租户唯一稳定复现的缺陷**。
4. 「已给足门店+时间仍被反问」在 MOCK_REST 上 9 条、RES_3101_009 上 3 条，
   **只有 2 条跨租户复现** —— 其余是 LLM 抖动，不是确定性缺陷。

### ⛔ 这个工具自己造过假缺陷，已修，但要知道形状

- 缺 `sys.path` 里的 `smartbi/` 那条 → `from services import` 抛
  `No module named 'services'` → 被包装成「餐饮执行链暂时不可用」，**长得和真缺陷
  一模一样**。生产日志里该错误 **0 次**（阳性对照：restaurant-intent 1767 次）。
  脚本现在自己接路径，不靠「记得设 PYTHONPATH」。
- 判据经历三个版本：把「诚实说没数据」先算失败（假阳性）→ 又算通过（**空租户会
  全绿**）→ 三态分开计数。**「没胡编」和「有能力」不能是同一个格子。**

---

## 三、口语 0/6：根因已定位，差最后两步

### 根因（不是策略问题）

反问管线拿**问题字符串本身**当类型系统：改动前 10 处
`clarification_question == TIME_/STORE_...` 在做分支。后果是结构性的 ——
时间闸先设 `clarification_needed`，门店闸开头就 `or spec.clarification_needed
→ return`，**一轮只能携带一个缺失槽位**。于是「最近生意咋样」要三轮
（问门店 → 问时间 → 答）。

#2121 已把 10 处收敛到**一个边界函数** `_slot_of_clarification`，行为中性。

### 已验证可行的部分（代码写过、prod 跑过）

- 合成追问在 prod 实测**三轮变两轮**：`最近生意咋样` → `★合成 slot=store+time`
  → 答「全部门店 最近30天」→ 执行成功，且**只在两个槽位都缺时触发**
  （「这个月赚钱了吗」仍单问门店，行为不变）。
- **用户一次答两个槽位，现有管线接得住**（intent 稳在 WASTAGE_TOP、数据真实）。
- 最难的 T3 降级用例
  `test_time_then_store_scope_clarifications_chain_without_losing_query`
  **我已改通并验证**：改成一步链、终态断言一字未改（含
  `original_query in resolver_query_seed`），LLM 全程未被调用。

### 还差的两步（这是本文最有用的一段）

1. **会话状态持久化槽位** —— `_pending_put` 写的是数据库表
   `(factory_id, session_key, original_query, clarification_question, created_at)`。
   要加一列 + migration + 部署 apply。⚠️ 老行没有该列，所以**字符串兜底去不掉**，
   `_slot_of_clarification` 必须保留。
2. **具体门店按钮链的合成答案确定性拆解** —— 我只做了「全部门店」那条路，
   具体店名那条会掉进 T3（测试原话：`a concrete store button on an approved
   exact chain must not call T3`）。这是 LLM 全挂时的唯一保障，必须先补。

⛔ **不要直接读 `spec.missing_slot`**（有用例
`test_nothing_reads_the_slot_field_directly_yet` 钉着）：continuation 的 spec 是从
上一轮持久化的**字符串**重建的，那时没有该字段 → None。我一度这么改，
**6088 条用例一条都没抓到**（那两处本就没有行为覆盖）。等第 1 步做完再解禁。

---

## 四、本轮 10 个 PR

| PR | 内容 |
|---|---|
| #2072 | 缺料明细改读后端结构化 DTO（机器人集成 PR，闸此前从没看过它） |
| #2110 | vitest 拆卸期假红 —— 26 个泄漏的 autosave 定时器 |
| #2112 | 「盘点亏了多少」按金额答 + ABS 符号归一 |
| #2116 | 补 #2112 漏掉的 EAV 回填（否则每一项都是 ¥0.00） |
| #2117 | LLM 额度记忆跨进程存活 + 退避递增 |
| #2118 | vue-tsc 进闸 + 全仓自动卸载 |
| #2119 | **越权泄露修复** |
| #2120 | 对抗性审计入仓 |
| #2121 | 反问不再拿字符串当类型系统 |

prod 部署 2 次（Python），各自独立复验。

---

## 五、🔴 本 session 踩的坑（对下一个 chat 最有价值的部分）

八次都是**判据本身出问题**，不是代码写错：

1. **审计工具自己造假缺陷**（缺 sys.path）—— 生产日志 grep 0 次才拆穿
2. **判据把「诚实说没数据」算失败**（假阳性）
3. **改完又算通过** → 空租户会全绿
4. **「非确定性」结论无效** —— 4 次复测里第 2~4 次是**计划缓存重放**，不是独立采样
5. **守卫测试基线数错两次** —— `grep -c` 数的是行不是出现次数；且把我自己写在注释里的示例数了进去
6. **从测试名字推断它守什么** —— `..._without_losing_query` 我以为守「两步链」，
   读了才知道守的是「不丢原始问句 + T3 降级下仍完整」，两步链只是夹具
7. **把 `spec.missing_slot` 当判据** —— 继承路径上它是 None，6088 条用例没抓到
8. **`io.open(p,'w')` 在 Windows 把整档 LF→CRLF** —— `git diff --stat` 显示 2134 行
   而我只改了 30 多行，靠「diff 远大于预期就停手」抓到

**共同形状：看起来证据充分，实际判据在量别的东西。** 变异检验是唯一一次次都管用的
硬手段 —— #2119 拿掉中央闸 9 条红、#2121 拿掉边界函数 2 条**行为**用例红
（第一次只有 1 条自证断言红，说明那 8 处分支原本零覆盖，已补）。

---

## 六、自证命令

```bash
# 对抗性审计（换租户看 --factory；无数据占比 >25% 会告警）
cd backend/python && python -m scripts.restaurant_adversarial_audit --factory MOCK_REST

# Python Gate 原话命令（当前 6089 passed）
EXCLUDES=$(grep -vE '^\s*#|^\s*$' ci-gate-excludes.txt | sed 's/[[:space:]]*#.*$//' \
  | sed 's/^/--ignore=/' | tr '\n' ' ')
pytest tests/ ota/tests efficiency_recognition/tests smartbi/agent/tests \
  smartbi/api/tests smartbi/external_benchmarks/tests smartbi/gold/tests \
  smartbi/ingestion/tests smartbi/services --timeout=60 \
  --ignore=tests/test_data_accuracy.py $EXCLUDES -k "not e2e and not integration"
```

**环境陷阱**：脚本必须放 `backend/python/` 下（部署只同步那里）；环境变量要**两半**
（`.env.prod` 给 LLM key + 服务进程 environ 给 `POSTGRES_*`）；python 的 `open()`
拿不到 MSYS 的 `/tmp`，要 `D:/...`；写回 py 文件用 `newline=''` 否则整档变 CRLF。
