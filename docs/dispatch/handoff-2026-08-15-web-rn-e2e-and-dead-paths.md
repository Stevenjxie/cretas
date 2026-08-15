# 交接 2026-08-15 —— web/RN E2E 打通 + 四条「点了必失败」的路径

**范围**: web E2E 全绿 → RN(Expo web) 报工/仓管流程 → Google Sheet 反馈评估 → 四件 owner 拍板项
**环境**: 全部指向**生产** `https://admin.cretaceousfuture.com`，账号 `f006_admin`
**写入租户**: 仅 **F006**（团队测试租户）。已核实 7 个套件无一处引用真客户 **LIUSHANMEN**

---

## 一、已上线（合入 main，后端已部署 prod 并验证）

| PR | 内容 | 验证 |
|---|---|---|
| #2644 | AI 追问不再向手机端操作员要 UUID | prod 接口实测：「请提供原材料类型ID」→「请问是哪个供应商？说名称就行」 |
| #2645 | 报工驳回/采购备注/换工种在 **Android 上是死按钮**（`Alert.prompt` 只有 iOS 有） | 5 条测试 + 棘轮闸 |
| #2650 | 质检照片一张都存不下来（四处各断一次） | 接上已有附件子系统 |
| #2651 | **上架是「假成功」** —— 不调任何接口就弹「上架成功」 | 写入→独立读回 |
| #2660 | 财务审核只有财务真批过才盖章 | jar 含 `findFinanceApproval`×3；前端 chunk 含「无需财务审核」 |
| #2659 | 删四条打停用端点（409）的入库路径 + 棘轮闸 | |
| #2661 | 驳回原因改标准选项 + 退役已无后端的工序段屏 | |
| #2642 / #2652 / #2653 | web/RN E2E 基建（API 基址、登录态、403 判据、工厂跟随会话、会话注入、账号环境变量化） | |
| #2648 / #2655 / #2657 | Google Sheet 评估结论 + 环境与判据文档 | |

**最近一次部署**: `DEPLOY_EXIT=0`，`RELEASE_FINAL_STATUS` 恰好 1 次 = `deployed`。

---

## 二、测试现状

**生产环境全量 174 passed / 18 skipped / 0 failed**（7 个套件，单 worker）

| 套件 | 起点 | 现在 |
|---|---|---|
| web-admin-e2e | 70 passed（**其中一批在 403 页上假绿**） | 60 / 11 skip |
| web-admin-crud | 2 failed | 19 / 1 skip |
| web-admin-workflows | 4 failed | 19 / 1 skip |
| phase2-verify | 6 failed | 18 |
| liushanmen-e2e | 8 passed + **7 did-not-run** | 16 / 1 skip |
| liushanmen-rn-e2e | **一条都跑不起来** | 21 / 1 skip |
| rn-expo-web | 1 failed | 21 / 3 skip |

跑法见 `docs/testing/e2e-environment-and-credentials.md`。

---

## 三、仍需 owner 决定 / 未做

1. **采购「财务已审核」的后续**：本轮只让审计痕迹说实话（没有财务节点就留空 + 界面显示
   「无需财务审核（未设置审批节点）」）。**状态没动** —— 收货门禁认的就是 `FINANCE_APPROVED`。
   若要真正要求财务审核，需给没有财务节点的工厂加节点或拆状态，**代价是 LIUSHANMEN 采购收货多一步**。
2. **Google Sheet 剩余未核实项**：采购订单新建 409（已定位是 60s 防双击幂等闸，键只含
   供应商+买手+时间、**无内容维度** → 同一供应商连续下两单会被误拦）、新建计划产品类型无法滑动、个人 OA 模块。
3. `.env.test.example` 里 `factory_admin1` 等 F001 账号是**死值**（迁移 `V20261029_68` 删了 F001），建议清理模板。

---

## 四、下一个人必须知道的环境事实

- **`139.196.165.140:8086`「测试环境」连的是 `cretas_prod_db`** —— 在那儿跑写入型用例，数据落生产库。
- **导航按角色在根部分叉**（`AppNavigator`）：仓储角色走 `WarehouseManagerNavigator`/`WarehouseWorkerNavigator`，
  其余走 `MainNavigator → ProcessingStack`。**两棵树互斥**，跨树 navigate 无效。
- **路由的真相是导航器里的 `name="X"`，不是 `types/navigation.ts`** ——
  实测 `PlanCreate`/`PlanList`/`PersonnelSchedule`/`SmartBI`/`ProductionLine` 注册了却没写进类型文件。
- RN 的 `Alert.*` 在 react-native-web 上**不渲染**，用 `appAlert`/`appPrompt`/`appChoose`。

---

## 五、本轮留下的闸（都做过变异验证）

| 闸 | 守什么 | 自保 |
|---|---|---|
| `NoDisabledBatchCreateEndpoint` | 不许再调停用的 `createBatch` | 先断言扫到 200+ 文件；剥注释 |
| `MobileToolPromptsDoNotAskForIds` | 手机端 Tool 追问不许要 ID | 先断言扫到 40+ 文件；白名单 7 条 |
| `quickActionsStore`「real navigation targets」 | 快捷操作必须指向**真注册过的路由** | 先断言扫到 100+ 路由 |
| `AppPrompt` / `AppChoose` | 跨平台弹窗行为 | 每条正向断言配对照 |

---

## 六、方法上的教训（这轮实际拦住我的东西）

**共同形态：读数完全正常，但量的不是我以为的那个东西。** 一晚踩了七次：

| # | 长相 | 真相 | 正确判据 |
|---|---|---|---|
| 1 | `curl :3010` → 200 | 旧 Expo 实例（新进程因端口占用退出） | 问 pid 和启动时间 |
| 2 | 改 `.env` 重启，日志正常 | babel 缓存键是文件**名**不是内容，bundle 没变 | **grep 产物** |
| 3 | 「测试环境」 | 连的是生产库 | 问 `/proc/<pid>/environ` |
| 4 | 各套件兜底次数 0/1/1/1/2 | 跑到一半我切了分支，**同一轮跑了两份代码** | 旁证计数 + 跑测时固定工作树 |
| 5 | `created=1` | 幂等回放，不是又建了一条 | 打印 `idempotentHit` + 查库 |
| 6 | `grep -c $'\r'` 报 0 | 仪器坏了，文件确实被转成 LF | **xxd 看字节** + 两种 `--stat` 口径 |
| 7 | 线上 bundle 命中 0 → 「前端没上」 | 只 grep 了主入口，改动在懒加载 chunk 里 | **查制品本体**，且带对照串 |

另有三次「搜索面太窄」：只搜单引号漏了双引号写法、只查 `MaterialReceipt` 漏了 `MaterialReceiptAI`、
只查 `screens/` 漏了 `store/`。**三次都是工具（类型检查/棘轮闸）抓住的，没有一次是我自己看出来的。**

判据沉淀在 `docs/testing/e2e-environment-and-credentials.md` 与
`docs/dispatch/2026-08-15-sheet-findings-product-decisions.md`。
