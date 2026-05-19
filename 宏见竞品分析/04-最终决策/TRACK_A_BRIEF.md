# TRACK A BRIEF — Phase 0 Canvas 死代码修复

> ⚠️ **DEPRECATED 2026-05-19** — 本文件已 supersede by `31-DEEP-RE-AUDIT.md §P` (Round 11 deep re-audit). Sprint 1+2 track 已全 ship, 决策面 status 请直接看 31-doc + `30-BACKLOG-STATUS-AUDIT.md §13`. 本文件保留作为 historical reference (Sprint 1+2 时点 brief / 计划 / dispatch).


> **接收方**: Chat 2 (Track A worker)
> **派发方**: Chat 1 (Organizer)
> **派发日期**: 2026-05-14
> **预期完成**: ~5-6 工作日 (名义 9d, Claude 加速 ~1.7x)
> **PR 命名**: `[Track-A] N# 编号 项目名`
> **STATUS 文件**: `宏见竞品分析/04-最终决策/STATUS/TRACK_A_STATUS.md` (你需要自己创建)
>
> **本文件原则**: 完全 self-contained。你不需要任何额外 context 就能动手干活。

---

## §1 项目 Onboarding (新人入门)

### Cretas 是什么

**Cretas Food Traceability System (白垩纪食品溯源系统)**
- 后端: Java 21 + Spring Boot 3.2.12 + PostgreSQL + JPA (Hibernate 6), 端口 10010
- 前端: Expo 53+ + TypeScript + React Navigation 7+ (React Native), 端口 3010
- AI 服务: Python + FastAPI + LLM API, 端口 8083
- 项目状态: Phase 3 核心完成 (82-85%)

源码位置: `C:\Users\Steve\my-prototype-logistics\`
- Java 后端: `backend/java/cretas-api/`
- RN 前端: `frontend/CretasFoodTrace/`
- Python 服务: `backend/python/`

### 当前业务背景

**客户**: 六扇门 (F006) 卤制品工厂 — ASAP 1.5 月交付 P0 修复
**销售背景**: Cretas 团队在跟客户演示时, 销售在 demo 中说 Canvas / AILayoutAssistant "AI 一句话改首页" / PageEditor "拖拽建表"。审计 (`AUDIT_Y_CANVAS.md`, 2026-05-14) 发现这些功能要么 **dead code** 要么 **fake AI** 要么 **写错数据库表**。

### 你是谁

**你 = Chat 2 = Track A worker**。Sprint 1 ASAP 有 4 个并行 chat (Track A-D):
- **Track A (你)**: Phase 0 Canvas 死代码修复 (9d)
- Track B (Chat 3): AI 钉钉机器人 + 抄码品 + PDF 扫码 (12d)
- Track C (Chat 4): Attachment + 单据打印 + 三价 + RBAC (11d)
- Track D (Chat 5): BOM + 工序 + 生产 bug (16d)

你只关心 Track A 自己的工作。其他 track 跟你无关 (除非你跟 Organizer ping)。

### 沟通方式

- **不要在本 chat 跟 organizer 战略讨论** — 战略已定, 你只执行
- **每日在 STATUS 文件追加 1 段** (格式见 §7)
- **完成一个项目 → 推 PR → ping organizer review**
- **碰到 blocker 立即在 STATUS 报, 不要自己卡死**

---

## §2 任务范围与工时

### 三个项目 (按执行顺序排)

| 项目 | N# 编号 | 工时 | 优先级 | 客户感知 |
|---|---|---|---|---|
| **1. AILayoutAssistant 接真 LLM** | C-CANVAS-AI | 4d | P0 | 销售红线 2 解除 ("真 AI") |
| **2. PageEditor 挂导航** | C-CANVAS-PAGE | 2d | P0 | 解锁 "对话生成仪表盘" 演示 |
| **3. Canvas Tool Repository 统一** | C-CANVAS-REPO | 3d | P0 | Tool 输出端到端可见 |

**总工时**: 9d (名义) → Claude 加速预期 ~5-6 工作日

### v2 销售红线 (修完后解禁)

修完这 3 项后, 销售可以说:
- ✅ "AILayoutAssistant 是真 AI" (修完项目 1 解禁)
- ✅ "PageEditor 拖拽建首页" (修完项目 2 解禁)

**仍禁** (Phase 0 不修, 后续 Sprint 做):
- ❌ "拖拽生成业务表单" (FAHomeScreen renderer 是 5-type 硬编码 switch, 不支持 form_*)
- ❌ "通用对话生成销售仪表盘" (运行时通用渲染器不存在)

### 工时不达标怎么办

- 名义 9d 是上限。Claude 加速通常 1.7-2x → 实际预期 5-6 工作日
- 如果某项目工时 >> 1.5 倍名义 (例如 AILayoutAssistant 超过 6d), 立即在 STATUS 报 organizer
- Organizer 会决定: 减 scope / 拉外援 / 让你跳到下一项

---

## §3 文件 Ownership (防冲突)

### 你的 (Track A 独占, 你可以随便改)

```
backend/java/cretas-api/src/main/java/com/cretas/aims/
├── service/decoration/impl/DecorationServiceImpl.java   ← 项目 1 主战场 (line 207)
├── ai/tool/impl/pagedesign/                              ← 项目 3 涉及 (4 个 Tool)
│   ├── PageGenerateTool.java
│   ├── PageComponentAddTool.java
│   ├── PageStyleUpdateTool.java
│   └── PageDataBindTool.java
└── ai/tool/impl/decoration/                              ← 项目 3 主战场 (3 个 Tool)
    ├── HomeLayoutGenerateTool.java
    ├── HomeLayoutUpdateTool.java
    └── HomeLayoutSuggestTool.java

