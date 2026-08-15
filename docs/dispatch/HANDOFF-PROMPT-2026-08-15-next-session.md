# 交接 Prompt —— 直接粘给下一个 session

> 复制下面整段（含分隔线之间的内容）作为新会话的第一条消息。

---

你接手 Cretas（白垩纪食品溯源）2026-08-15 那一轮的后续。先读这三份，别急着动手：

- `docs/dispatch/handoff-2026-08-15-web-rn-e2e-and-dead-paths.md`（上一轮全貌）
- `docs/testing/e2e-environment-and-credentials.md`（怎么跑测 + 四个会让读数作废的坑）
- `docs/dispatch/2026-08-15-sheet-findings-product-decisions.md`（Google Sheet 评估结论）

## 你的任务（按优先级）

1. **Google Sheet 里还没核实的三项**：
   - 采购订单新建 409 —— **已定位**：`PurchaseServiceImpl` 约 307 行的 60s 防双击幂等闸，
     键是 `(工厂, 供应商, 买手, 60s)`，**没有内容维度** → 同一供应商连续下两张不同的单会被误拦。
     要判断：是收紧键（加内容哈希/客户端幂等键），还是放宽窗口。这是产品决策，先评估再动。
   - 新建计划产品类型无法滑动（RN 交互）
   - 个人 OA 模块
2. **采购「财务已审核」的后半步**（owner 未拍板）：上一轮只让审计痕迹说实话 ——
   没有财务节点就不盖章、界面显示「无需财务审核（未设置审批节点）」。**状态故意没动**，
   因为收货门禁认的就是 `FINANCE_APPROVED`（`PurchaseServiceImpl:1679`），
   改状态会把 LIUSHANMEN 的采购收货整条堵死。若要真正要求财务审核，需要给没有财务节点的
   工厂加节点或拆状态，代价是 LIUSHANMEN 采购多一步。
3. `.env.test.example` 里 `factory_admin1` 等 F001 账号是**死值**（迁移 `V20261029_68` 删了 F001），
   建议清理模板。

## 环境（照抄即可）

```bash
# 全部指向生产
export E2E_BASE_URL=https://admin.cretaceousfuture.com
export TEST_FACTORY_ADMIN_USER=f006_admin
export TEST_FACTORY_ADMIN_PASS=<向 owner 要，别写进仓库>

cd web-admin
npx playwright test --project=web-admin-e2e --no-deps --workers=1

# RN 两个套件要先起 Expo，且**必须换一个 env 文件名**（见坑③）
cd frontend/CretasFoodTrace
ENVFILE=.env.prod.local npx expo start --web --port 3010 --clear
```

基线：**7 个套件 174 passed / 18 skipped / 0 failed**。跑出别的数先怀疑环境，不要先怀疑代码。

## 硬约束

- **写入只落 F006**（团队测试租户）。⛔ **LIUSHANMEN 是真客户**，只读；要写走
  `scripts/e2e/production-readonly` 的白名单。
- 任何代码工作**开独立 worktree，off `origin/main`**；prod 只从 main 部署。
- 部署判据：`DEPLOY_EXIT=0` **且** 日志里 `RELEASE_FINAL_STATUS` 恰好 1 次。
- 不要动 VPN / Clash / WireGuard / 代理节点 / SSH 配置。预热提示
  `PREWARM=skipped reason=artifact_transport_unreachable` 是**正常的**，脚本会退回本地构建。
- 不创建账号、不处理明文口令、不在登录表单里输密码。

## 会让你白干一轮的七个坑（都是上一轮实际踩的）

| 长相 | 真相 | 正确判据 |
|---|---|---|
| `curl :3010` → 200 | 旧 Expo 实例（新进程因端口占用直接退出） | 问监听端口的 **pid 和启动时间** |
| 改了 `.env` 重启、日志正常 | babel 缓存键是文件**名**不是内容，bundle 里还是旧地址 | **grep 产物**，不信配置文件 |
| 「测试环境 `:8086`」 | 它连的是 **`cretas_prod_db`** | 问 `/proc/<pid>/environ` |
| 各套件读数轻微不一致 | 跑到一半切了分支 → **同一轮跑了两份代码** | 跑测前 `git checkout --detach <sha>` 固定工作树 |
| 幂等接口返 `created=1` | 是回放，不是又建了一条 | 看 `idempotentHit` + 查库 |
| `grep -c $'\r'` 报 0 | **这个仪器是坏的** | `xxd` 看字节 + 两种 `--stat` 口径对比 |
| 线上 bundle grep 命中 0 | 只搜了主入口，改动在懒加载 chunk 里 | 查**部署制品本体**，且带对照串 |

共同形态：**读数完全正常，但量的不是你以为的那个东西。**

## 三条来自实战的判据

1. **搜索面窄一格就会得出相反结论**。上一轮三次：只搜单引号漏了双引号写法；
   只查 `MaterialReceipt` 漏了 `MaterialReceiptAI`；只查 `screens/` 漏了 `store/`。
   判「某个屏可不可达」要把 `src/` 全铺开搜，再查一层「引用它的那个东西自己有没有人用」。
2. **路由的真相是导航器里的 `name="X"`，不是 `types/navigation.ts`** ——
   有 5 个路由注册了却没写进类型文件。
3. **每条正向断言配一条能让它红的对照**。上一轮有条用例叫
   「exports role action sets with **real navigation targets**」，实际只断言 `screen.length > 0` ——
   恒真式，所以它没能拦住「指向一个已删的屏」。

## 已有的闸（改到相关代码时别绕过）

- `NoDisabledBatchCreateEndpoint` —— 不许再调停用的 `materialBatchApiClient.createBatch`
- `MobileToolPromptsDoNotAskForIds` —— 手机端 Tool 追问不许要 ID
- `quickActionsStore`「real navigation targets」—— 快捷操作必须指向真注册过的路由
- `AppPrompt` / `AppChoose` —— 跨平台弹窗（RN 的 `Alert.*` 在 web 上不渲染）

## 干活方式

有问题直接修、提交、部署，不用问我；**产品决策**先出长期方案并 mark，最后一起审。
报结论前先问自己一句：**我想知道的 X 和我实际在量的 Y 是不是同一个东西。**

---
