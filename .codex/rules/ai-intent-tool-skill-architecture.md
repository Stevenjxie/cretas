# AI 意图识别 & Tool-Skill 架构规范

**最后更新**: 2026-03-09

---

## 架构概览

AI 意图系统采用 **Tool-Skill 架构**，所有业务逻辑通过 Tool 和 Skill 实现。

```
用户输入
  ↓
IntentExecutorServiceImpl.execute()
  ├─ 意图识别 (AIIntentService)
  │   ├─ EXACT (哈希表)
  │   ├─ PHRASE_MATCH (短语映射)
  │   ├─ REGEX (正则)
  │   ├─ KEYWORD (关键词评分)
  │   ├─ SEMANTIC (向量相似度)
  │   ├─ CLASSIFIER (Python BERT)
  │   ├─ FUSION (语义+关键词融合)
  │   └─ LLM (兜底)
  ↓
路由执行 (4 分支)
  ├─ 1. Tool 直接执行 (intent 绑定 tool_name)
  ├─ 2. Skill 编排 (多 Tool 协作)
  ├─ 3. ToolRouter 动态选择 (向量检索 + LLM 选择)
  └─ 4. 无匹配 → 返回提示
```

**关键数字**：337+ 个 Tool 已注册，16 个内置 Skill，51 个测试意图全部通过。

---

## ⛔ 禁止事项

### 1. 禁止创建 IntentHandler

**Handler 架构已完全移除（2026-03-09）。** 以下类已删除，不得重新引入：

- `IntentHandler` 接口
- `AbstractSemanticsHandler` 基类
- `service/handler/` 目录下的所有 Handler 实现（26 个）
- `IntentExecutorServiceImpl` 中的 `handlerMap`、`executeWithHandler()`、`executeWithHandlerFallback()`

```java
// ❌ 禁止 — Handler 架构已废弃
public class MyIntentHandler implements IntentHandler { ... }
public class MyHandler extends AbstractSemanticsHandler { ... }

// ✅ 正确 — 使用 Tool
@Component
public class MyBusinessTool extends AbstractBusinessTool { ... }
```

### 2. 禁止在 Tool 中直接注入 AIIntentService / AIEnterpriseService

会导致循环依赖：`AIIntentService → LlmFallbackClient → ToolRegistry → YourTool → AIIntentService`

```java
// ❌ 导致循环依赖
@Autowired
private AIIntentService aiIntentService;

// ✅ 用 @Lazy 打破循环
@Autowired
@Lazy
private AIIntentService aiIntentService;
```

### 3. 禁止 Bean 名称冲突

不同包下的同名类会导致 Spring Bean 冲突。

```java
// ❌ crm/OrderCreateTool + dataop/OrderCreateTool → 冲突
@Component
public class OrderCreateTool extends AbstractBusinessTool { ... }

// ✅ 方案A：不同类名
@Component
public class CrmOrderCreateTool extends AbstractBusinessTool { ... }

// ✅ 方案B：显式 bean name
@Component("crmOrderCreateTool")
public class OrderCreateTool extends AbstractBusinessTool { ... }
```

---

## 添加新 Tool 的步骤

### 1. 创建 Tool 类

```java
package com.cretas.aims.ai.tool.impl.{domain};

@Slf4j
@Component
public class MyNewTool extends AbstractBusinessTool {

    @Autowired
    private MyService myService;  // 注入业务 Service

    @Override
    public String getToolName() {
        return "{domain}_{action}";  // 如 "material_batch_query"
    }

    @Override
    public String getDescription() {
        return "工具描述，LLM 根据此描述判断何时调用";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "param1", Map.of("type", "string", "description", "参数说明")
            ),
            "required", List.of("param1")
        );
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("param1");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String param1 = getString(params, "param1");
        // 调用实际业务 Service
        var result = myService.doSomething(factoryId, param1);
        return buildSimpleResult("操作成功", result);
    }
}
```

### 2. 绑定意图（数据库）