frontend/CretasFoodTrace/src/
└── screens/lowcode/                                      ← 项目 2 主战场 (PageEditor.tsx)
    ├── PageEditor.tsx
    ├── components/ComponentPalette.tsx
    ├── components/...
    └── index.ts
```

### 共享只读 (改之前必须 ping organizer)

```
backend/java/cretas-api/src/main/java/com/cretas/aims/
├── entity/BaseEntity.java                                ← 跨 track 共用, 别动
├── service/impl/IntentExecutorServiceImpl.java          ← AI 意图核心路由
└── ai/tool/AbstractBusinessTool.java                    ← Tool 基类

frontend/CretasFoodTrace/src/services/api/aiApiClient.ts ← 跨 track 共用
CLAUDE.md                                                 ← 项目规范文件
.claude/rules/                                            ← 规则文件
```

### 别 track 的 (绝对不准碰)

- Track B: `backend/.../service/dingtalk/`, `frontend/.../screens/shared/LabelScanScreen.tsx`
- Track C: `backend/.../entity/Attachment.java`, `frontend/.../screens/smartbi/`
- Track D: `backend/.../entity/bom/`, `backend/.../service/workprocess/`

### 你可能需要参考的 (只读, 不改)

```
frontend/CretasFoodTrace/src/screens/factory-admin/home/
├── HomeLayoutEditorScreen.tsx          ← 已挂导航的对照参考
├── components/AILayoutAssistant.tsx    ← 项目 1 前端调的就是这个 UI
├── components/BentoGridEditor.tsx      ← 真实拖拽实现, 参考
└── FAHomeScreen.tsx                    ← 运行时渲染器, switch 5 类

frontend/CretasFoodTrace/src/navigation/factory-admin/
├── FAManagementStackNavigator.tsx     ← 项目 2 可能挂这里
└── FactoryAdminTabNavigator.tsx       ← 顶层 tab

backend/java/cretas-api/src/main/java/com/cretas/aims/
├── ai/client/PythonLLMClient.java     ← 项目 1 要接的就是这个
├── ai/client/DashScopeClient.java     ← Tool 已在用的另一个 LLM client
└── entity/decoration/FactoryHomeLayout.java  ← 项目 3 统一到这张表
```

---

## §4 Day-by-Day 执行计划

### Day 1-4: AILayoutAssistant 接真 LLM (项目 1, C-CANVAS-AI)

#### 病灶定位

文件: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/decoration/impl/DecorationServiceImpl.java`

第 162-210 行的 `generateLayoutWithAI(String factoryId, AILayoutRequest request)` 方法:
- **第 168 行** 调 `generateModulesFromRequest(request)` — 这是一个规则方法, 根据 `priorityModules` 排序, **完全没调 LLM**
- **第 207 行** 显式 `modelUsed("rule-based")` — 自我承认是规则模板, 不是 AI
- **第 524 行** `generateModulesFromRequest()` 实现是按 `priorityModules` 数组顺序排, 加主题调色板调色

前端 `AILayoutAssistant.tsx` (Modal 打字机效果 + 3 StylePreset + 4 QuickCommand) 看着像 ChatGPT, 后端却是骗子。

#### Day 1: 读代码 + 设计 prompt template

任务:
1. 完整读 `DecorationServiceImpl.java` 全文 (~700 行), 理解:
   - `generateLayoutWithAI()` 输入: `AILayoutRequest { prompt, gridColumns, timeBasedEnabled, priorityModules, stylePreference }`
   - 输出: `AILayoutResponse { layout, explanation, generationTimeMs, modelUsed, suggestions }`
   - `layout` 类型: `HomeLayoutDTO { factoryId, modules: List<ModuleConfig>, theme, gridColumns, status, version, aiGenerated, aiPrompt, timeBasedEnabled }`
   - `ModuleConfig` 字段 (grep `HomeLayoutDTO.ModuleConfig` 找到完整定义)
2. 读 `AILayoutAssistant.tsx` 前端调用代码 (找 `decorationApiClient.generateLayoutWithAI`), 确认 request/response 字段名 (camelCase 已经统一)
3. 读 `PythonLLMClient.java` (`ai/client/PythonLLMClient.java`), 找到 `call(...)` 方法签名 — Java 已有的 LLM 调用客户端
4. 也读 `HomeLayoutGenerateTool.java` 的 prompt 构造 (`buildLayoutGeneratePrompt()`), 那里已经有一个生产级的 prompt template
5. 设计你的 prompt template:
   - **System prompt**: 介绍 Cretas 首页布局场景, 列出 5 个允许的 module type (`welcome` / `ai_insight` / `stats_grid` / `quick_actions` / `dev_tools`), 列出 4 种允许的尺寸 (1x1/2x1/1x2/2x2), 要求严格 JSON 输出
   - **User prompt**: 拼 `request.prompt` + `request.priorityModules` + `request.stylePreference` + `request.gridColumns`
   - **Output schema** (JSON):
     ```json
     {
       "modules": [
         {"id": "welcome", "type": "welcome", "x": 0, "y": 0, "w": 2, "h": 1, "visible": true, "props": {}}
       ],
       "theme": {"primaryColor": "#1890ff", "mode": "light"},
       "explanation": "我把 AI 洞察移到了顶部, 因为..."
     }
     ```

**Day 1 产出**: prompt template 文档 + 代码骨架 + 你写在 STATUS 的设计说明

#### Day 2: 接 PythonLLMClient + 跑通

