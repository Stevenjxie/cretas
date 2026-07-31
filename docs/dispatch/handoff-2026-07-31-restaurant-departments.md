# 交接：餐饮四部门（2026-07-31 晚）

> **这份交接的写法**：今天这个会话是从「上一份交接把我带偏」开始的 —— 它的诊断、
> 结论、验收标准三样全错，读起来却完全自洽，还让我去「更正」一条本来正确的记忆。
> 所以下面每条都标了 **[实测]** / **[推断]** / **[未验]**。
> **只有标 [实测] 的可以直接当前提用。**

---

## 一、立刻可接的一件事

**财务「全部门店最近30天毛利最低的菜品有哪些」→ `NO_ANSWER`**

- **[实测]** intent 路由是**对的**（`GROSS_MARGIN`），但 `kind != "answer"`，且不带建议。
- **[实测]** 同部门的「哪家店毛利最好」「哪些菜的食材成本最高」「采购花了多少钱」
  三条都能答。所以不是数据缺失，也不是整条链路坏。
- **[未验]** 为什么没答出来。我**没有**查过它的 `kind` 具体是什么
  （clarification? contract 拒绝? resolver 返空?），也没看过 answer_text 原文。
- ⛔ **不要**假设是「毛利最低 = ranking_direction 反向没支持」—— 那只是我的猜测，
  今天已经因为「读到一处就以为看懂机制」返工过一次（见 §五-2）。

**复现命令**（服务器上，约 3 分钟）：

```bash
cd /www/wwwroot/cretas/code/backend/python
PID=$(systemctl show cretas-python -p MainPID --value)
nohup env $(tr '\0' '\n' < /proc/$PID/environ | grep -E '^(POSTGRES|SMARTBI|DASHSCOPE|FOOD_KB|ALIBABA|VOLC|TENCENT|LLM|AI_)' | tr '\n' ' ') \
  PYTHONPATH=$PWD:$PWD/smartbi venv-current/bin/python \
  -m scripts.restaurant_department_audit --factory MOCK_REST > /tmp/a.log 2>&1 &
```

当前基线 **[实测]**：运营 6/6、市场 6/6、财务 3/4、人事 0/2，**合计 15/18**。

---

## 二、今天上线的 16 个 PR

| 组 | PR | 一句话 |
|---|---|---|
| AI 时间窗 | #2076 | 损耗按**请求的**窗口取数（原来恒答滚动 N 天） |
| | #2078 | 换时间范围按钮；闸用 **resolver 签名有无 `date_range`** |
| | #2081 | 领料 / 盘点同病同治；三处窗口逻辑收敛成 `_explicit_window()` |
| 权限 | #2082 | `restaurant` 拆成四个部门模块（天花板 + 细分） |
| | #2083 | 补 `restaurant_chef`；修正上一版比 Java 更严的 7 处 |
| | #2084 | 菜单 `roles` 白名单补三个餐饮角色 |
| | #2087 | 16 个功能页按部门归位，**部门权限才真正生效** |
| 部门页 | #2085 | 路由 / 菜单 / 六区骨架 / 人事空态 |
| | #2086 | 趋势图 + 从数据算出的一句话结论 |
| | #2088 | 端点前缀写错致四页全 404 |
| | #2089 | 取值路径改 camelCase（`transformKeys`） |
| | #2090 | 期间选择器没生效（页头 30 天 / 图表 576 天） |
| | #2091 | 毛利算不出来时显示「—」而非 `0.0%` |
| 审计报告 | #2092 | 按部门的能力审计脚本 + 月度报告恢复后厨三节 |
| 路由 | #2093 | （**没生效**，见 §五-2） |
| | #2094 | 规划表补 `requisition_cost` —— 真正的修复 |

---

## 三、还没做的（按我建议的顺序）

1. **子项目 A · 人事数据地基** —— 人事页现在是诚实空态，要让它有内容需要：
   - **[实测]** `fact_staffing_daypart` 全表 **0 行**（所有租户）
   - **[实测]** `fact_pos_transaction.time` **100% 填充**，小时分布干净（11–14 午市 /
     15–16 下午茶 / 17–20 晚市 / 21 点后无夜宵）→ **时段可从 time 派生，零录入**
   - **[实测]** `meal_period` 与 `staff_id` 两列存在但 **100% 为空** → 在岗人数**无法**
     从 POS 派生，只能录入
   - 所以真要人填的只有两个字段（各时段在岗人数、目标人效）。A2 的录入页是这几块
     里最重的一块。
   - 设计稿：`docs/superpowers/specs/2026-07-31-restaurant-time-window-buttons-design.md`
     旁边那份四部门 artifact（见 §六）

