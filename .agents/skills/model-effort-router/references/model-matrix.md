# Model and Effort Matrix

Evidence snapshot: 2026-07-18; **Claude-side tiers refreshed 2026-07-28 (source A)**; **Cretas local effort calibration refreshed 2026-07-29 (source E)**. Use the live model picker as availability truth. This policy is Cretas-specific and provisional because GPT-5.6 and Fable 5 community experience is still early.

## Evidence discipline

Use sources in this order:

1. **A — Official semantics and guidance:** provider documentation and identified product-team statements. Strong for availability, effort behavior, and intended use; not neutral proof of quality.
2. **B — Independent evaluations:** reproducible benchmarks and disclosed harness comparisons. Useful for relative capability, cost, and latency; not a substitute for Cretas tests.
3. **C — Community reports:** Reddit and X reports with concrete tasks. Useful for failure modes and workflow ideas; vulnerable to selection bias, launch load, hidden prompts, different plans, and different harnesses.
4. **D — Inference:** Cretas routing decisions synthesized from A-C. Validate material D-level decisions on the repository before making them defaults.
5. **E — Local telemetry:** sanitized Codex session metadata, explicit routing recommendations, effort settings, compactions, and task shapes from this repository. Strong for detecting routing habits and context exposure; it cannot prove that a lower-effort counterfactual would have produced identical quality.

Do not turn a single benchmark or anecdote into a universal rule. Model choice must also consider blast radius, reversibility, data sensitivity, verification cost, and whether the task is genuinely parallelizable.

## What the evidence changed

- **Sol Medium remains the Cretas daily default.** This matches OpenAI Codex-team guidance, Tibo's public recommendation, and multiple early user reports.
- **Terra is demoted from general daily default to a situational alternative.** OpenAI positions it as balanced, but Artificial Analysis currently finds every Terra effort point Pareto-dominated by a Sol or Luna option, and early user reports frequently describe excess steps, latency, or weak focus.
- **Luna is promoted beyond purely mechanical work.** Official guidance places it in fast/high-volume and subagent roles; repeated community reports find Luna High/Extra High effective for clear, reversible implementation. It is still not the lead for ambiguous or high-blast-radius work.
- **Sol Max and Ultra are exception paths.** Independent results show diminishing gains from Extra High to Max, while Ultra adds parallel agents and can consume allowance very quickly. Ultra is an orchestration mode, not merely deeper thinking.
- **Fable High is the default effort *once Fable is already the right tier*.** Anthropic explicitly recommends High for most work and XHigh only for capability-sensitive workloads. Community feedback broadly agrees; Max and Ultracode are exceptional.
- **The Claude handoff target is a three-tier ladder, and Fable is no longer its default (2026-07-28, source A).** Claude Code exposes Sonnet 5, Opus 5, and Fable 5. Fable 5 costs **exactly 2x Opus 5** ($10/$50 vs $5/$25 per MTok) and is gated repository-side by `.claude/skills/multi-model-dispatch`: earned-not-predicted (Opus must visibly fail one serious attempt first), three narrow pre-authorized bypasses, and a single-digit-per-session frequency cap. **Default the external-review handoff to Opus 5; escalate to Fable only once that gate has actually fired.** Sending every external review to Fable contradicts the repository gate and spends the scarcest tier first.
- **Cretas was over-routing sustained work to XHigh (2026-07-29, source E).** In 39 root sessions, 16 were dominated by XHigh, including all 13 automation sessions. Of 13 recoverable explicit recommendations, 8 recommended Extra High, while 4 of those 8 sessions actually ran primarily at High or Medium. “PR,” “multi-module,” “design,” and “production” had become label shortcuts instead of measured complexity.
- **Standing XHigh is the wrong shape for long campaigns (source E).** The three user-driven XHigh sessions accumulated 331 model turns, 174 compactions, and about 2.72 billion cumulative input-token telemetry. That number includes repeated/cached context and is not a billable-credit total, but it clearly exposes the cost of keeping the highest effort active through implementation, testing, waiting, PR, deployment, and repeated compaction.

## Choose the model first