任务:
1. 在 `DecorationServiceImpl` 里 `@Autowired private PythonLLMClient pythonLLMClient;`
2. 把 `generateLayoutWithAI()` 改成:
   ```java
   long startTime = System.currentTimeMillis();
   String systemPrompt = buildLayoutGeneratePrompt();   // 类比 HomeLayoutGenerateTool
   String userPrompt = buildUserPrompt(request);
   String llmResponse = pythonLLMClient.chatLowTemp(systemPrompt, userPrompt);  // 或对应的同步方法
   ParsedLayout parsed = parseLayoutJson(llmResponse);
   List<ModuleConfig> modules = parsed.getModules();
   ThemeConfig theme = parsed.getTheme();

   // 验证布局合法性 (复用 LayoutValidator)
   if (!layoutValidator.validate(modules).isValid()) {
       log.warn("LLM 生成布局验证失败, 降级到规则布局");
       modules = generateModulesFromRequest(request);  // 保留旧逻辑做 fallback
   }

   // 保存到数据库 (现有逻辑)
   ...

   return AILayoutResponse.builder()
       .layout(layout)
       .explanation(parsed.getExplanation())
       .generationTimeMs(System.currentTimeMillis() - startTime)
       .modelUsed("qwen-flash-aliyun-b")  // ← 真实模型名, 从 PythonLLMClient 配置读
       .suggestions(generateDesignSuggestions(request))
       .build();
   ```
3. **关键**: 保留 `generateModulesFromRequest()` 作为 LLM 失败时的 fallback, 不要删
4. JSON 解析失败 / LLM 超时 / 验证失败 → 降级到规则布局, 但日志 `log.warn` 记录
5. 本地跑通 (mvn spring-boot:run): 用 `curl -X POST localhost:10010/api/mobile/{factoryId}/decoration/home-layout/ai-generate -d '{"prompt": "把AI洞察移到顶部"}'`

**Day 2 产出**: LLM 真接通, 本地能 curl 看到真 AI 输出

#### Day 3: 边界处理 + 错误处理

任务:
1. **边界 case**:
   - LLM 返回非 JSON → 报错, fallback 规则
   - LLM 返回 JSON 但 schema 不匹配 → fallback 规则
   - LLM 返回的 module type 不在 5 个允许列表 → 过滤掉无效 module, 用 fallback 补齐
   - LLM 返回的 x/y/w/h 超出 grid 范围 → fallback 规则
   - LLM 超时 (>30s) → fallback 规则, log.warn
2. **错误处理**:
   - **禁止降级处理** (CLAUDE.md 核心原则): 如果 LLM 完全不可用, 不要返回假数据, 应该返回 error response with clear message
   - 但 "LLM 生成不合法 → fallback 规则" 算合理的二级降级, 因为规则布局本身是有效结果
   - 区分: LLM 失败 + 规则 fallback = OK; 规则也失败 = error response
3. **写单元测试**: mock `PythonLLMClient`, 测试 4 种边界
4. **集成测试**: 真实 LLM 调用, 测试 3 个 prompt:
   - "生成简洁的管理首页"
   - "我需要重点看数据统计"
   - "把 AI 洞察移到顶部"

**Day 3 产出**: 边界 case 全覆盖, 单测 + 集成测试通过

#### Day 4: E2E 测试 + PR

任务:
1. 启动前端 (frontend/CretasFoodTrace) `npm start`
2. 启动后端 (backend/java/cretas-api) `mvn spring-boot:run`
3. 登录 factory_super_admin 账号, 进 FAHome → 长按 → HomeLayoutEditor → 打开 AILayoutAssistant Modal
4. 输入 4 个 prompt 验证真智能:
   - "把 AI 洞察移到顶部" — 验证 AI 洞察 y=0
   - "我只要 3 个模块" — 验证返回 3 个 visible=true
   - "深色主题" — 验证 theme.mode=dark
   - "重点看数据" — 验证 stats_grid w=2 h=2
5. 保存 → 发布 → 退出 → 重进 FAHome → 验证布局生效
6. 撤销/重做 → 验证不会破坏 AI 输出
7. 录 30s GIF (用 Expo Web 或截图序列)
8. 推 PR: `[Track-A] C-CANVAS-AI AILayoutAssistant 接真 LLM`
9. PR body 含: 涉及文件 / 测试方式 / Before/After 对比 (modelUsed 从 "rule-based" → "qwen-flash-aliyun-b") / 风险点 (LLM 超时降级)

**Day 4 产出**: PR 推送, 等 organizer review

---

### Day 5-6: PageEditor 挂导航 (项目 2, C-CANVAS-PAGE)

#### 病灶定位

文件: `frontend/CretasFoodTrace/src/screens/lowcode/PageEditor.tsx` (1252 行)

完整设计器:
- 左: ComponentPalette (6 大类 15 个组件硬编码)
- 中: 2 列 BentoGrid Canvas
- 右: 属性面板
- 底: 撤销重做 + 状态徽章
- 支持 5 种 pageType: home / dashboard / list / detail / form

**问题**: grep `PageEditor` 全仓 → 只有 `lowcode/index.ts` re-export 和测试, **0 个 navigation 文件引用**。1252 行烂在仓库里。

#### Day 5: 看 navigator + 加路由

任务:
1. 读 `frontend/CretasFoodTrace/src/navigation/factory-admin/FAManagementStackNavigator.tsx` (Track A 你的 ownership) — 看现有 Stack 怎么注册 Screen
2. 读 `frontend/CretasFoodTrace/src/types/navigation.ts` — 找 `FAManagementStackParamList` 类型, 看怎么加新路由
3. 决策: 挂哪个 Stack?
   - **推荐**: 挂 `FAManagementStackNavigator` (页面管理类的入口)
   - **备选**: 新建独立 `LowcodeStackNavigator` (如果 PageEditor 子页面多)
4. 加路由:
   ```typescript
   // FAManagementStackParamList 加
   PageEditor: { pageId?: string; pageType?: 'home' | 'dashboard' | 'list' | 'detail' | 'form' };

   // FAManagementStackNavigator.tsx 加
   <Stack.Screen
     name="PageEditor"
     component={PageEditor}
     options={{ title: '页面编辑器', headerShown: true }}
   />
   ```