2. **AI 面板 typed blocks** —— 设计已完成未实现。`OpsAnswer.answer_text` 现在是一个
   Markdown blob，KPI 在 `kpis` 里又重复一遍。改造会碰 15 个 resolver，且
   `answer_text` 必须留着（Java / chat / agent runtime 都在渲染它）→ **一段时间内两个
   真相来源**，这正是今天栽过两次的形态。建议等有唯一消费者时再做。

3. **部门页 ⑥ 建议区** —— **[实测]** 没实现。要出真建议得有阈值与归因逻辑；
   没有就只能写死话术，那在数据变了之后会变成假话。

4. **测试套件不稳定** —— **[实测]** web-admin 同一份代码连跑三次报 **4 / 5 / 6** 个
   失败，失败集合还不一样；python 侧 `-k "restaurant or report or template"` 的 clean
   基线就有 18 failed。**「套件绿」目前不是可信信号**，值得单独立项。

---

## 四、环境速查

- **免登录看真页面**：`http://139.196.165.140:8086/demo` → 选「餐饮工厂演示」→
  再改 URL 到 `/restaurant/{ops,marketing,hr,finance}`。
  ⚠️ 与官网 `cretaceousfuture.com/demo` 不是一回事（那是营销页，直接访问会
  `ERR_TOO_MANY_REDIRECTS`）。
- **Playwright MCP**：`navigate` 后要 `wait_for {time:5~6}`（SPA 首屏）；
  `take_screenshot` **只返回路径**，要再用 Read 读那个文件；文件落在**当前工作目录**
  而不是 worktree。
- **浏览器里发探针**：token 在 `localStorage.cretas_access_token`，租户在
  `cretas_user.factoryUser.factoryId`，python 接口要加 `/smartbi-api` 前缀。
  **这三处我都先弄错过一次**，每次都报出误导性的 401/404。
- **部署**：`LC_ALL=C ./scripts/deploy/deploy-smartbi-python.sh --env prod` /
  `deploy-web-admin.sh --env prod --confirm-prod YES-PROD`
- **db-credentials.md 的 `SMARTBI_DB_PASSWORD` 已失效** —— 06-26 之后又轮换过。
  取真值：`tr '\0' '\n' < /proc/$(systemctl show cretas-python -p MainPID --value)/environ | grep POSTGRES`

---

## 五、今天用错误换来的判据

1. **「换个窗口问数字变了」不能证明日期范围生效。** 三个金额不同只因窗口**长度**不同
   （30/31/7）。判据是拿答案对**直查数据源的日历值**，并把 rolling 版并排算出来。

2. **只改准入白名单 ≠ 改决策表。** #2093 把指标加进
   `_CONTRACT_REPAIRABLE_METRICS`，单测绿、上线、prod 复跑 —— **还是错**，只是换了个
   错法。真根因在下一层 `_plan_requested_intents`。**我读到一处就以为看懂了机制。**

3. **一个闸往往由多处独立承载。** 餐饮部门权限有三个（模块矩阵 / 菜单白名单 /
   路由 meta），返工四次才真正生效。写**横跨两个承载点**的用例。

4. **阳性对照。** grep 到 0 / 收到 401 之前，先拿一个**你确定一定有**的东西并排试。
   今天救了我至少五次（三次探针写错、两次核部署产物查错文件）。

5. **别用嵌套转义写补丁脚本。** 今天栽五次（`\n` 被吃掉、长 CJK 字符串把 heredoc
   撑爆）。长文本用 Edit 工具，不要 python heredoc。

6. **改既有测试的期望值前，先问「旧期望是不是在编码缺陷本身」。** 今天两次拆封都
   先在真实租户上验过才动（报告模板那次专门用**报告自己的问句形态**验，因为
   「上个月」是另一条日期解析路径）。

---

## 六、相关产出

- **artifact**（4 份，最后一份最接近现状）：
  - 已上线实况 https://claude.ai/code/artifact/19fe7a63-aefd-4167-b312-b9b3b6a1741b
  - AI 面板重设计（**未实现**）https://claude.ai/code/artifact/c1d8ff45-7165-4926-b9f4-300c5bb32dbc
- **memory**：`feedback_only_visible_when_you_open_the_page.md` /
  `feedback_gate_has_more_carriers_than_you_checked.md` /
  `reference_prod_demo_entry_for_visual_check.md` /
  `feedback_differing_numbers_do_not_prove_window_applied.md`
