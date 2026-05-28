# [Sprint 13 P1] AI Workdesk error path 0 toast — 4 位一体 全 fail (no UI feedback for errors)

**Severity**: P1 (流程依赖错误 UX 完全缺失 per qa-prompt v2.4 Rule 8 / 专章)
**Source**: AI 工厂 Sprint 11 AI Workdesk audit `docs/audits/2026-05-28-sprint11-ai-workdesk-audit/audit.md` §Phase E Dim A

## Problem

Phase B audit captures: **`toastLog` count = 0 in all 22 cases** including 4 error-deep:
- E1 empty_input: no toast
- E2 forced_misroute: no toast
- E3 composite_old_month: no toast (response inline in formatted-output instead)
- E4 wrong_workdesk: TIMEOUT, no toast

Per qa-prompt v2.4 四位一体:

| 检查项 | AI Workdesk 现状 |
|---|---|
| (a) network response.data.message | ✅ backend 返 "请输入问题" / "无该月数据" 等 |
| (b) UI toast 文案 = 后端 message | ❌ 无 toast |
| (c) toast sticky duration:0 + showClose | ❌ 无 toast |
| (d) toast next action 指引 | ❌ 无 toast |

**全 fail.** Errors silently displayed in `.formatted-output` as plain markdown text without:
- 红色 banner
- sticky 不消失
- "前往 X 重试" 指引
- 任何区别于正常 AI 输出的视觉信号

## Customer impact

- E1 (empty input): 用户点空发送 → 没反馈 → 重复点击 → 系统假装在处理 → 用户懵
- E2 (misroute LLM fallback): 用户问 X → AI 答 Y (不相关) → 用户分不清是 AI 不会答还是系统 bug
- E3 (data unavailable): 客户问"上月成本" → AI 输出 "暂无数据" 黑色 plain text → 客户以为公司真没数据 → 不知道该上传 Excel / 选其他月份

Per fool-proof-design R1 (预先显示边界) + R5 (Dead-end 改导航):
- "暂无数据" 必须有 actionHint "请前往 SmartBI 上传 / 选择其他月份"
- error 必须 sticky + showClose 防客户错过

## Fix scope

`web-admin/src/views/workdesk/SalesOwnerWorkdesk.vue:603-606` (and 6 sibling Workdesks):

```ts
// Current
const response = await callIntentExecute(userInput.value, intentCode);
formattedText.value = response.formattedText || response.message || '(无输出)';

// Proposed
const response = await callIntentExecute(userInput.value, intentCode);
if (response.severity === 'error' || /失败|无法|暂无/.test(response.message || '')) {
  ElMessage({
    message: response.message || 'AI 处理失败, 请重试',
    type: 'error',
    duration: 0,           // sticky
    showClose: true,       // 用户手动关
    grouping: true,
    onClick: response.actionHint ? () => router.push(response.actionUrl) : undefined,
  });
  // Plus add red banner inline (alongside formattedText) for visual prominence
  errorBanner.value = {
    message: response.message,
    actionHint: response.actionHint,
    actionUrl: response.actionUrl,
  };
}
formattedText.value = response.formattedText || response.message || '(无输出)';
```

Backend needs to add `actionHint` / `actionUrl` fields to `IntentExecuteResponse` for error cases:
- Composite Tool "三项数据不可用" → actionHint "前往 SmartBI 上传财务数据" + actionUrl `/smartbi/upload?type=finance`
- Empty input rejection → actionHint "请尝试输入: 帮我看上月损溢异常 / 损益分析 / 哪个菜亏钱"
- 403 wrong-Workdesk → actionHint "需要 quality:read 权限, 请联系管理员申请"

## Test design

Spec already has `fourInOneVerdict` computed for error-deep cases — but currently all 4 sub-checks return false because 0 toast fired.

After fix:
1. Re-run 4 error-deep cases
2. Assert `cap.toastLog.length >= 1` for E1/E2/E3
3. Assert `cap.toastLog[0].hasClose === true` (sticky)
4. Assert `cap.toastLog[0].text` contains backend message
5. Assert `cap.toastLog[0].text` contains action hint phrase

## Owner suggestion

Frontend chat (UI work) + AI 工厂 chat for backend `actionHint` / `actionUrl` field additions.

## Effort

4-6h:
- 2h frontend: update 7 Workdesk sendQuery functions (better: centralize in intent-chat.ts)
- 2h backend: add actionHint/actionUrl to IntentExecuteResponse + populate in error paths of major Tools (Composite, validation, perm denial)
- 1h spec assertion update
- 1h e2e re-run verify

## Cross-references

- Audit: `docs/audits/2026-05-28-sprint11-ai-workdesk-audit/audit.md` §Phase E Dim A
- qa-prompt v2.4: 专章 "为什么需要强 UI 反馈"
- fool-proof-design R1 + R5: `.claude/rules/fool-proof-design.md`
- Existing 4-位一体 implementation reference: `web-admin/src/api/request.ts:127` (admin/regular CRUD path already sticky)