5. 处理 PageEditor 内部 props:
   - 当前 `PageEditor.tsx` 大概是直接读 store, 不一定接 route params
   - 加 `const route = useRoute<RouteProp<FAManagementStackParamList, 'PageEditor'>>();` 接 `pageId` (编辑现有) / `pageType` (新建)
6. **类型安全** (CLAUDE.md 规则): 禁止 `useRoute<any>()`, 必须显式 RouteProp 类型

**Day 5 产出**: 路由注册完, 类型签名正确, npm start 不报错

#### Day 6: ManagementScreen 加入口 + 跑通 + PR

任务:
1. 读 `frontend/CretasFoodTrace/src/screens/factory-admin/management/FAManagementScreen.tsx` — 看现有的菜单项怎么加
2. 加入口卡片:
   ```typescript
   <ManagementCard
     title="页面编辑器"
     subtitle="对话/拖拽生成自定义页面"
     icon="layout"
     onPress={() => navigation.navigate('PageEditor', { pageType: 'home' })}
   />
   ```
3. E2E 跑通:
   - 启前后端, 登 factory_super_admin
   - FactoryAdmin → Management → 点 "页面编辑器" 卡片
   - 进入 PageEditor → 从左侧拖一个 `stats_card` 到 Canvas
   - 编辑右侧属性
   - 点底部 "保存" → 验证 POST `/api/mobile/{factoryId}/lowcode/pages` 200
   - 退出 → 重进 → 验证页面加载, 拖拽状态恢复
4. **重要**: PageEditor 保存的数据要写 `lowcode_page_config` 表 (现有 LowcodeController CRUD 已实现, 你不用动后端)
5. 测试至少 3 个 pageType:
   - `home` — 首页 (跟 FAHomeScreen 兼容)
   - `dashboard` — 销售仪表盘
   - `list` — 列表页
6. 推 PR: `[Track-A] C-CANVAS-PAGE PageEditor 挂导航`
7. PR body 含: 加的路由 / 加的菜单入口 / E2E 验证步骤 / 演示截图

**Day 6 产出**: PageEditor 用户能进 + 拖 + 存 + 重载, PR 推送

---

### Day 7-9: Canvas Tool Repository 统一 (项目 3, C-CANVAS-REPO)

#### 病灶定位

三个表写错乱:

| Tool / Service | 写哪张表 | 读哪张表 | 状态 |
|---|---|---|---|
| `pagedesign/*Tool` (4 个) | `lowcode_page_config` | ←PageEditor (项目 2 修完后) 读 | ✅ 一致 (此次不动) |
| `decoration/HomeLayoutGenerateTool` (`HomeLayoutGenerateTool.java:33` autowire `FactorySettingsRepository`) | **`FactorySettings`** ❌ | HomeLayoutEditor 读 `FactoryHomeLayout` | ❌ Tool 输出首页看不到 |
| `decoration/HomeLayoutUpdateTool` | **`FactorySettings`** ❌ | 同上 | ❌ 同上 |
| `decoration/HomeLayoutSuggestTool` | **`FactorySettings`** ❌ | 同上 | ❌ 同上 |

**目标**: 把 3 个 `decoration/*Tool` 改成写 `FactoryHomeLayout` (跟 HomeLayoutEditor 读的表对齐), 删除 `FactorySettings` 中相关的 layout 字段 (或保留但标 deprecated)。

#### Day 7: 数据迁移设计 + Flyway

任务:
1. 读 `entity/decoration/FactoryHomeLayout.java` — 完整字段, 注意 `modulesConfig` / `themeConfig` / `gridColumns` / `aiGenerated` / `aiPrompt` / `timeBasedEnabled` / `status` / `version`
2. 读 `entity/FactorySettings.java` — 找现在被 3 个 Tool 写的字段 (大概率是 `homeLayoutJson` 之类的 JSON 字段)
3. 写 Flyway migration:
   ```sql
   -- V20260516_04__migrate_decoration_tool_target_table.sql
   -- 1. 把 FactorySettings.homeLayoutJson 中已有数据迁到 FactoryHomeLayout (如果有)
   INSERT INTO factory_home_layout (
       id, factory_id, modules_config, theme_config, grid_columns,
       ai_generated, ai_prompt, status, version, created_at, updated_at
   )
   SELECT
       gen_random_uuid(), factory_id,
       home_layout_json::jsonb -> 'modules',
       home_layout_json::jsonb -> 'theme',
       2, 1, '从 FactorySettings 迁移',
       0, 1, NOW(), NOW()
   FROM factory_settings
   WHERE home_layout_json IS NOT NULL
     AND NOT EXISTS (
       SELECT 1 FROM factory_home_layout fhl WHERE fhl.factory_id = factory_settings.factory_id
     );

   -- 2. 删除 FactorySettings.homeLayoutJson 字段 (或保留并标 deprecated)
   -- 谨慎: 如果其他代码还在读这个字段, 不要删, 加 @Deprecated 注释
   ```
4. **重点**: 先 grep 全仓 `FactorySettings.*homeLayout` 看有没有其他代码读这个字段, 如果有, 第二步先不删, 留 follow-up ticket
5. 部署到本地 PG: `mvn spring-boot:run` 自动跑 Flyway, 验证表结构

**Day 7 产出**: Flyway migration 文件 + 本地数据迁移验证

#### Day 8: 改 3 个 decoration Tool 写 FactoryHomeLayout

