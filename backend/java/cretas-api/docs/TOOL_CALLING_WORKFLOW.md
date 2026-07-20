# Tool Calling Workflow Diagram

> **Historical document — do not use as the current execution architecture.**
> `ToolExecutionManager` had no runtime consumers and was removed in D11A. The current target is
> `ToolExecutionGateway` with a shrinking, test-frozen legacy bypass list; the authoritative status
> and migration order are recorded in
> [`docs/architecture/2026-07-18-cretas-agent-architecture-v2.md`](../../../../docs/architecture/2026-07-18-cretas-agent-architecture-v2.md).
> The diagrams below are retained only to explain the retired prototype flow.

## Complete Flow

```
┌─────────────────────────────────────────────────────────────────────┐
│                      User Request                                    │
│                   "帮我查找面粉原料"                                  │
└────────────────────────────┬────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────────┐
│                   ToolExecutionManager                               │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │ 1. Build available tools list                               │    │
│  │ 2. Check permissions                                        │    │
│  │ 3. Initialize conversation history                          │    │
│  └────────────────────────────────────────────────────────────┘    │
└────────────────────────────┬────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    DashScopeClient                                   │
│                                                                      │
│  chatCompletionWithTools(messages, tools, "auto")                   │
│                                                                      │
│  Request Body:                                                       │
│  {                                                                   │
│    "model": "qwen-plus",                                            │
│    "messages": [                                                    │
│      {"role": "system", "content": "You are..."},                  │
│      {"role": "user", "content": "帮我查找面粉原料"}                │
│    ],                                                                │
│    "tools": [                                                       │
│      {                                                              │
│        "type": "function",                                          │
│        "function": {                                                │
│          "name": "search_materials",                                │
│          "description": "搜索原料信息",                             │
│          "parameters": {...}                                        │
│        }                                                            │
│      }                                                              │
│    ],                                                                │
│    "tool_choice": "auto"                                            │
│  }                                                                   │
└────────────────────────────┬────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────────┐
│                 DashScope API (Qwen-Plus)                            │
│                                                                      │
│  LLM analyzes user input and available tools                        │
│  Decides: "I should call search_materials tool"                     │
│                                                                      │
│  Response:                                                           │
│  {                                                                   │
│    "choices": [{                                                    │
│      "message": {                                                   │
│        "role": "assistant",                                         │
│        "tool_calls": [{                                             │
│          "id": "call_abc123",                                       │
│          "type": "function",                                        │
│          "function": {                                              │
│            "name": "search_materials",                              │
│            "arguments": "{\"keyword\": \"面粉\"}"                   │
│          }                                                          │
│        }]                                                           │
│      }                                                              │
│    }]                                                                │
│  }                                                                   │
└────────────────────────────┬────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────────┐
│              DashScopeClient - hasToolCalls()                        │
│                                                                      │
│  ✅ Tool call detected                                               │
│  └─> getFirstToolCall() returns ToolCall object                     │
└────────────────────────────┬────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────────┐
│              ToolExecutionManager - Execute Tool                     │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │ 1. Parse arguments: {"keyword": "面粉"}                     │    │
│  │ 2. Find tool executor: SearchMaterialToolExecutor           │    │
│  │ 3. Check permissions: ✅ Allowed                             │    │
│  │ 4. Execute: executor.execute(toolCall, context)             │    │
│  └────────────────────────────────────────────────────────────┘    │
└────────────────────────────┬────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────────┐
│           SearchMaterialToolExecutor (Custom)                        │
│                                                                      │
│  materialService.search("面粉")                                      │
│                                                                      │
│  Result:                                                             │
│  {                                                                   │
│    "success": true,                                                 │
│    "results": [                                                     │
│      {"id": "M001", "name": "高筋面粉", "quantity": 100},           │
│      {"id": "M002", "name": "低筋面粉", "quantity": 50}             │
│    ]                                                                 │
│  }                                                                   │
└────────────────────────────┬────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────────┐
│       ToolExecutionManager - Add Result to History                  │
│                                                                      │
│  conversation.add(                                                   │
│    ChatMessage.assistant(null, List.of(toolCall))                  │
│  );                                                                  │
│  conversation.add(                                                   │
│    ChatMessage.tool(result, "call_abc123")                          │
│  );                                                                  │
└────────────────────────────┬────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────────┐
│            DashScopeClient - Second Round Call                       │
│                                                                      │
│  chatCompletionWithTools(updatedMessages, tools, "auto")            │
│                                                                      │
│  Messages now include:                                               │
│  [                                                                   │
│    {"role": "system", "content": "..."},                            │
│    {"role": "user", "content": "帮我查找面粉原料"},                  │
│    {"role": "assistant", "tool_calls": [...]},                      │
│    {"role": "tool", "content": "{...}", "tool_call_id": "..."}     │
│  ]                                                                   │
└────────────────────────────┬────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────────┐
│                 DashScope API - Process Result                       │
│                                                                      │
│  LLM analyzes tool result                                            │
│  Generates natural language response                                 │
│                                                                      │
│  Response:                                                           │
│  {                                                                   │
│    "choices": [{                                                    │
│      "message": {                                                   │
│        "role": "assistant",                                         │
│        "content": "我找到了2种面粉：\n" +                            │
│                  "1. 高筋面粉 - 库存100kg\n" +                       │
│                  "2. 低筋面粉 - 库存50kg"                            │
│      }                                                              │
│    }]                                                                │
│  }                                                                   │
└────────────────────────────┬────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────────┐
│          DashScopeClient - hasToolCalls()                            │
│                                                                      │
│  ❌ No more tool calls                                               │
│  └─> Return final answer                                            │
└────────────────────────────┬────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      Final Response                                  │
│                                                                      │
│  "我找到了2种面粉：                                                  │
│   1. 高筋面粉 - 库存100kg                                            │
│   2. 低筋面粉 - 库存50kg"                                            │
└─────────────────────────────────────────────────────────────────────┘
```

