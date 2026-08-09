# 交接 · 画布落库 + 「模型的复述」这一类缺陷（2026-08-09 第三轮）

> **PR**: https://github.com/Stevenjxie/cretas/pull/2414 （分支 `feat/canvas-patch-write`，9 commits）
> **worktree**: `C:/Users/Steve/cretas-canvas-write`（off `origin/main` @ `6f84f6cb04`）
> **状态**: 待合并。`merge-tree` 零冲突；`origin/main` 期间前进 30 commit，**一个都没碰改动的三个文件**。

---

## 0. ⛔ 读这份交接之前先做一件事

```bash
git rev-list --count HEAD..origin/main    # 不是 0 就先对齐基准
```

**本轮最贵的教训**：项目主目录 `my-prototype-logistics` 当前分支落后 `origin/main`
**30 个 commit**，我在它上面量了一整轮，说错四件事（详见 §4）。
和「查错数据库」是同一条判据的两种形态 —— 两次都**感觉不到自己在别处**。

---

## 1. 这支做了什么

| 层 | 状态 |
|---|---|
| **前端修复** | ✅ **合了就生效** —— AI 建流程不再用模型复述覆盖已配好的调料克数 |
| **后端落库** | 开关 `cretas.ai.canvas-workflow-write.enabled` **默认关**，两道闸 |
| **网关 `DECLINED`** | 纯增量 —— 干净的拒绝不再被记成疑似写入的脏账 |
| **防复发的闸** | `scripts/tests/check-ai-wholesale-writes.py`，601 工具扫出 1 处（已修那个） |

53 tests / 0 failures（后端）+ 380 tests / 0 failures（前端该目录），每条承重断言都变异验证过。

---

## 2. 🔴 贯穿全轮的那一个形状（撞了三次）

> **写下去的应该是存着的真值，不是模型的复述。**

1. **后端**：分流闸判的是**补丁清单**，而 `saveDraft` 写的是 **AI 重发的整张图** ——
   「把工序改个名」（只能用 `UPSERT_NODE`，整节点替换、清洗器不许带调料字段）
   会**静默清空该工序全部调料克数**，还回 `applied:true`。
2. **前端**：`buildWorkflowFromSpec` 从零重建整张图，`materialBindings` 取自
   **LLM 复述的 spec** —— 模型把 12.5 说成 12、或漏述另外 21 道工序的调料，
   都**不是无效行**，`seasoningRejections` 抓不到。
3. **我自己写的闸**：第一版按方法切，**0 命中** —— 而它抓不到自己为之而写的那个缺陷
   （`convertValue` 在一个方法、`saveDraft` 在另一个）。

> 📌 **一道报 0 的闸，必须先证明它抓得到已知实例** —— 否则「0 命中」读起来就是「全都干净」。
> 现在 `check-ai-wholesale-writes.py` 有 `KNOWN_INSTANCE` 地面真相自检，扫不到就 `exit 3`。

---

## 3. ⛔ 打开那个开关之前

洞**已经堵上了**（`belongsToStoredProduct` 用库里那张图的节点 id 判归属，
不需要往 context 塞 productTypeId）。仍然默认关，理由变了：

> **这条链没有一次真人端到端走过**：agent 出补丁 → 落草稿 → 人在页面看到 → 人发布。

本支全是单元级验证。⛔ 打开前先在 test 环境用真实产品走一遍 —— 单元全绿不等于这条链通。

```
CRETAS_AI_CANVAS_WORKFLOW_WRITE_ENABLED=true
```

---

## 4. ⚠️ 我在这一轮说错、后来更正的（下一轮别照抄旧结论）

| 我说过 | 真实（`origin/main`） |
|---|---|
| 「补丁语言里没有辅料/包材的 key」 | **早就有**：`UPSERT_MATERIAL_BINDING` / `materialBindings` / `dosagePerKgG` / `subsequentPotRatio` |
| 「BOM/工序融合：设计拍板、代码零改动」 | **大面积做完**：编辑器 3435 → **4826** 行；辅料 41 / 包材 42 / 锅序 3 命中 |
| 「`BomController` 人工/均摊 20 处还在」 | **0** —— 端点已下线，**整个 BOM 页文件都不存在了** |
| 「这个工具的 `execute()` 根本没有调用方」 | **错**：`DefaultToolExecutionGateway:373` 会泛化调它 |

前三条同一个根因（在落后 30 commit 的工作树上量）。

⚠️ **更要紧的反向教训**：发现落后之后我更正了**行号**，
却没有回头重问一遍「我基于旧代码下过的那些**结论**呢」。
📌 **基准错了，要重扫的是所有基于它的结论，不只是当前这次引用。**

✅ 救回来的是 **worktree** —— 项目规则要求 off `origin/main` 开，
建的那一刻文件行数对不上才暴露整件事。
⇒ **worktree 隔离的附带价值：它是一次强制的基准对齐。**

---

## 5. 当前画布 AI 的真实架构（⛔ 别再照「补丁路」理解）

```
厂长说话 → LLM 只产出【语义规格】(rawMaterials + steps)
        → 前端【确定性编译器】buildWorkflowFromSpec 生成图
        → 用户在画布上看、改、保存（走正常 UI）
```

源码注释原话：「**弃补丁**: 让 LLM 只产出语义规格，前端确定性编译成图」
「图的合法性由**前端编译器**保证（端口/边/id 全代码生成）」。

`CanvasAIController` 的两条路都不调这个工具的 `execute()`：
`/chat` 走 LLM 出规格（第 361 行取的 `executor` 是**死变量**）；
`/apply-diffs` 在第 620 行**显式排除**它。
⇒ 补丁路只在**通用网关**那条线上活着 —— 这正是那个默认关的开关守的地方。