任务:
1. `HomeLayoutGenerateTool.java`:
   - 把 `@Autowired private FactorySettingsRepository factorySettingsRepository;` 改成 `@Autowired private FactoryHomeLayoutRepository layoutRepository;`
   - 改 `saveLayout(factoryId, userId, generatedLayout)` 方法:
     ```java
     private void saveLayout(String factoryId, Long userId, List<Map<String, Object>> modules) {
         FactoryHomeLayout entity = layoutRepository.findByFactoryId(factoryId)
             .orElse(createNewLayout(factoryId));
         entity.setModulesConfig(objectMapper.writeValueAsString(modules));
         entity.setAiGenerated(1);
         entity.setAiPrompt(/* userInput from params */);
         entity.setStatus(0);  // 草稿
         entity.setUpdatedBy(userId);
         layoutRepository.save(entity);
     }
     ```
   - 参考 `DecorationServiceImpl.saveDraft()` (`DecorationServiceImpl.java:127-141`) 看现有写表逻辑
2. `HomeLayoutUpdateTool.java`:
   - 同样换 Repository
   - 改 update 逻辑: 先 `findByFactoryId` 拿到现有 entity, 在它的 `modulesConfig` JSON 上做 patch (按 LLM 解析的 "把 AI 洞察移到顶部" 指令), 再 save
3. `HomeLayoutSuggestTool.java`:
   - 它本来不写表 (只读 + 规则推荐), 但需要从 `FactoryHomeLayout` 读现有布局做建议
   - 把 `factorySettingsRepository.findByFactoryId` 改成 `layoutRepository.findByFactoryId`
4. **不要删 FactorySettingsRepository 本身** — 其他业务模块还在用它, 只删 layout 相关字段读写

**Day 8 产出**: 3 个 Tool 都写 / 读 `FactoryHomeLayout`, 本地启动验证

#### Day 9: 集成测试 + PR

任务:
1. **Tool 链端到端测试**:
   - 触发 `home_layout_generate` Tool (通过 AI 意图: "生成一个新首页布局")
   - 验证 `factory_home_layout` 表写入新数据
   - 退出 FAHome → 重进 → 验证 Tool 生成的布局在首页生效
2. **AILayoutAssistant 跟 Tool 不冲突测试**:
   - AILayoutAssistant 调 `/decoration/home-layout/ai-generate` (走 `DecorationServiceImpl`, 项目 1 已修)
   - Tool 走 IntentExecutor (`home_layout_generate` toolName)
   - 两条路径都写 `FactoryHomeLayout` 同一表 → 验证不会互相覆盖 (用 version + updated_at 检查)
3. **HomeLayoutSuggestTool 读对表**:
   - 触发 `home_layout_suggest` (AI 意图: "给我布局建议")
   - 验证它读 `FactoryHomeLayout` 而不是 `FactorySettings`
4. **数据迁移验证**:
   - 如果有从 `FactorySettings.homeLayoutJson` 迁过来的数据, 验证 `factory_home_layout` 表有对应行
5. 推 PR: `[Track-A] C-CANVAS-REPO Canvas Tool Repository 统一`
6. PR body 含: 涉及文件 / Flyway migration 路径 / 改了哪些 Repository / 测试方式 / 风险点 (老数据迁移)

**Day 9 产出**: 3 个项目全部完成, 3 个 PR 推送

---

## §5 关键参考文档

### 必读文档

| 路径 (绝对) | 用途 |
|---|---|
| `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\03-审计过程\AUDIT_Y_CANVAS.md` | **找到这 3 个 bug 的审计文档**, TL;DR 表 + §1-§5 详细分析 |
| `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\04-最终决策\DISPATCH_OVERVIEW.md` | 跨 track 协调总览 |
| `C:\Users\Steve\my-prototype-logistics\CLAUDE.md` | Cretas 项目规范 (字段命名 / API / 类型安全) |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\api-response-handling.md` | 统一响应格式 `{ success, data, message }` |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\typescript-type-safety.md` | TypeScript 类型安全 (禁止 `as any`) |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\database-entity-sync.md` | Entity / Flyway 同步规范 |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\ai-intent-tool-skill-architecture.md` | Tool-Skill 架构 (Tool 怎么注册, 禁止 IntentHandler) |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\concurrent-edit-safety.md` | 并发编辑安全 (你跟其他 track 共用仓库, 必读) |

### 关键源码位置 (你要改的)

| 文件 | 行号 | 说明 |
|---|---|---|
| `backend/.../service/decoration/impl/DecorationServiceImpl.java` | 207 | 写死 `modelUsed("rule-based")`, 项目 1 主战场 |
| `backend/.../service/decoration/impl/DecorationServiceImpl.java` | 162-210 | `generateLayoutWithAI()` 整个方法 |
| `backend/.../service/decoration/impl/DecorationServiceImpl.java` | 524 | `generateModulesFromRequest()` 规则布局, 保留作 fallback |
| `backend/.../ai/tool/impl/decoration/HomeLayoutGenerateTool.java` | 33 | autowire `FactorySettingsRepository` ❌, 改成 `FactoryHomeLayoutRepository` |
| `backend/.../ai/tool/impl/decoration/HomeLayoutUpdateTool.java` | 类似 | 同上 |
| `backend/.../ai/tool/impl/decoration/HomeLayoutSuggestTool.java` | 类似 | 同上 |
| `frontend/.../screens/lowcode/PageEditor.tsx` | 全文 | 1252 行 dead code, 项目 2 挂导航 |
| `frontend/.../navigation/factory-admin/FAManagementStackNavigator.tsx` | 路由表 | 项目 2 加路由 |
| `frontend/.../screens/factory-admin/management/FAManagementScreen.tsx` | 菜单项 | 项目 2 加入口卡片 |

### 关键参考源码 (你不改, 但要读)

| 文件 | 用途 |
|---|---|
| `backend/.../ai/client/PythonLLMClient.java` | 项目 1 要接的 LLM client |
| `backend/.../ai/client/DashScopeClient.java` | Tool 已在用的另一个 LLM client (HomeLayoutGenerateTool 用它), 参考 prompt 构造 |
| `backend/.../entity/decoration/FactoryHomeLayout.java` | 统一目标表的 Entity |
| `backend/.../repository/FactoryHomeLayoutRepository.java` | 统一目标的 Repository (项目 3 用) |
| `frontend/.../screens/factory-admin/home/components/AILayoutAssistant.tsx` | 项目 1 前端调的 UI, 看它发什么 request |
| `frontend/.../services/api/decorationApiClient.ts` | 前端 API client, `generateLayoutWithAI` 在这里 |
| `frontend/.../screens/factory-admin/home/HomeLayoutEditorScreen.tsx` | 已挂导航的对照参考, 看 PageEditor 怎么挂 |
| `backend/.../resources/db/migration/V2026_01_14_02__add_lowcode_page_config_tables.sql` | lowcode_page_config 表 schema 参考 |

### Schema 风格参考

虽然 Canvas 不在 ASAP "9 张表" 范围里 (那 9 表是 Track C/D 干的), 但写 Flyway migration 时参考:
- `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\01-客户档案\SCHEMA_DESIGN.md` (如有)
- 现有 Flyway: `backend/java/cretas-api/src/main/resources/db/migration/V2026_*.sql`

---

## §6 接口契约 (Interface Contracts)

### 项目 1: AILayoutAssistant 返回的 JSON Schema

**前端期望** (`AILayoutAssistant.tsx` 调用):
```typescript
// POST /api/mobile/{factoryId}/decoration/home-layout/ai-generate
// Request
{
  prompt: string;                        // "把 AI 洞察移到顶部"
  gridColumns?: number;                  // 默认 2
  timeBasedEnabled?: boolean;            // 默认 false
  priorityModules?: string[];            // ["ai_insight", "stats_grid"]
  stylePreference?: 'concise' | 'data_dense' | 'action_first';
}