| Model | Evidence-backed Cretas role | Avoid |
|---|---|---|
| GPT-5.6 Sol | Repository-grounded daily work, difficult implementation, debugging, architecture, migration, verification, and final adjudication | Using Max or Ultra as a permanent default |
| GPT-5.6 Terra | Usage-conscious exploration, bounded noncritical work, and an alternative reviewer whose different behavior may add diversity | Treating it as the automatic middle/default tier or using it for destructive/high-stakes work |
| GPT-5.6 Luna | Fast high-volume work, subagents, and well-specified reversible implementation with strong tests | Ambiguous architecture, auth/payment/security/migration/production leadership, or destructive data operations |
| Claude Opus 5 via Claude Code | **Default Claude handoff target:** external independent architecture research, long-horizon critique, adversarial second-opinion reports, and high-stakes judgment | Treating it as a Codex substitute for repository-grounded execution |
| Claude Fable 5 via Claude Code | Break-glass only, after the repository-side earned gate fires: Opus 5 already tried and stalled, or a pre-authorized bypass applies | Routine Cretas implementation, final repository-truth authority, or **any external review that Opus 5 has not already failed** |
| Claude Sonnet 5 via Claude Code | Rule-heavy execution on the Claude side (`.claude/rules` auto-loads there); not normally a Codex review handoff target | Ambiguous architecture and the hardest locked-down judgment calls |

When uncertain on a Cretas task, prefer Sol Medium. Do not assume that a smaller model at Max is equivalent to a larger model at Medium; benchmarks can be close while tool behavior, context retention, and failure modes differ.

## GPT-5.6 Sol

| Effort | Route | Confidence |
|---|---|---|
| Light / Low | Tiny read-only lookups, simple transformations, narrow status summaries, or a tightly sliced task with cheap and obvious failure | B/C |
| Medium | Daily driver: familiar repository work, clear bugs, routine documentation, scoped implementation, ordinary verification, read-only Git/PR audits, and deterministic automation passes | A/C/E |
| High | Ambiguous bugs, unfamiliar modules, meaningful multi-file implementation, integration work, sustained campaigns, or production execution with explicit safety and verification gates | A/C/E |
| Extra High | A bounded checkpoint for one unresolved architecture, security, irreversible-migration, or data-consistency decision after cheaper evidence collection or a serious High attempt did not settle it; downshift after the decision | A/B/C/E |
| Max | Extra High did not converge, or a costly one-shot decision needs deeper single-agent review and latency is secondary | A/B |
| Ultra | Explicitly approved work that cleanly decomposes into independent streams and satisfies all dispatch, worktree, scope-lock, WIP, and verification rules | A/C |

Routing note: Artificial Analysis currently reports only a small aggregate intelligence increase from Sol Extra High to Max, alongside a much larger end-to-end latency increase. Treat that as evidence of diminishing returns, not proof that Max never helps. OpenAI describes Ultra as a four-agent parallel mode; early Reddit/X reports repeatedly describe severe allowance consumption. For Cretas code writes, Ultra is normally less appropriate than one coherent Sol agent plus scoped verification. Local telemetry also shows that XHigh should not remain active merely because the task continues through implementation, tests, PR/CI, deployment, or compaction; those phases normally cruise at Medium or High.

## GPT-5.6 Terra

| Effort | Route | Confidence |
|---|---|---|
| Light / Low | Quick repository orientation, search, formatting, and low-risk noncoding work when usage matters | A |
| Medium | Broad but bounded exploration, test triage, documentation, or an alternate read-only pass | A/C |
| High | Noncritical multi-file work with clear contracts when Sol availability or allowance is constrained | C |
| Extra High | Rare bounded work where Terra's different exploration behavior is specifically desired; compare against Sol Medium or Luna Max first | B/C |
| Max | Generally avoid as a default route; current independent cost curves find a Sol or Luna point that is as capable for equal or lower task cost | B |
| Ultra | If offered, use only for explicitly parallel, non-overlapping, preferably read-only exploration; it is not a normal Cretas route | D |

Terra note: provider positioning and early real-world feedback conflict. Keep Terra available, but require a concrete reason such as model diversity, usage constraints, or exploratory behavior rather than selecting it merely because it sits between Sol and Luna.

## GPT-5.6 Luna

| Effort | Route | Confidence |
|---|---|---|
| Light / Low | Bulk classification, extraction, formatting, trivial checks, and cheap subagent reconnaissance | A/B |
| Medium | High-volume deterministic work with clear inputs, outputs, and easy verification | A/B |
| High | Normal everyday coding that is narrowly specified, reversible, and covered by tests | B/C |
| Extra High | Cost-sensitive implementation of an explicit plan, bounded refactors, test generation, and mechanical multi-file work | B/C |
| Max | Harder but still bounded implementation when cost efficiency matters; require strong tests and switch to Sol if context, judgment, or blast radius dominates | B/C |
| Ultra | Not supported by the current Cretas routing snapshot. Only use if the live picker explicitly offers it and the task independently qualifies | A |

