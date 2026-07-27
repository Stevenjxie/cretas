---
name: model-effort-router
description: Advisory Cretas router for selecting an appropriate AI model and reasoning effort when that guidance would materially help the user. Recommendations are optional, appear at most once per task, and never block execution or require switch confirmation.
---

# Model Effort Router

Recommend a model and effort only when the choice would materially help the user. Optimize for sufficient capability, not maximum effort by default, and never turn the recommendation into an execution gate.

## Workflow

1. Decide whether a model or effort suggestion would materially help. Skip the visible recommendation for trivial, familiar, or already well-routed work.
2. Use only the context already available or a minimal read-only inspection to estimate scope, risk, ambiguity, reversibility, duration, verification burden, and parallelizability.
3. Read [references/model-matrix.md](references/model-matrix.md) completely.
4. Select the model family first, then select its effort. Never compensate for the wrong model tier merely by raising effort.
5. If a visible recommendation is useful, use the compact response format below at most once for the task, then continue execution in the same turn.
6. If the setting does not match or cannot be observed, state that only as non-blocking advice. Do not ask the user to switch or confirm, and do not delay editing, testing, research, state changes, or deployment that the task otherwise authorizes.
7. Reassess internally if scope, risk, task type, or required autonomy changes materially. Do not repeat the visible recommendation unless the user explicitly asks.

## Hard rules

- Prefer the lowest setting that safely handles the task, while accounting for the cost of a wrong result.
- A recommendation is advisory only. Never use model or effort mismatch, an unobservable picker, or an unanswered suggestion as a reason to pause or refuse an otherwise authorized task.
- Do not ask the user to confirm a model or effort switch. The user may ignore the recommendation.
- Weight blast radius, reversibility, and verification cost at least as heavily as perceived difficulty. A simple auth, payment, migration, or production change can require a stronger route than a hard but isolated refactor.
- Treat GPT-5.6 Sol Medium as the normal Cretas daily driver, not as a universal answer.
- Reserve Max for exceptional single-agent depth after Extra High is insufficient or when a high-stakes one-shot decision justifies the additional compute.
- Reserve Ultra for genuinely parallelizable work. Ultra does not bypass repository subagent, dispatch, scope-lock, WIP, or verification rules.
- Do not make Terra the default merely because it is positioned as balanced. Current independent cost curves and early community reports often favor either Sol or Luna; use Terra only when its exploration or diversity role fits.
- Allow Luna High or Extra High for well-specified, reversible implementation with strong tests. Do not route ambiguous architecture, security, migrations, production changes, or destructive data operations to Luna as lead.
- Treat Claude models (Sonnet 5 / Opus 5 / Fable 5) as external to Codex and available only through Claude Code. Never imply that Codex can switch to or invoke any of them directly.
- Default the Claude handoff to **Opus 5**, not Fable 5. Use Claude handoffs primarily for independent architecture research and adversarial reports, and return the report to GPT-5.6 Sol for repository-grounded adjudication.
- Escalate to Fable 5 only once the repository gate in `.claude/skills/multi-model-dispatch` has actually fired: Opus 5 visibly stalled on one serious attempt, Opus 5 XHigh returned self-contradicting conclusions, or a pre-authorized bypass applies (production incident on the clock / documented same-family precedent / irreversible small-diff final gate). Fable 5 costs 2x Opus 5 and is capped at single-digit uses per session; a task merely looking hard, long-running, or important is a *predicted* escalation and does not qualify.
- Whenever recommending a Claude handoff (Opus 5 or Fable 5), include a complete, task-specific, paste-ready Claude Code prompt in the same response. Never provide only the model recommendation, an abstract prompt outline, or an offer to write the prompt later.
- Fill the Claude prompt with the known task and Cretas context. Include the role, objective, repository path and access assumptions, current evidence, read-only or mutation boundary, required sources, deliverables, constraints, selected model and effort, output format, and the handoff packet for GPT-5.6 Sol. Do not leave generic placeholders that the user must complete when the required value is already known.
- When the current model or effort cannot be observed, note that limitation without asking the user to respond or delaying the task.
- If the picker differs from the reference, treat the live picker as availability truth; do not invent unsupported combinations.
- Treat the dated evidence snapshot in the reference as provisional. Re-check official availability and refresh the matrix after a material model, pricing, harness, or effort change.

## Optional response

When a visible recommendation would help, use this compact structure once and continue executing:

```text
任务分类：<daily / implementation / complex-analysis / high-stakes / parallel-research / external-review>
推荐模型：<exact model name>
推荐 Effort：<exact picker label>
推荐原因：<one or two concrete sentences>
是否建议切换：<是 / 否 / 无法观察当前设置>
执行状态：建议仅供参考，继续执行
重新路由条件：<material scope or risk changes>
```

When recommending a Claude handoff as an optional independent review — Opus 5 by default, Fable 5 only once the earned gate above has fired — append the material below only if that handoff would be immediately useful. Continue the current Codex task unless the user explicitly chooses the external handoff:

````text
Claude Code 操作：打开 Claude Code，选择 <Claude Opus 5 | Claude Fable 5>，Effort <exact effort>
可直接粘贴 Prompt：
```text
<complete task-specific prompt following the Claude prompt contract in the reference>
```
回交方式：将完整报告原样交回当前 Codex task，由 GPT-5.6 Sol 结合仓库真值裁决和落地。
````

The nested fence above describes the required user-facing layout; render it with valid Markdown fences. Do not make the user ask separately for the prompt.