// Response (你的输出, 必须符合)
{
  success: true,
  data: {
    layout: {
      factoryId: string,
      modules: Array<{
        id: string,                      // "welcome" / "ai_insight" / etc
        type: 'welcome' | 'ai_insight' | 'stats_grid' | 'quick_actions' | 'dev_tools',
        x: number, y: number,            // 0-indexed grid 坐标
        w: number, h: number,            // 1-2 (1x1/2x1/1x2/2x2)
        visible: boolean,
        props?: object                   // 模块特定属性
      }>,
      theme: {
        primaryColor: string,            // "#1890ff"
        mode: 'light' | 'dark'
      },
      gridColumns: number,
      status: 0 | 1,                    // 0=草稿 1=已发布
      version: number,
      aiGenerated: 1,
      aiPrompt: string,
      timeBasedEnabled: 0 | 1
    },
    explanation: string,                 // "我把 AI 洞察移到了顶部, 因为..."
    generationTimeMs: number,
    modelUsed: string,                   // "qwen-flash-aliyun-b" (不是 "rule-based")
    suggestions: Array<{
      id: string,
      type: 'visibility' | 'layout' | 'theme',
      title: string,
      description: string
    }>
  },
  message: "操作成功"
}
```

**FAHomeScreen renderer 兼容性** (`FAHomeScreen.tsx:182-219` 硬编码 switch):
- 只渲染 5 种 type: `welcome` / `ai_insight` / `stats_grid` / `quick_actions` / `dev_tools`
- 你的 LLM 输出 module type **必须**在这 5 个里, 否则前端不渲染
- prompt template 必须明确告诉 LLM "只能用这 5 个 type"

### 项目 2: PageEditor 保存格式

**写入 `lowcode_page_config` 表** (现有 LowcodeController CRUD 已实现, 你不写后端):
- 前端 PageEditor 调 `lowcodeApiClient.savePage(pageId, config)` → `POST /api/mobile/{factoryId}/lowcode/pages`
- `config` 字段: `{ pageType, components: [...], layout: {...}, theme: {...} }`
- 后端 Entity `LowcodePageConfig` (`entity/LowcodePageConfig.java:32`) 已有完整字段

**你需要做的**:
- 不改后端
- 只确保 PageEditor 挂导航后, 走现有 `/lowcode/pages` CRUD 路径
- 验证保存的数据能被同一 PageEditor 重新加载

### 项目 3: 统一表 FactoryHomeLayout 字段约定

**Entity 字段** (`entity/decoration/FactoryHomeLayout.java`):
- `id` UUID PK
- `factoryId` varchar
- `modulesConfig` text (JSON string of modules array)
- `themeConfig` text (JSON string of theme)
- `gridColumns` int (默认 2)
- `aiGenerated` int (0/1)
- `aiPrompt` text
- `timeBasedEnabled` int (0/1)
- `status` int (0=草稿 1=已发布)
- `version` int (乐观锁)
- BaseEntity 字段 (createdAt / updatedAt / deletedAt / createdBy / updatedBy)

**字段命名规范** (.claude/rules/field-naming-convention.md):
- Java Entity: camelCase (`batchNumber`)
- 数据库列: snake_case (`batch_number`)
- JSON API: camelCase (`"batchNumber"`)
- TypeScript: camelCase

---

## §7 PR / Status Update 流程

### 每日 STATUS 更新

文件: `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\04-最终决策\STATUS\TRACK_A_STATUS.md`

**注意**: 该目录 + 文件目前**不存在**, 你需要在 Day 1 第一件事就创建。

格式 (每天追加 1 段):
```markdown
## Day 1 (2026-05-15)
- ✅ 完成: 读完 DecorationServiceImpl.java + AILayoutAssistant.tsx + PythonLLMClient.java; 设计 prompt template
- 🟡 进行中: prompt template v1 draft
- ❌ Blocker: 无
- 明日计划: 接 PythonLLMClient, 本地 curl 跑通