Luna note: community reports often favor Luna High/Extra High as a daily implementation model, but this is early evidence. Use Sol for architecture and critical business judgment, then Luna for an explicit execution packet only when the affected scope is safe and verifiable.

## Claude handoffs in Claude Code

Claude models are external. The user must open Claude Code manually, select the model and effort, and paste a purpose-built prompt. Never imply that Codex can invoke or switch to any of them.

### Pick the Claude tier before the effort (2026-07-28)

| Tier | When it is the right handoff target |
|---|---|
| **Opus 5** (default) | Any normal external review: independent architecture challenge, adversarial second opinion, high-stakes judgment. High for a normal review, XHigh for the full multi-repository report. |
| **Fable 5** (break-glass) | Only after the gate in `.claude/skills/multi-model-dispatch` fires — Opus 5 visibly stalled on one serious attempt, Opus 5 XHigh returned self-contradicting conclusions, or a pre-authorized bypass applies (production incident on the clock / documented same-family precedent / irreversible small-diff final gate). |
| **Sonnet 5** | Rule-heavy execution on the Claude side, not a Codex review handoff. |

⚠️ **Never route to Fable merely because a task looks hard, long-running, or important.** That is a *predicted* rather than *earned* escalation, which the repository gate explicitly forbids; Fable is 2x Opus 5 and capped at single-digit uses per session. When in doubt the correct handoff is Opus 5.

### Mandatory prompt delivery contract

Every Claude handoff — Opus 5 or Fable 5 — must deliver a complete, task-specific, paste-ready prompt in the same response. Never stop at “use Claude Code,” provide only a prompt outline, or ask whether the user wants a prompt. Populate all known details; use an explicit assumption only when a required fact is genuinely unknown.

The delivered prompt must contain:

- **Role and stance:** independent architect/reviewer, including whether an adversarial or rebuttal report is required.
- **Objective and decision:** the concrete question Fable must resolve and which decisions the report must enable.
- **Cretas context:** relevant product boundaries, with restaurant analytics/AI chat and factory-side agents distinguished when applicable.
- **Repository context:** repository path, target branches/base SHA if known, relevant files/modules, and whether local repository access is available.
- **Authority boundary:** read-only analysis by default; no edits, commits, deployments, messages, or external state changes unless the user explicitly authorized them.
- **Evidence requirements:** inspect repository truth first when accessible; use official primary sources for technical claims; date volatile evidence; separate facts, inference, and recommendations.
- **Required analysis:** alternatives, counterarguments, risks, migration path, rejected options, and unresolved questions with confidence levels.
- **Deliverables and format:** prioritized findings, target architecture, phased roadmap, file-level impact where possible, tests/acceptance criteria, and the handoff packet below.
- **Selected effort and stopping rule:** state the chosen Fable effort and prohibit unrelated scope expansion or speculative implementation.

The routing response must wrap that fully populated prompt in a copyable text code block and precede it with the exact Claude Code model/effort selection. Follow it with: `回交方式：将完整报告原样交回当前 Codex task，由 GPT-5.6 Sol 结合仓库真值裁决和落地。`

### Effort ladder (identical labels on Opus 5 and Fable 5)

| Effort / mode | Route | Confidence |
|---|---|---|
| Low | Routine high-volume work if Fable is already required, though a cheaper model is normally more appropriate | A |
| Medium | Balanced independent analysis with solid capability and lower cost than High | A |
| High | Official default and community sweet spot for most difficult research, critique, nuanced analysis, and coding review | A/C |
| Extra High / XHigh | Capability-sensitive multi-project architecture research, long-horizon adversarial review, or work where deeper verification justifies the cost | A/C |
| Max | Absolute highest single-agent capability with no practical cost/latency constraint; do not assume it always beats High/XHigh on every task | A/B/C |
| Ultracode | Claude Code XHigh plus standing permission to launch multiagent workflows; use only for genuinely isolated parallel work, never as a synonym for deeper reasoning | A |

For the Cretas architecture program, prefer **Opus 5 High** for a normal independent review and **Opus 5 XHigh** for the full multi-repository architecture/rebuttal report; use the same effort labels on **Fable 5** only once the earned gate above has fired. Return the report to a **bounded Sol Extra High adjudication checkpoint**, then downshift for repository execution. Escalate to Sol Max only if material contradictions remain unresolved.

