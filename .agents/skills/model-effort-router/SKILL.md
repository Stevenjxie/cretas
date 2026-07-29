---
name: model-effort-router
description: Duration- and usage-aware Cretas router for selecting the lowest sufficient model and cruise effort, with bounded escalation only at genuinely difficult decision checkpoints.
---

# Model Effort Router

Route every new Cretas task on two independent axes: the capability needed for the hardest unresolved decision, and the usage exposure created by the expected number of turns, context growth, tools, agents, waits, and phases. Optimize total task cost, not peak capability per turn.

## Workflow

1. Read [references/model-matrix.md](references/model-matrix.md) completely.
2. Use only current context or the minimum read-only inspection needed to score:
   - decision ambiguity and domain unfamiliarity;
   - blast radius and reversibility;
   - strength of deterministic verification and rollback;
   - expected model turns, context growth, tool calls, waits, compact risk, and agent fan-out;
   - whether the work is one decision or a multi-phase execution campaign.
3. Classify the execution shape:
   - **burst**: one explicit bounded step, no waits or phase transitions, expected to finish within 3 model turns;
   - **sustained**: automation, monitoring, investigation plus implementation, multi-stage testing/release, expected to exceed 3 model turns, or any credible compact/subagent risk.
4. Select the model family first. Then choose the **cruise effort** for the majority of the task. Do not compensate for the wrong model family by increasing effort.
5. If one narrow decision genuinely needs more depth, name a separate **checkpoint effort** and its stopping condition. Never select the checkpoint effort as the standing setting for the later execution loop.
6. Compare the current setting:
   - effort below the capability floor is never compatible;
   - for burst work, the exact cruise effort or one tier higher is compatible;
   - for sustained work, only the exact cruise effort is compatible; a higher setting is over-provisioned, not “more than sufficient”;
   - if the setting cannot be observed, say so and follow the repository gate.
7. Re-route when a burst grows into sustained work, the task enters a materially different risk phase, the first compact occurs, agents are added, or High fails to resolve the named decision.
8. If a mismatch is discovered mid-task, finish only the current atomic step, preserve evidence and a handoff, then apply the automatic continuation rules below at the next safe boundary. Do not interrupt an in-flight test, deploy, transaction, or consistency-sensitive edit.
9. Before rotating for effort, usage, compact, or context pressure, call `get_goal` to determine authorization semantics. Task creation does not require a Goal.
10. Give the recommendation once per stable phase. Do not repeat it on every ordinary turn.

## Hard rules