## Day 2 (2026-05-16)
- ✅ 完成: ...
...
```

**ping organizer 时机**:
- 每天 EOD 写完 STATUS 后, 在本 chat 跟 organizer 说 "Track A Day N 完成, 已更新 STATUS"
- 碰到 blocker 立即 ping (不等到 EOD)

### PR 流程

每个项目完成 → 推一个 PR:

1. **创建分支** (推荐 git worktree, 见 `.claude/rules/concurrent-edit-safety.md`):
   ```bash
   cd C:\Users\Steve\my-prototype-logistics
   git worktree add ../my-prototype-logistics-track-a-canvas-ai feature/asap-track-a-canvas-ai
   cd ../my-prototype-logistics-track-a-canvas-ai
   ```

2. **里程碑式 commit** (规则 1, 必读):
   ```bash
   # 不要等"全部做完再 commit", 每完成一个 phase 立即 commit
   git add backend/.../DecorationServiceImpl.java
   git commit -m "WIP: Day 2 接通 PythonLLMClient"
   ```

3. **并发安全 commit** (规则 5b):
   ```bash
   # 不要用 git add . 全 stage, 用 specific paths
   # 4 chat 并行时, 用 commit -- 锁定 scope
   git commit -m "feat: AILayoutAssistant 接真 LLM" -- backend/.../DecorationServiceImpl.java backend/.../DecorationServiceImplTest.java
   # 或用 scripts/safe-commit.sh 自动 verify
   ```

4. **推 PR**:
   ```bash
   git push -u origin feature/asap-track-a-canvas-ai
   gh pr create --title "[Track-A] C-CANVAS-AI AILayoutAssistant 接真 LLM" --body "$(cat <<'EOF'
   ## Summary
   - 修复 DecorationServiceImpl.java:207 写死 modelUsed("rule-based")
   - 接通 PythonLLMClient, 真调用 LLM
   - 保留 generateModulesFromRequest() 作为 LLM 失败时的 fallback

   ## Test plan
   - [ ] 单元测试: mock PythonLLMClient, 4 个边界 case
   - [ ] 集成测试: 真 LLM 调用, 3 个 prompt
   - [ ] E2E: FAHome → HomeLayoutEditor → AILayoutAssistant → 输入 prompt → 验证布局变化

   ## Risk
   - LLM 超时 (>30s) 降级到规则布局
   - LLM 返回非合法 JSON 降级到规则布局

   🤖 Generated with Claude Code
   EOF
   )"
   ```

5. **等 organizer review**: 不要自己 merge, organizer 决定 merge 顺序避免跨 track 冲突

### Blocker 上报模板

```markdown
## Day N (YYYY-MM-DD)
- ❌ Blocker: PythonLLMClient.chatLowTemp() 方法不存在, 只有 async 版本
- 影响: 项目 1 Day 2 任务卡死
- 建议方案: A) 用 async client + .get() 同步等; B) Organizer 协调加同步方法
- 需要 organizer: 拍板 A/B
```

---

## §8 不要做 (Do Not Do)

### 严格禁止

1. **不要 refactor Canvas 整体架构** — 你的范围是 3 个 bug 修复, 不是重写
   - 不要把 PageEditor 拆成多个子组件 (1252 行就 1252 行)
   - 不要换技术栈 (BentoGridEditor 用 react-native-reanimated 就继续用)
   - 不要重新设计 ModuleConfig schema

2. **不要改 ownership 外的文件**:
   - 不要改 `BaseEntity.java`
   - 不要改 `IntentExecutorServiceImpl.java`
   - 不要改 `aiApiClient.ts`
   - 不要改 `CLAUDE.md` / `.claude/rules/`
   - 不要改 Track B/C/D ownership 的文件 (见 §3)

3. **不要写新的 N# 编号** — 已有 `NUMBERING_MAP.md`, 你的项目编号是 C-CANVAS-AI / C-CANVAS-PAGE / C-CANVAS-REPO

4. **不要在 worker chat 战略讨论** — 战略已定, 你只执行
   - 不要质疑 "AILayoutAssistant 是否真的需要 LLM"
   - 不要建议 "PageEditor 应该改成 Web 版"
   - 不要讨论 "Cretas 跟宏见的竞争策略"

5. **不要降级处理** (CLAUDE.md 核心原则):
   - 不要返回假数据 (项目 1 LLM 失败要 fallback 到规则布局, 不要返回 mock)
   - 不要静默吞错 (Tool 写表失败要 log.error + throw)
   - 不要 `catch (error: any) { /* ignore */ }`

6. **不要用 `as any`** (.claude/rules/typescript-type-safety.md):
   - 项目 2 加路由必须用 `useRoute<RouteProp<...>>()`, 不准 `useRoute<any>()`
   - 当前项目已有 273 处 `as any` 是历史债, 不要新增

7. **不要并发改同一文件** (.claude/rules/concurrent-edit-safety.md):
   - 用 git worktree 隔离 (推荐)
   - 修改共享文件前 `git status` 确认无 unstaged 变化
   - Commit 用 `git commit -- F1 F2` 锁定 scope, 防 husky 吞别 chat 文件
   - 完成一个 phase 立即 commit (里程碑式)

---

## §9 验收清单

修完 3 个项目, 4 个 PR 全部 merge 后, 验证以下:

### 功能验收

- [ ] **项目 1 (C-CANVAS-AI)**: AILayoutAssistant 能演示真 AI 改首页
  - [ ] 输入 "把 AI 洞察移到顶部" → AI 洞察 y=0
  - [ ] 输入 "深色主题" → theme.mode=dark
  - [ ] 输入 "我只要 3 个模块" → 返回 3 个 visible=true
  - [ ] response 的 `modelUsed` 字段不是 "rule-based"
  - [ ] LLM 失败时自动 fallback 到规则布局, 日志可见 warn

- [ ] **项目 2 (C-CANVAS-PAGE)**: PageEditor 用户能进 + 拖 + 存 + 重载
  - [ ] FAManagement → 页面编辑器 入口可见
  - [ ] 点击进入 PageEditor 不报错
  - [ ] 从左侧拖一个 stats_card 到 Canvas
  - [ ] 编辑右侧属性
  - [ ] 点底部 "保存" → POST /lowcode/pages 200
  - [ ] 退出重进 → 拖拽状态恢复
  - [ ] 支持 3 个 pageType: home / dashboard / list

- [ ] **项目 3 (C-CANVAS-REPO)**: Canvas Tool 写同一表 + HomeLayoutEditor 读同表
  - [ ] HomeLayoutGenerateTool 写 `factory_home_layout` 表 (验证 SELECT)
  - [ ] HomeLayoutUpdateTool 写 `factory_home_layout` 表
  - [ ] HomeLayoutSuggestTool 读 `factory_home_layout` 表
  - [ ] AI 触发 Tool 生成布局 → HomeLayoutEditor 重进首页 → 布局生效
  - [ ] Flyway migration 跑通, 老数据 (如有) 已迁移

### 销售红线验收

- [ ] **红线 2 解除**: 销售可以说 "AILayoutAssistant 是真 AI"
- [ ] **附带**: 销售可以说 "PageEditor 拖拽建首页"
- [ ] **仍禁** (不在 Track A 范围): "拖拽生成业务表单" / "通用对话生成销售仪表盘"

### 技术验收

- [ ] 3 个 PR 全部 merged 到 main
- [ ] 无新增 `as any` (TypeScript)
- [ ] 无新增 `catch (error: any)` (TypeScript)
- [ ] Flyway migration 文件存在 (项目 3)
- [ ] 单元测试覆盖 LLM 边界 case (项目 1)
- [ ] E2E 测试步骤记录在 PR body

---

## §10 客户场景对照

### 客户期望

**六扇门 F006 (卤制品工厂)** 希望:
1. **"AI 一句话改首页"体验** — 客户在 demo 中看到 AILayoutAssistant 打字机回复, 以为是 ChatGPT 级体验
2. **自助调首页布局** — 不用让 Cretas 工程师改代码, 老板自己拖一下就能调
3. **(将来) 拖拽建表单** — 比如 "建一个采购验收表", 不用让 IT 配置

### Cretas 的差异化卖点 (对宏见 ERP)

宏见 keyframes_v2 范式: 左 12 模块菜单 + 中表格 + 顶 tab + 行级操作 + 模态弹窗 + 底部批量操作。**全硬编码, 客户改不动**。

Cretas Canvas 现代化卖点 (修完后才成立):
- ✅ "AI 改首页" (Track A 项目 1 解锁)
- ✅ "拖拽配仪表盘" (Track A 项目 2 解锁)
- ❌ "Formily 式表单生成器" (现在还是 demo, 后续 Sprint 做)

修完 Track A 后, Cretas 对宏见的差异化:
- 客户老板自己改首页, 不用提工单
- AI 助手能根据自然语言改布局
- 销售有真功能可演示, 不再被 "这是 mock 吗" 戳穿

### 跟其他 Track 的串联

- Track A 修完 Canvas 死代码 → 销售能稳定 demo
- Track B 加钉钉机器人 → 老板手机收到告警 → 直接打开 Cretas 首页查看 AI 洞察 (Track A 修过的)
- Track C 加 attachment → PageEditor 拖出来的 form_input 可以接 attachment (后续 Sprint)
- Track D 修生产 bug → AI 洞察读到的数据准确 → AILayoutAssistant 真正有用

---

## 附录: 关键命令速查

### 启动开发环境

```powershell
# 前端 (RN, 端口 3010)
cd C:\Users\Steve\my-prototype-logistics\frontend\CretasFoodTrace
npm start