Three Claude-side constraints worth knowing when writing the handoff (source A, 2026-07-28): Fable 5 always thinks and cannot have thinking disabled; Fable 5 requires 30-day data retention, so an organization on zero data retention gets a hard failure on every Fable call and Opus 5 is the only viable tier there; and neither model returns its raw chain of thought, so the report you get back is written output, not a reasoning trace.

Require the Fable handoff to contain:

- inspected repository paths and base SHA, or an explicit statement that repository access was unavailable;
- official external sources and dates;
- facts separated from inference and recommendation;
- disagreements, counterarguments, and rejected alternatives;
- unresolved questions and confidence levels;
- proposed file-level migration scope;
- tests and acceptance criteria;
- a concise implementation handoff for GPT-5.6 Sol.

## Common Cretas routes

| Task | Recommended route |
|---|---|
| Daily question, status, small familiar change | Sol Medium |
| Tiny high-volume or mechanical task | Luna Light/Medium |
| Clear reversible implementation with tests | Sol Medium, or Luna High/Extra High when conserving allowance |
| Ambiguous bug or unfamiliar cross-module work | Sol High |
| Read-only PR/Git audit, no-candidate daily integration pass | Sol Medium |
| Real PR candidate with conflicts, integration risk, or authorized repository mutation | Sol High; the word “PR” alone never raises effort |
| Routine SOP/RAG or maintenance automation | Sol Medium for read-only/no-op; Sol High for a bounded change plus validation; never standing XHigh |
| Multi-module work with explicit contracts and target tests | Sol Medium or High according to ambiguity and verification cost, not module count |
| Production read-only diagnosis | Sol Medium or High according to ambiguity; production naming alone does not require XHigh |
| Production write or release with established gates and rollback | Sol High cruise effort |
| Unresolved architecture, security, irreversible migration, or data-consistency decision | Sol Extra High for the named decision checkpoint only, then downshift |
| Established design-system or multi-screen implementation | Sol Medium or High; “design” and screen count alone do not require XHigh |
| Any sustained Cretas task approaching context/compact limit | Keep the lowest sufficient Medium/High cruise effort; warn once near 50%, then auto-create and dispatch a fresh continuation task near 80% or after the second compact. Goal is not required; never keep XHigh merely to avoid handoff |
| Independent external architecture challenge | **Opus 5 High**; use Opus 5 XHigh for the full multi-project report; the routing reply must include the paste-ready Claude Code prompt, then a bounded Sol Extra High adjudication checkpoint |
| Opus 5 already stalled or self-contradicted on that same challenge | Fable 5 High/XHigh as break-glass, per the earned gate in `.claude/skills/multi-model-dispatch` |
| Alternative exploratory/reviewer pass | Terra Medium, with an explicit reason |
| Extra High failed on a high-stakes decision | Sol Max |
| Several genuinely independent workstreams | Sol Ultra only with explicit approval and repository coordination gates |

## Sources

### A — Official and identified product-team guidance

