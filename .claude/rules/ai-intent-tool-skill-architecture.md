---
paths:
  - "backend/java/**"
---

# AI 意图识别 & Tool-Skill 架构规范

**最后更新**: 2026-07-28

## 架构概览

AI 意图系统采用 **Tool-Skill 架构**，所有业务逻辑通过 Tool 和 Skill 实现（337+ Tool，16 内置 Skill）。

```
用户输入 → IntentExecutorServiceImpl.execute()
  ├─ 意图识别 (AIIntentService): EXACT → PHRASE_MATCH → REGEX → KEYWORD → SEMANTIC → CLASSIFIER (Python BERT) → FUSION → LLM 兜底
  └─ 路由执行: ① Tool 直接执行 (intent 绑定 tool_name) → ② Skill 编排 (多 Tool) → ③ ToolRouter 动态选择 (向量检索+LLM) → ④ 无匹配提示
```

## ⛔ 禁止事项

### 1. 禁止创建 IntentHandler

**Handler 架构已完全移除（2026-03-09）**：`IntentHandler` 接口、`AbstractSemanticsHandler` 基类、`service/handler/` 全部 26 个实现、`IntentExecutorServiceImpl` 的 `handlerMap` / `executeWithHandler*()` 均已删除，不得重新引入。新业务一律 `@Component` + 继承 `AbstractBusinessTool`。

### 2. 禁止在 Tool 中直接注入 AIIntentService / AIEnterpriseService

循环依赖链：`AIIntentService → LlmFallbackClient → ToolRegistry → YourTool → AIIntentService`。必须 `@Autowired @Lazy` 打破循环。

### 3. 禁止 Bean 名称冲突

不同包下同名 Tool 类导致 Spring Bean 冲突（如 crm/OrderCreateTool + dataop/OrderCreateTool）。改类名（`CrmOrderCreateTool`）或显式 `@Component("crmOrderCreateTool")`。

## 添加新 Tool

1. 在 `ai/tool/impl/{domain}/` 下建类，继承 `AbstractBusinessTool`，实现 `getToolName()`（`{domain}_{action}` 格式）/ `getDescription()` / `getParametersSchema()` / `getRequiredParameters()` / `doExecute()`。写法照同 domain 现有 Tool。
2. 绑定意图（数据库，非代码）：
   ```sql
   -- 绑定现有意图
   UPDATE ai_intent_config SET tool_name = 'material_batch_query' WHERE intent_code = 'MATERIAL_BATCH_QUERY';
   -- 或创建新意图
   INSERT INTO ai_intent_config (id, intent_code, intent_name, intent_category, tool_name, keywords, is_active, sensitivity_level)
   VALUES (gen_random_uuid(), 'MY_NEW_INTENT', '新功能', 'DATA_OPERATION', 'my_new_tool', '["关键词1","关键词2"]', true, 'LOW');
   ```
3. 验证：`@Component` 自动注册进 `ToolRegistry`，启动日志确认 `✅ 注册工具: name=my_new_tool`。

WRITE 操作支持预览的 Tool 覆盖 `supportsPreview()` + `doPreview()`（查当前值返回变更预览，TCC 模式，不实际修改）。

## Skill 编排层

Skill = 多 Tool 编排，复杂查询需调用 2+ Tool 时使用。注册优先级：数据库 SmartBiSkill > SKILL.md 文件 > 16 个内置 Skill（代码定义）。

## 关键文件

| 文件 | 职责 |
|------|------|
| `ai/tool/AbstractBusinessTool.java` | 业务 Tool 基类（参数校验/类型转换/preview） |
| `ai/tool/ToolRegistry.java` | 自动注册所有 @Component Tool |
| `service/impl/IntentExecutorServiceImpl.java` | 意图执行路由（Tool → Skill → Dynamic → Error） |
| `service/skill/impl/SkillRegistryImpl.java` / `SkillExecutorImpl.java` | Skill 注册中心 / 执行引擎 |
| `service/ToolRouterService.java` | 动态 Tool 选择（向量检索） |
| `entity/config/AIIntentConfig.java` | 意图配置实体（tool_name 字段绑定 Tool） |