- Prefer the lowest setting that safely handles the task as a whole, while accounting for the cost of a wrong result and the number of turns over which the setting will remain active.
- Higher effort is not automatically compatible. For sustained work, unnecessary effort compounds across every message, tool result, retry, compact, and agent branch.
- Effort measures reasoning depth for the current phase; it is not a proxy for autonomy, persistence, task length, module count, PR importance, or the number of files.
- Multiple modules with explicit contracts and strong tests normally need Medium or High, not Extra High. Count interfaces with unresolved semantic ambiguity, not directories.
- A PR audit is primarily deterministic Git-graph work: use Medium for read-only/no-candidate runs and High when a real candidate requires conflict analysis or an authorized mutation. PR creation alone never justifies Extra High.
- Routine SOP/RAG synchronization and other recurring automations use Medium for no-op/read-only passes and High for bounded source changes plus validation. Never configure a standing automation to Extra High/XHigh.
- Production read-only inspection is not automatically high effort. Production writes raise safety gates and verification, but normally use High cruise effort; reserve Extra High for a bounded unresolved security, migration, or data-consistency decision that verification cannot cheaply settle.
- Design work is routed by ambiguity and consequence, not by the word “design.” Applying an established design system is Medium/High; Extra High requires a foundational cross-product decision with competing constraints and no cheap prototype.
- Long-running work should normally cruise at Medium or High. Extra High/XHigh must have a named question, a bounded evidence set, and an exit condition; downshift before implementation, testing, PR mechanics, CI waiting, deployment monitoring, or repeated tool loops.
- Automatic continuation applies to ordinary tasks and Goal tasks unless the user opts out. Warn once near 50% usage; near 80% usage, after the second compact, before a likely third compact, or when a sustained effort mismatch would otherwise continue wasting usage, automatically create a fresh continuation task at the safe boundary.
- Continuation must use a concise handoff in a newly created task rather than a full-history fork. Resolve the saved project with `list_projects`, then pass the complete continuation packet directly as `create_thread.prompt`; this creates the operation-area task, sends the handoff, and starts it without user copy/paste. For Git projects prefer a worktree from `working-tree` when state is uncommitted or from an explicit existing branch/ref when state is durable. Omit the model unless the user specified it, and set `thinking` to the recommended cruise effort.
- Call `get_goal` before dispatch. Without an ACTIVE Goal, the packet carries only the current request and its existing authority; the new task must not create a Goal or expand scope. With an ACTIVE Goal, preserve the exact objective/budget and authority boundaries, and recreate that exact Goal only if the new task did not inherit it. Goal pause disables continuation.
- Every packet preserves repository state, evidence, ownership locks, and next action. Creation success transfers ownership; the old task stops modifying that scope. If creation fails or state cannot be copied safely, continue the task in the current task and retry only at a later safe boundary.
- If `create_thread` returns a ready `threadId`, use one bounded `wait_threads` call to confirm progress or an attention request. A returned `clientThreadId` is a confirmed queued worktree creation. Stop the old task only after one of those confirmations; otherwise continue the Goal locally.
- Handoff is not completion or blockage. Never call `update_goal complete/blocked` merely to rotate tasks.
- Weight blast radius and reversibility together with verification strength. Strong tests, previews, JPA startup gates, dry runs, canaries, blue-green release, backups, and rollback reduce the reasoning effort needed even when operational care remains high.
- Treat GPT-5.6 Sol Medium as the normal Cretas daily driver, not as a universal answer.
- Reserve Sol High for ambiguous bugs, cross-module implementation, or high-risk execution with clear technical gates. Reserve Extra High for the bounded checkpoint cases above.
- Reserve Max for a costly one-shot decision only after a serious Extra High attempt failed to converge.
- Reserve Ultra for genuinely parallelizable work. Ultra does not bypass repository subagent, dispatch, scope-lock, WIP, or verification rules.
- Do not make Terra the default merely because it is positioned as balanced. Current independent cost curves and early community reports often favor either Sol or Luna; use Terra only when its exploration or diversity role fits.
- Allow Luna High or Extra High for well-specified, reversible implementation with strong tests. Do not route ambiguous architecture, security, migrations, production changes, or destructive data operations to Luna as lead.
- Treat Claude models (Sonnet 5 / Opus 5 / Fable 5) as external to Codex and available only through Claude Code. Never imply that Codex can switch to or invoke any of them directly.
- Default the Claude handoff to **Opus 5**, not Fable 5. Use Claude handoffs primarily for independent architecture research and adversarial reports, and return the report to GPT-5.6 Sol for repository-grounded adjudication.
- Escalate to Fable 5 only once the repository gate in `.claude/skills/multi-model-dispatch` has actually fired: Opus 5 visibly stalled on one serious attempt, Opus 5 XHigh returned self-contradicting conclusions, or a pre-authorized bypass applies (production incident on the clock / documented same-family precedent / irreversible small-diff final gate).
- Whenever recommending a Claude handoff, include a complete task-specific paste-ready Claude Code prompt in the same response. Include the role, objective, repository path and access assumptions, current evidence, authority boundary, required sources, deliverables, constraints, selected model/effort, stopping rule, and handoff packet for GPT-5.6 Sol.
- If the live picker differs from this reference, treat the picker as availability truth. Never invent unsupported combinations.
- Treat the dated evidence snapshot in the reference as provisional. Refresh it after a material model, pricing, harness, effort, or local outcome change.

## Required routing response

Use this compact format once for each stable phase:

```text
任务分类：<daily / implementation / complex-analysis / high-stakes / parallel-research / external-review>
预计执行形态：<burst / sustained>
Usage 暴露：<low / medium / high> — <预计回合、compact、工具或代理依据>
推荐模型：<exact model name>
常驻 Effort：<exact picker label>
关卡 Effort：<none，或 exact picker label + 仅解决的具体问题 + 退出条件>
推荐原因：<能力下限与持续成本各一句>
当前设置判定：<匹配 / 低于能力下限 / 长任务过度配置 / 无法观察>
执行状态：<按 AGENTS.md gate 继续或等待>
重新路由条件：<持续度、风险或阶段变化>
```

When recommending a Claude handoff — Opus 5 by default, Fable 5 only after the earned gate fires — append:

````text
Claude Code 操作：打开 Claude Code，选择 <Claude Opus 5 | Claude Fable 5>，Effort <exact effort>
可直接粘贴 Prompt：
```text
<complete task-specific prompt following the Claude prompt contract in the reference>
```
回交方式：将完整报告原样交回当前 Codex task，由 GPT-5.6 Sol 结合仓库真值裁决和落地。
````