- [OpenAI GPT-5.6 launch](https://openai.com/index/gpt-5-6/) — Sol/Terra/Luna positioning; Max depth; Ultra four-agent behavior.
- [OpenAI GPT-5.6 help](https://help.openai.com/en/articles/20001354-gpt-56-in-chatgpt) — product availability and effort labels.
- [OpenAI Codex team AMA](https://www.reddit.com/r/codex/comments/1us9ty9/ama_with_openais_codex_team/) — identified team members recommend Sol Medium for clear bugs/features, stronger Sol for ambiguity, and lighter models for quick work.
- [Tibo's GPT-5.6 routing recommendation](https://x.com/thsottiaux/status/2075581430055493909) — Sol Medium daily driver, Extra High for genuinely hard problems, Ultra when maximum result is worth rapid allowance use.
- [Anthropic effort documentation](https://platform.claude.com/docs/en/build-with-claude/effort) — Fable High default, XHigh capability-sensitive, Max unconstrained, Ultracode semantics.
- [Anthropic Fable prompting guidance](https://platform.claude.com/docs/en/build-with-claude/prompt-engineering/prompting-claude-fable-5) — higher-effort strengths, overthinking/overbuilding risk, and long-horizon behavior.
- [Anthropic Fable product page](https://www.anthropic.com/claude/fable) — intended long-running research, coding, enterprise workflow, and self-verification use.
- [Anthropic model overview](https://platform.claude.com/docs/en/about-claude/models/overview) — Sonnet 5 / Opus 5 / Fable 5 identifiers, context windows, pricing, and effort support (checked 2026-07-28).
- Repository gate `.claude/skills/multi-model-dispatch` — the earned-not-predicted ladder, five escalation landing points, three pre-authorized bypasses, and the frequency cap that this matrix defers to for Claude tier selection.

### B — Independent evaluation

- [Artificial Analysis: GPT-5.6 intelligence vs cost](https://artificialanalysis.ai/articles/gpt-5-6-intelligence-vs-cost-across-sol-terra-luna) — Sol and Luna currently Pareto-dominate Terra across reported effort points.
- [Artificial Analysis GPT-5.6 benchmark overview](https://artificialanalysis.ai/articles/gpt-5-6-has-landed) — capability, coding-agent, cost, and effort comparisons.
- [METR predeployment evaluation](https://metr.org/blog/2026-06-26-gpt-5-6-sol/) — benchmark caveat: its Sol time-horizon estimate was confounded by unusually high detected reward-hacking/cheating behavior.

### C — Community sampling

- [Reddit early Sol/Terra/Luna routing guide and discussion](https://www.reddit.com/r/codex/comments/1utzi5w/gpt56_sol_vs_terra_vs_luna_my_early_guide_to/) — Luna daily-driver reports, Terra disagreement, Sol escalation, and blast-radius routing insight.
- [Reddit Terra incident and model comparison discussion](https://www.reddit.com/r/codex/comments/1ut3u5l/very_bad_first_experience_with_gpt_56_terra/) — one destructive incident plus broader Luna/Sol/Terra comparisons; anecdotal, not a rate estimate.
- [Reddit GPT-5.6 experience thread](https://www.reddit.com/r/codex/comments/1us2mpo/your_experience_using_56/) and [Ultra usage report](https://www.reddit.com/r/codex/comments/1uv7ui6/codex_56_sol_at_ultra_used_100_of_my_weekly_usage/) — repeated allowance-burn reports for higher Sol efforts, especially Ultra.
- [Reddit Fable effort discussion](https://www.reddit.com/r/ClaudeAI/comments/1ul5fw5/what_fable_5_effort_are_you_using_and_why/) — High/XHigh/Max user trade-offs.
- [Reddit Fable High/XHigh summary](https://www.reddit.com/r/ClaudeCodeTLDR/comments/1ura3et/tldr_which_reasoning_mode_do_you_use_with_fable_5/) — community favors High for most work and reports overthinking/cost above it; automated summary, therefore low-confidence.
- X community sampling through public profile mirrors, including [Luna High/XHigh and Sol High routing reports](https://twstalker.com/Puneet_singh_tw), [Sol/Luna value comparisons](https://twstalker.com/Stardddff), and [Ultra usage observations](https://mobile.twstalker.com/novagkwatch). Treat mirrors and self-reports as low-confidence discovery evidence.

### E — Cretas local telemetry

- Rolling window: 2026-07-22 11:39 through 2026-07-29 11:39 Asia/Singapore.
- Method: a targeted streaming parser inspected 116 local rollout JSONL files (about 2.38 GB), treated the first `session_meta` as authoritative, excluded forked subagent transcripts, and retained 39 root sessions with 851 model turns. Prompt text was sanitized; no credentials or private payloads were retained in this matrix.
- Actual dominant session effort: 16 XHigh, 18 High, 4 Medium, and 1 unobservable. All 13 recurring automation sessions were XHigh.
- Recoverable explicit recommendations: 8 Extra High, 4 High, and 1 Medium. Four of the eight Extra High recommendations actually ran primarily at High or Medium, showing that the old labels were not a reliable compatibility rule.
- Repeated misuse patterns: read-only/no-candidate PR automation at XHigh; routine SOP/RAG maintenance at XHigh; multi-module count treated as complexity; established design work escalated by keyword; and XHigh retained across long implementation, test, wait, PR, and deployment phases.
- Token totals in this audit are cumulative session telemetry, not credit or billing totals. They are suitable for comparing context exposure and compaction pressure, not for claiming an exact quota-saving percentage.

## Refresh triggers

Refresh this matrix when any of the following occurs:

- the live picker adds or removes a model/effort combination;
- OpenAI or Anthropic changes effort semantics, pricing, context, or multiagent behavior;
- a material Codex/Claude Code harness update changes subagent routing;
- the repository-side Claude gate in `.claude/skills/multi-model-dispatch` changes its tier ladder, escalation thresholds, or pre-authorized bypass list;
- independent evaluations add stable task-level results;
- Cretas accumulates at least ten comparable task outcomes that contradict a route above.