## Key Components

### 1. DashScopeClient
- **Role**: HTTP client for DashScope API
- **Methods**:
  - `chatCompletionWithTools()` - Send request with tools
  - `hasToolCalls()` - Check if response contains tool calls
  - `getFirstToolCall()` / `getAllToolCalls()` - Extract tool calls

### 2. ToolExecutionManager
- **Role**: Orchestrator for multi-turn tool calling
- **Responsibilities**:
  - Build available tools list
  - Manage conversation history
  - Execute tools via ToolExecutor implementations
  - Handle errors and max iterations

### 3. ToolExecutor (Interface)
- **Role**: Define and execute specific tools
- **Methods**:
  - `getToolName()` - Tool identifier
  - `getDescription()` - What the tool does
  - `getParametersSchema()` - JSON Schema for parameters
  - `execute()` - Actual execution logic

### 4. ChatMessage
- **Role**: Represent conversation turns
- **Types**:
  - `system` - System prompt
  - `user` - User input
  - `assistant` - LLM response (with optional tool_calls)
  - `tool` - Tool execution result

## Error Handling Flow

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Tool Execution Error                              │
└────────────────────────────┬────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────────┐
│              Catch Exception in Manager                              │
│                                                                      │
│  try {                                                               │
│    String result = executor.execute(toolCall, context);             │
│  } catch (Exception e) {                                            │
│    String errorResult = String.format(                              │
│      "{\"error\": \"%s\", \"message\": \"%s\"}",                    │
│      e.getClass().getSimpleName(),                                  │
│      e.getMessage()                                                 │
│    );                                                                │
│  }                                                                   │
└────────────────────────────┬────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────────┐
│           Add Error Result to Conversation                           │
│                                                                      │
│  conversation.add(                                                   │
│    ChatMessage.tool(errorResult, toolCall.getId())                  │
│  );                                                                  │
└────────────────────────────┬────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────────┐
│                 LLM Handles Error                                    │
│                                                                      │
│  LLM sees error result and provides user-friendly message:          │
│  "抱歉，查询原料时出现错误：数据库连接失败。请稍后再试。"             │
└─────────────────────────────────────────────────────────────────────┘
```

## Permission Check Flow

```
┌─────────────────────────────────────────────────────────────────────┐
│               Build Available Tools                                  │
└────────────────────────────┬────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────────┐
│            For Each Registered ToolExecutor                          │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │ 1. Check: executor.isEnabled()                             │    │
│  │    └─> false? Skip this tool                               │    │
│  │                                                             │    │
│  │ 2. Check: executor.requiresPermission()                    │    │
│  │    └─> true? Check executor.hasPermission(userRole)        │    │
│  │       └─> false? Skip this tool                            │    │
│  │                                                             │    │
│  │ 3. Add to available tools list                             │    │
│  └────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────┘

Example:
  User Role: "warehouse_staff"

  Tool: "search_materials"
    - isEnabled() = true
    - requiresPermission() = false
    → ✅ Available

  Tool: "create_new_intent"
    - isEnabled() = true
    - requiresPermission() = true
    - hasPermission("warehouse_staff") = false
    → ❌ Not available (requires admin)
```

## Iteration Control

```
Max Iterations: 5

Iteration 1: LLM calls "search_materials"
    └─> Execute tool, add result

Iteration 2: LLM calls "get_inventory_stats"
    └─> Execute tool, add result

Iteration 3: LLM calls "format_report"
    └─> Execute tool, add result

Iteration 4: LLM returns final answer
    └─> No more tool calls, return to user

If reaches Iteration 5 without final answer:
    └─> Force stop: "抱歉，处理超时，请简化您的请求或重试。"
```

## Data Flow

```
User Input (String)
    ↓
ChatMessage.user(input)
    ↓
List<ChatMessage> conversation
    ↓
chatCompletionWithTools(conversation, tools, "auto")
    ↓
JSON Request → DashScope API
    ↓
JSON Response ← DashScope API
    ↓
ChatCompletionResponse (parsed)
    ↓
hasToolCalls(response) ?
    ├─ Yes → ToolCall object
    │   ↓
    │   execute(toolCall, context)
    │   ↓
    │   String result (JSON)
    │   ↓
    │   ChatMessage.tool(result, id)
    │   ↓
    │   Add to conversation
    │   ↓
    │   Loop back to chatCompletionWithTools
    │
    └─ No → String content
        ↓
        Return final answer to user
```