# 后端 Java (端口 10010)
cd C:\Users\Steve\my-prototype-logistics\backend\java\cretas-api
mvn spring-boot:run

# 后端 Python (端口 8083, 项目 1 LLM 调用经过这里)
cd C:\Users\Steve\my-prototype-logistics\backend\python
uvicorn main:app --port 8083
```

### 健康检查

```powershell
# Java
curl http://localhost:10010/api/mobile/health

# Python
curl http://localhost:8083/health
```

### Git Worktree (推荐)

```powershell
# 创建 Track A 隔离 worktree
cd C:\Users\Steve\my-prototype-logistics
git worktree add ../my-prototype-logistics-track-a-canvas feature/asap-track-a-canvas-ai

# Day 1-4 在这个 worktree 干 AILayoutAssistant
cd ../my-prototype-logistics-track-a-canvas

# Day 5-6 切到另一个 worktree (PageEditor 挂导航)
cd C:\Users\Steve\my-prototype-logistics
git worktree add ../my-prototype-logistics-track-a-pageeditor feature/asap-track-a-canvas-page

# Day 7-9 再切 (Repository 统一)
git worktree add ../my-prototype-logistics-track-a-repo feature/asap-track-a-canvas-repo

# 完成后清理
git worktree remove ../my-prototype-logistics-track-a-canvas
```

### 安全 Commit

```powershell
# 不要 git add ., 用 specific paths
# 不要 git commit -m "...", 用 -- 锁定 scope (并发安全)
git commit -m "feat: AILayoutAssistant 接真 LLM" -- backend/java/cretas-api/src/main/java/com/cretas/aims/service/decoration/impl/DecorationServiceImpl.java
```

---

**Brief 结束。Day 1 开始干活。第一件事: 创建 `STATUS/TRACK_A_STATUS.md`, 然后读 `AUDIT_Y_CANVAS.md` 完整版熟悉病灶。**