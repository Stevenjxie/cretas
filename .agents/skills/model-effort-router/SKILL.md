---
name: model-effort-router
description: Mandatory Cretas preflight router for selecting the exact AI model and reasoning effort before every new task or material scope change. Use before edits, tests, long research, state-changing tools, deployment, or implementation; it covers GPT-5.6 Sol, Terra, Luna, and external Claude Code Fable 5 handoffs, proceeds immediately when the observable setting matches, and requires confirmation only after a switch or when the setting cannot be observed.
---

# Model Effort Router

Route the task before executing it. Optimize for sufficient capability, not maximum effort by default.

## Workflow

1. Identify whether this is a new objective or a material change to the active objective. Do not re-route ordinary clarification within the same confirmed scope.
2. Perform only the minimum read-only inspection needed to estimate scope, risk, ambiguity, reversibility, duration, verification burden, and parallelizability.
3. Read [references/model-matrix.md](references/model-matrix.md) completely.
4. Select the model family first, then select its effort. Never compensate for the wrong model tier merely by raising effort.
5. Use the exact response format below. If the observable current setting matches, state that no switch is needed and continue directly into execution in the same turn.
6. If the setting does not match or cannot be observed, stop before editing, testing, long research, external state changes, or deployment. Continue only after the user confirms the required setting.
7. Re-run this workflow if scope, risk, task type, or required autonomy changes materially.

## Hard rules

- Prefer the lowest setting that safely handles the task, while accounting for the cost of a wrong result.
- Weight blast radius, reversibility, and verification cost at least as heavily as perceived difficulty. A simple auth, payment, migration, or production change can require a stronger route than a hard but isolated refactor.
- Treat GPT-5.6 Sol Medium as the normal Cretas daily driver, not as a universal answer.
- Reserve Max for exceptional single-agent depth after Extra High is insufficient or when a high-stakes one-shot decision justifies the additional compute.
- Reserve Ultra for genuinely parallelizable work. Ultra does not bypass repository subagent, dispatch, scope-lock, WIP, or verification rules.
- Do not make Terra the default merely because it is positioned as balanced. Current independent cost curves and early community reports often favor either Sol or Luna; use Terra only when its exploration or diversity role fits.
- Allow Luna High or Extra High for well-specified, reversible implementation with strong tests. Do not route ambiguous architecture, security, migrations, production changes, or destructive data operations to Luna as lead.
- Treat Fable 5 as external to Codex and available only through Claude Code. Never imply that Codex can switch to or invoke it directly.
- Use Fable 5 primarily for independent architecture research and adversarial reports. Return its handoff to GPT-5.6 Sol for repository-grounded adjudication.
- Whenever recommending Fable 5, include a complete, task-specific, paste-ready Claude Code prompt in the same response. Never provide only the model recommendation, an abstract prompt outline, or an offer to write the prompt later.
- Fill the Fable prompt with the known task and Cretas context. Include the role, objective, repository path and access assumptions, current evidence, read-only or mutation boundary, required sources, deliverables, constraints, selected effort, output format, and the handoff packet for GPT-5.6 Sol. Do not leave generic placeholders that the user must complete when the required value is already known.
- When the current model or effort cannot be observed, state that limitation and ask the user to compare the recommendation with the picker.
- If the picker differs from the reference, treat the live picker as availability truth; do not invent unsupported combinations.
- Treat the dated evidence snapshot in the reference as provisional. Re-check official availability and refresh the matrix after a material model, pricing, harness, or effort change.

## Required response

Use this exact compact structure:

```text
任务分类：<daily / implementation / complex-analysis / high-stakes / parallel-research / external-review>
推荐模型：<exact model name>
推荐 Effort：<exact picker label>
推荐原因：<one or two concrete sentences>
是否需要切换：<是 / 否 / 无法观察当前设置>
执行状态：<设置匹配，直接执行 / 等待用户切换并确认>
重新路由条件：<material scope or risk changes>
```

When recommending Fable 5, append all of the following. Because Fable is external, stop and wait for the user to run it and return the report:

````text
Claude Code 操作：打开 Claude Code，选择 Claude Fable 5，Effort <exact effort>
可直接粘贴 Prompt：
```text
<complete task-specific prompt following the Fable prompt contract in the reference>
```
回交方式：将完整报告原样交回当前 Codex task，由 GPT-5.6 Sol 结合仓库真值裁决和落地。
````

The nested fence above describes the required user-facing layout; render it with valid Markdown fences. Do not make the user ask separately for the prompt.