```sql
-- 方式1：绑定到现有意图
UPDATE ai_intent_config
SET tool_name = 'material_batch_query'
WHERE intent_code = 'MATERIAL_BATCH_QUERY';

-- 方式2：创建新意图
INSERT INTO ai_intent_config (id, intent_code, intent_name, intent_category,
  tool_name, keywords, is_active, sensitivity_level)
VALUES (gen_random_uuid(), 'MY_NEW_INTENT', '新功能', 'DATA_OPERATION',
  'my_new_tool', '["关键词1","关键词2"]', true, 'LOW');
```

### 3. 验证

Tool 通过 `@Component` 自动注册到 `ToolRegistry`，无需手动配置。
启动日志确认：`✅ 注册工具: name=my_new_tool, class=MyNewTool`

---

## Tool 目录结构

```
ai/tool/
├── ToolExecutor.java              # 接口 (getToolName, execute, preview)
├── AbstractTool.java              # 基类 (日志、错误处理、JSON 序列化)
├── AbstractBusinessTool.java      # 业务基类 (参数校验、类型转换、preview)
├── ToolRegistry.java              # 注册中心 (Spring DI 自动收集)
└── impl/
    ├── camera/                    # 摄像头 (10 tools)
    ├── crm/                       # CRM: 客户/订单/供应商
    ├── dataop/                    # 数据操作 (通用 CRUD)
    ├── decoration/                # 页面装饰
    ├── equipment/                 # 设备管理 (9 tools)
    ├── finance/                   # 财务
    ├── foodknowledge/             # 食品知识
    ├── hr/                        # 人力资源
    ├── material/                  # 原材料 (13 tools)
    ├── pagedesign/                # 页面设计
    ├── purchase/                  # 采购
    ├── quality/                   # 质检 (6 tools)
    ├── report/                    # 报表 (20+ tools)
    ├── restaurant/                # 餐饮
    ├── returnorder/               # 退货
    ├── sales/                     # 销售
    ├── scale/                     # 电子秤 (7 tools)
    ├── scheduling/                # 排程
    ├── shipment/                  # 出货
    ├── system/                    # 系统功能 (帮助/反馈/设置)
    ├── transfer/                  # 调拨
    └── workreport/                # 工作报告
```

---

## Skill 编排层

Skill = 多个 Tool 的编排方案，适用于复杂查询需要调用 2+ 个 Tool 的场景。

```
SkillRegistry (ConcurrentHashMap)
  ├─ 16 个内置 Skill (代码定义)
  ├─ SKILL.md 文件 (YAML frontmatter)
  └─ 数据库 SmartBiSkill (最高优先级)
```

**内置 Skill**: inventory-analysis, production-tracking, quality-inspection, material-batch, personnel-scheduling, report-generation, equipment-diagnosis, order-fulfillment, traceability, supplier-evaluation, restaurant-operations, restaurant-wastage, cost-analysis, finance-invoice, finance-payment, rd-sample

---

## WRITE 操作 Preview 流程

支持预览的 Tool 覆盖 `doPreview()` 实现 TCC 模式：

```java
@Override
public boolean supportsPreview() { return true; }

@Override
protected Map<String, Object> doPreview(String factoryId,
        Map<String, Object> params, Map<String, Object> context) {
    // 查询当前值，返回变更预览，不实际修改
    return Map.of("status", "PREVIEW", "currentValue", ..., "newValue", ...);
}
```

---

## 关键文件

| 文件 | 职责 |
|------|------|
| `ai/tool/ToolExecutor.java` | Tool 接口定义 |
| `ai/tool/AbstractBusinessTool.java` | 业务 Tool 基类（参数校验/类型转换/preview） |
| `ai/tool/ToolRegistry.java` | 自动注册所有 @Component Tool |
| `service/impl/IntentExecutorServiceImpl.java` | 意图执行路由（Tool → Skill → Dynamic → Error） |
| `service/AIIntentService.java` | 意图识别接口 |
| `service/skill/impl/SkillRegistryImpl.java` | Skill 注册中心 |
| `service/skill/impl/SkillExecutorImpl.java` | Skill 执行引擎 |
| `service/ToolRouterService.java` | 动态 Tool 选择（向量检索） |
| `entity/config/AIIntentConfig.java` | 意图配置实体（tool_name 字段绑定 Tool） |
