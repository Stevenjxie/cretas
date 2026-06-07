---
name: agent-team
description: 编排多角色 Agent Team 深度研究工作流（研究员×2-3并行 + 浏览器探索 → 分析师 → 批评者 → 整合者 → 自愈验证）。融合 Playwright Test Agents 的 Planner/Healer 模式。适用于技术调研、方案对比、竞品分析、UI/UX 评估等任务。使用 /agent-team <研究主题> 触发。
allowed-tools:
  - Bash
  - Read
  - Grep
  - Glob
  - WebSearch
  - WebFetch
  - Write
  - Task
---

# Agent Team — Deep Research Workflow

Orchestrate a multi-agent research and analysis workflow with parallel research, structured analysis, critical review, and integrated reporting.

## Trigger

User invokes `/agent-team <research topic or question>`

## Workflow

When triggered, execute the following phases in order. The argument after `/agent-team` is the **research topic**.

### Phase 0: Planning (You — the Manager)

1. Parse the research topic from the user's input
2. **Detect output language**: if the topic contains CJK characters (Chinese/Japanese/Korean), set `OUTPUT_LANGUAGE=Chinese`; otherwise set `OUTPUT_LANGUAGE=English`. Pass this to ALL agent prompts.
3. **Extract user context** (if available): check the current project's CLAUDE.md for tech stack, environment, and constraints. Summarize in 1-2 sentences as `USER_CONTEXT` to pass to Analyst and Critic.
4. **Detect codebase grounding need**: Set `CODEBASE_GROUNDING=true` if topic matches ANY:
   - 项目路径关键词: `backend/`, `frontend/`, `web-admin/`, `SmartBI`, `smartbi`, `TwoStage`, `AIIntentService`, `IntentExecutor`, `DashScope`, `chart_builder`, `insight_generator`, `python/`, `java/`, `agents/`, `skills/`, `vite.config`, `application.properties`, `main.py`, `KPICard`, `DashboardBuilder`
   - 中文所有权词: `我们的`、`本项目`、`当前系统`、`现有代码`、`项目里`、`已有的`、`系统里`、`项目中`、`我们现在`、`目前的`
   - 项目专有名词: `Cretas`、`AIMS`、`食品溯源`、`SmartBIAnalysis`、`KPICard`、`IntentDisambiguation`、`LinUCB`、`agent-team`、`CrossSheetAggregator`、`FinanceAnalysis`、`DemoTour`、`PythonSmartBI`
   - 代码检查动词: `怎么实现的`、`现在是怎么`、`代码里`、`源码`、`排查`、`为什么我们`、`看看代码`、`检查一下`、`分析一下我们`、`优化我们`、`review`、`audit`
   - 泛化模式: any mention of specific file names (e.g., `*.vue`, `*.java`, `*.py`, `*.ts`) or line numbers (e.g., `line 42`, `:245`)

   Default: `CODEBASE_GROUNDING=false`

   Pass to ALL agent prompts. When `CODEBASE_GROUNDING=true`, add to the announce block:
   > Grounding: ENABLED — agents will verify claims against actual source code
5. **Detect competitor topic**: Set `COMPETITOR_MODE=true` if topic matches ANY:
   - 竞品关键词: `竞品`、`竞争对手`、`competitor`、`vs`、`对比`、`比较`、`替代方案`、`alternative`
   - 产品比较: `A还是B`、`哪个更好`、`选型`、`选择`
   - 市场分析: `市场份额`、`market share`、`competitive landscape`

   Default: `COMPETITOR_MODE=false`
6. **Detect browser research need**: Set `BROWSER_RESEARCH=true` if topic matches ANY:
   - 页面/UI 关键词: `页面`、`UI`、`UX`、`界面`、`布局`、`样式`、`responsive`、`design`、`layout`
   - 实时浏览词: `看看网站`、`打开`、`访问`、`browse`、`explore site`、`check the site`、`看一下页面`
   - 竞品 UI 对比: `竞品页面`、`competitor UI`、`competitor site`、`看看他们的`、`对方的网站`
   - 包含 URL: `http://`、`https://`、`localhost`
   - 项目 Web UI: `web-admin`、`管理后台页面`、`前端页面`
   - 显式请求: `screenshot`、`截图`、`浏览器`、`browser`

   Default: `BROWSER_RESEARCH=false`

   Pass to ALL agent prompts. When `BROWSER_RESEARCH=true`, add to the announce block:
   > Browser: ENABLED — browser-explorer agent will interact with live pages
7. **Detect fact-check need**: Set `FACT_CHECK=true` when ANY of these conditions hold:
   - Topic involves technical specifications, benchmarks, or version numbers
   - Topic involves statistics, dates, or quantitative claims
   - User explicitly requests verification
   - `CODEBASE_GROUNDING=false` (external claims are more likely to be outdated)

   Default: `FACT_CHECK=true` in Full mode, `false` in Quick mode.
8. **Assess complexity** to select mode using these heuristics:
   - **Quick mode** (2 agents) — select when ALL of these are true:
     - Topic is a single factual question or explanation ("what is X", "explain Y", "how does Z work")
     - No comparison keywords: `vs`、`对比`、`比较`、`A还是B`、`哪个更好`、`优缺点`、`选择`
     - No multi-faceted structure (only 1 clear answer expected)
     - `CODEBASE_GROUNDING=false` (grounding topics are inherently multi-faceted → Full)
     → 1 Researcher (sonnet) + 1 Analyst-Critic combo (opus)
     → Skip Integrator, Manager directly presents
   - **Full mode** (5+ agents) — select when ANY of these are true:
     - Contains comparison keywords (`vs`、`对比`、`比较`、`A还是B`、`哪个更好`、`tradeoff`)
     - Strategic/architectural decision ("should we", "应该用", "迁移", "重构", "migrate")
     - Multi-faceted topic requiring 2+ research angles
     - `CODEBASE_GROUNDING=true`
     → Full 4-phase pipeline as below
   - **When in doubt, choose Full mode** — it's better to over-research than under-research
9. Break the topic into 2-3 **independent research angles** suitable for parallel investigation
10. Announce the plan to the user:
   ```
   ## Agent Team: [topic]

   Mode: [Quick/Full] | Language: [Chinese/English]
   Enhancements: [Competitor profiles: ON/OFF] | [Fact-check: ON/OFF] | [Browser research: ON/OFF]

   Research angles:
   1. [Angle A] — official docs & technical specs
   2. [Angle B] — community experience & real-world usage
   3. [Angle C] — alternatives & competitive landscape  (Full mode only)
   4. [Angle D] — live browser exploration  (only when BROWSER_RESEARCH=true)

   Phases: Research (parallel) [+ Browser] → Analysis → Critique → Integration [→ Fact-Check] [→ Heal]
   ```

---

### Phase 1: Parallel Research (Researcher agents)

Launch 2-3 **Task** tool calls **in parallel**, each using `subagent_type: "general-purpose"` and `model: "sonnet"`. Each task should:

- Reference the `researcher` agent role
- Assign a specific research angle
- Include the research topic and any constraints from the user
- Set `max_turns: 12` when `CODEBASE_GROUNDING=true` (code exploration needs extra turns), otherwise `max_turns: 8`
- Include the output language instruction

**Prompt template for each researcher:**
```
You are acting as a Researcher agent. Read .claude/agents/researcher.md for your full role definition and output format.

Research topic: [TOPIC]
Your angle: [SPECIFIC ANGLE]
Output language: [OUTPUT_LANGUAGE] — write your ENTIRE output in this language.

Instructions:
1. Search for sources relevant to your angle on this topic
2. Collect up to 8 findings with exact quotes and source URLs
3. Rate each source's reliability (1-5 stars)
4. Note any contradictions between sources
5. Output in the exact format specified in researcher.md

[ONLY include the following block when CODEBASE_GROUNDING=true]
Codebase grounding: ENABLED.
MANDATORY: Before any web search, spend your first 2-3 turns exploring the actual codebase:
- Use Glob to locate relevant source files (e.g., **/*SmartBI*.vue, **/*chart_builder*.py)
- Use Grep to find class/function/config references
- Use Read to examine the 2-4 most relevant files in detail
Document your codebase findings as "Codebase Evidence" (★★★★★ reliability) FIRST in your Key Findings table, then supplement with web search sources.
[End conditional block]

[ONLY include the following block when COMPETITOR_MODE=true]
Competitor analysis mode: ENABLED.
MANDATORY additional steps:
- For each competitor/option found, collect:
  1. Official repository/website URL
  2. GitHub stars / npm downloads / adoption metrics (if applicable)
  3. Last release date and version
  4. Key differentiators (3-5 bullet points)
  5. Known limitations or complaints from community
- Organize findings as a "Competitor Profile" subsection within your output
- Cite actual repository/documentation pages (★★★★★) over blog opinions (★★★☆☆)
- If a competitor has a public GitHub repo, note: repo URL, language, license, contributor count
[End conditional block]

CRITICAL: Your FINAL message must be your complete structured output (starting with "## Researcher Output"). Do NOT end with a question or status update. The LAST thing you write must be the full formatted report.
```

#### Phase 1B: Browser Explorer (parallel with researchers) — when BROWSER_RESEARCH=true

Launch an **additional** Task call **in parallel** with the researchers, using `subagent_type: "general-purpose"` and `model: "sonnet"`:

**Prompt template:**
```
You are acting as a Browser Explorer agent. Read .claude/agents/browser-explorer.md for your full role definition and output format.

Research topic: [TOPIC]
Output language: [OUTPUT_LANGUAGE] — write your ENTIRE output in this language.

Target URLs to explore:
[LIST URLs FROM TOPIC — e.g., competitor URLs, project URLs like http://47.100.235.168:8088, or localhost URLs]

Instructions:
1. Navigate to each target URL
2. Use browser_snapshot to understand page structure (prefer over screenshots)
3. Interact with key UI elements (click, navigate, fill forms if needed)
4. Take screenshots ONLY for key visual evidence
5. Extract any structured data (pricing, feature tables, etc.)
6. Output in the exact format specified in browser-explorer.md

[ONLY include when exploring our own project]
Project exploration mode: ENABLED.
Login credentials (if needed): username=[from CLAUDE.md test accounts], password=[from CLAUDE.md]
After login, systematically explore the main navigation sections.
Cross-reference what you see with local source files using Read/Grep.
[End conditional block]

CRITICAL: Your FINAL message must be your complete structured output (starting with "## Browser Explorer Output"). Do NOT end with a question. Always call browser_close when done.
```

Set `max_turns: 10` (browser interaction needs more turns).

**Browser findings integration**: Compress browser explorer output and include it alongside researcher outputs when passing to the Analyst:
```
=== BROWSER EXPLORER (condensed) ===
1. [🖼️] Finding summary — URL: ...
2. [📝] Finding summary — URL: ...
...
```

**After Phase 1, output progress to the user:**
> Research complete — N researchers collected M findings from X sources. [+ Browser explorer visited N pages.] (if BROWSER_RESEARCH)

**Output validation** (Healer-inspired — applies to ALL agents in Phase 1):
For each agent (researchers + browser explorer), apply this validation loop:
1. Check if output contains the expected format marker (`## Researcher Output` or `## Browser Explorer Output`)
2. If missing: resume that agent once with specific feedback about what's missing
3. If second attempt also fails: extract any partial findings, prefix with `[PARTIAL — incomplete output]`
4. **Content validation** (new — inspired by Playwright Healer): beyond header check, verify:
   - Key Findings table has at least 2 rows (not just headers)
   - Source URLs or file paths are present (not placeholder text)
   - If both checks fail after resume, mark as `[EMPTY — no usable data]` and note the gap for the Analyst

---

### Phase 2: Analysis (Analyst agent) — Full mode only

**Compress researcher outputs** before passing downstream: convert each researcher's findings to compact format (1 line per finding):
```
=== RESEARCHER A (condensed) ===
1. [★★★★★] Finding summary — source: URL
2. [★★★★☆] Finding summary — source: URL
...
```

Launch a single **Task** call with `subagent_type: "general-purpose"` and `model: "opus"`:

**Prompt template:**
```
You are acting as an Analyst agent. Read .claude/agents/analyst.md for your full role definition and output format.

Research topic: [TOPIC]
Output language: [OUTPUT_LANGUAGE] — write your ENTIRE output in this language.
User context: [USER_CONTEXT]

Below are the condensed research findings from our team:

=== RESEARCHER A (condensed) ===
[paste condensed findings — 1 line per finding, max 8 lines]

=== RESEARCHER B (condensed) ===
[paste condensed findings]

=== RESEARCHER C (condensed) ===  (if applicable)
[paste condensed findings]

Instructions:
1. Create a comparison matrix covering all options found
2. Identify patterns across the research
3. Build a decision framework for the user's specific context
4. Assess risks and data gaps
5. Output in the exact format specified in analyst.md

CRITICAL: Your FINAL message must be your complete structured output (starting with "## Analyst Output"). Do NOT end with a question or status update. The LAST thing you write must be the full formatted report.
```

**After Phase 2, output progress to the user:**
> Analysis complete — comparison matrix covers N dimensions across M options.

**Output validation**: If the Task result does NOT contain "## Analyst Output", resume that agent once with feedback specifying what's missing. If still incomplete, extract partial content, mark as `[PARTIAL]`, and pass downstream with a quality warning to the Critic.

---

### Phase 2-Quick: Analysis + Critique combo — Quick mode only

Launch a single **Task** call with `subagent_type: "general-purpose"` and `model: "opus"`:

**Prompt template:**
```
You are acting as a combined Analyst + Critic agent. Read both .claude/agents/analyst.md and .claude/agents/critic.md for role definitions.

Research topic: [TOPIC]
Output language: [OUTPUT_LANGUAGE] — write your ENTIRE output in this language.
User context: [USER_CONTEXT]

Below are the research findings:

=== RESEARCHER (condensed) ===
[paste condensed findings]

Instructions — do BOTH:

PART 1 (Analyst): Create a structured analysis with comparison matrix, decision framework, and risk assessment per analyst.md format.

PART 2 (Critic): Now adopt a completely different perspective — imagine you are a skeptical external reviewer who has NOT seen PART 1's reasoning process. Re-examine the evidence independently. Challenge claims with weak evidence; verify and confirm claims with strong evidence. Provide revised confidence levels per critic.md format.

Output both parts in sequence: "## Analyst Output" followed by "## Critic Output".

[ONLY include the following block when CODEBASE_GROUNDING=true]
Codebase grounding: ENABLED.
For PART 1 (Analyst): Your Comparison Matrix first column must be "当前实现" citing actual code file:line references from the Researcher's codebase evidence.
For PART 2 (Critic): Before writing challenges, use Read/Grep to verify at least 2 code-related claims. Add a "### Code Verification" table before your challenges.
[End conditional block]

CRITICAL: Your FINAL message must contain both "## Analyst Output" and "## Critic Output" sections. Do NOT end with a question.
```

**After Phase 2-Quick, output progress to the user:**
> Analysis & critique complete.

Then skip to **Phase 5** (Present Results) — the Manager synthesizes directly instead of using the Integrator.

---

### Phase 3: Critique (Critic agent) — Full mode only

Launch a single **Task** call with `subagent_type: "general-purpose"` and `model: "opus"`:

**Prompt template:**
```
You are acting as a Critic agent. Read .claude/agents/critic.md for your full role definition and output format.

Research topic: [TOPIC]
Output language: [OUTPUT_LANGUAGE] — write your ENTIRE output in this language.
User context: [USER_CONTEXT]

Below is the analysis to critique:

=== ANALYST OUTPUT (condensed) ===
[Compress the Analyst output: keep Summary in full, convert Comparison Matrix to 1-line-per-row, keep Decision Framework and Risk Assessment in full (Critic needs these for verification), drop Strengths & Weaknesses and Data Gaps sections]

=== KEY RESEARCH FINDINGS (condensed) ===
[paste condensed researcher findings — 1 line per finding]

Instructions:
1. Challenge at least 2 major conclusions from the Analyst
2. Search for counterexamples and failure stories
3. Identify hidden assumptions
4. Rate failure modes by probability and impact
5. Provide revised confidence levels
6. Output in the exact format specified in critic.md

[ONLY include the following block when CODEBASE_GROUNDING=true]
Codebase grounding: ENABLED.
MANDATORY: Before writing challenges, verify at least 3 code-related claims from the Analyst by using Read/Grep on actual source files.
Any unverified claim about project behavior or implementation is an automatic challenge target.
Add a "## Code Verification" section BEFORE "## Challenges to Key Claims" in your output.
[End conditional block]

CRITICAL: Your FINAL message must be your complete structured output (starting with "## Critic Output"). Do NOT end with a question or status update. The LAST thing you write must be the full formatted report.
```

**After Phase 3, output progress to the user:**
> Critique complete — challenged N conclusions, found M counterarguments.

**Output validation**: If the Task result does NOT contain "## Critic Output", resume that agent once with feedback specifying what's missing. If still incomplete, extract partial content, mark as `[PARTIAL]`, and pass downstream.

---

### Phase 4: Integration (Integrator agent) — Full mode only

Launch a single **Task** call with `subagent_type: "general-purpose"` and `model: "opus"`:

**Prompt template:**
```
You are acting as an Integrator agent. Read .claude/agents/integrator.md for your full role definition and output format.

Research topic: [TOPIC]
Output language: [OUTPUT_LANGUAGE] — write your ENTIRE output in this language.

Below are all team outputs to synthesize:

=== RESEARCHER OUTPUTS (condensed) ===
[paste condensed findings from all researchers]

=== ANALYST OUTPUT (condensed) ===
[Compress: Summary + Comparison Matrix (1-line-per-row) + Decision Framework key points + Risk top items. Drop verbose Strengths/Weaknesses prose.]

=== CRITIC OUTPUT (condensed) ===
[Compress: Overall Assessment + Challenges table (1-line-per-challenge) + Strongest Counterargument + Revised Confidence table. Drop verbose Hidden Assumptions and Failure Modes prose.]

Instructions:
1. Create an executive summary (under 100 words)
2. Map consensus and disagreements across agents
3. Assign final confidence levels
4. Provide actionable recommendations (immediate, short-term, conditional)
5. List open questions
6. Output in the exact format specified in integrator.md

CRITICAL: Your FINAL message must be your complete structured output (starting with "## Final Integrated Report"). Do NOT end with a question or status update. The LAST thing you write must be the full formatted report.
```

**Output validation**: If the Task result does NOT contain "## Final Integrated Report", resume that agent once with feedback specifying what's missing. If still incomplete, the Manager should synthesize directly from the Analyst and Critic outputs rather than presenting an empty report.

---

### Phase 4.5: Fact Verification (Optional) — Full mode only

**Activation**: Run this phase when `FACT_CHECK=true` (see Phase 0 step 6).

Launch a single **Task** call with `subagent_type: "general-purpose"` and `model: "sonnet"`:

**Prompt template:**
```
You are a Fact-Check Verification agent. Your job is to verify factual claims in a research report.

Research topic: [TOPIC]
Output language: [OUTPUT_LANGUAGE] — write your ENTIRE output in this language.

Below is the integrated report to verify:

=== INTEGRATED REPORT (condensed) ===
[paste the Integrator's Executive Summary + Confidence Assessment + Recommendations sections]

Instructions:
1. Extract ALL verifiable factual claims from the report. Focus on:
   - Version numbers (e.g., "React 18.3", "Python 3.12")
   - Release dates (e.g., "released in March 2025")
   - Statistics and percentages (e.g., "70% market share", "2x faster")
   - Technical specifications (e.g., "supports WebSocket", "uses AES-256")
   - Benchmark numbers (e.g., "500ms latency", "10k requests/sec")
   - Company/project claims (e.g., "acquired by X", "open-sourced in 2024")
2. For EACH claim, use WebSearch to verify against official sources (docs, GitHub, release notes)
3. Rate each claim: ✅ Verified | ❌ Incorrect | ⚠️ Outdated | ❓ Unverifiable
4. For ❌ or ⚠️ claims, provide the correct information with source URL

Output format:
## Fact-Check Report

| # | Claim | Status | Source | Correction (if needed) |
|---|-------|--------|--------|----------------------|
| 1 | [claim text] | ✅/❌/⚠️/❓ | [URL] | [correction or "—"] |
| 2 | ... | ... | ... | ... |

### Summary
- Total claims checked: N
- ✅ Verified: N
- ❌ Incorrect: N
- ⚠️ Outdated: N
- ❓ Unverifiable: N

### Critical Issues (if any)
[List any ❌ claims that should be corrected in the report]

CRITICAL: Your FINAL message must be your complete structured output (starting with "## Fact-Check Report"). Do NOT end with a question or status update.
```

Set `max_turns: 8` (needs multiple WebSearch rounds).

**After Phase 4.5, output progress to the user:**
> Fact-check complete — N claims verified, M issues found.

**Output integration**:
- If any ❌ (incorrect) claims were found, insert a warning block after the Executive Summary in Phase 5:
  ```
  > ⚠️ **Fact-check notice**: M claims were found to be incorrect or outdated. See corrections below.
  ```
- Append the full Fact-Check Report as an appendix at the bottom of the saved report file
- Add fact-check stats to the Process Note (see Phase 5)

**Output validation**: If the Task result does NOT contain "## Fact-Check Report", skip fact-check gracefully — note "Fact-check: skipped (agent error)" in the Process Note and proceed to Phase 5.

---

### Phase 5: Present Results (You — the Manager)

After all agents complete, **first run Phase 5.5 (Self-Healing Validation)** below, then:

1. Present the **Executive Summary** to the user first
2. Then the full integrated report (from Integrator in Full mode, or your own synthesis in Quick mode)
3. Add a brief **Process Note**:
   ```
   ---
   ### Process Note
   - Mode: Quick/Full
   - Researchers deployed: N
   - Browser explorer: ON/OFF [N pages visited] (if applicable)
   - Total sources found: N
   - Key disagreements: N resolved, N unresolved
   - Phases completed: Research [+ Browser] → Analysis → Critique → Integration [→ Fact-Check] [→ Heal]
   - Fact-check: N claims verified, M issues found (if applicable, or "disabled" for Quick mode)
   - Healer: N checks passed, M auto-fixed (or "all passed ✅")
   - Competitor profiles: N competitors analyzed (if applicable, or "N/A")
   ```
4. **Save the full report** using the Write tool to:
   `.claude/agent-team-outputs/YYYY-MM-DD_topic-slug.md`
   where `topic-slug` is a kebab-case summary of the research topic (max 50 chars).
   This allows the user to review results later across sessions.
5. Inform the user: "Report saved to `.claude/agent-team-outputs/[filename]`"
6. Ask: "Would you like me to dive deeper into any section, or run additional research on specific points?"

---

### Phase 5.5: Self-Healing Validation (Healer pattern) — All modes

**Design inspiration**: Playwright Test Agents' Healer — run → detect failure → diagnose → fix → re-run → repeat until success.

After all phases complete but BEFORE presenting results, the Manager performs a self-healing validation pass on the assembled report:

**Validation checklist:**

| Check | What to Verify | Auto-Heal Action |
|-------|---------------|-----------------|
| Structural completeness | All expected sections present (Executive Summary, Comparison Matrix, Recommendations, etc.) | If missing, synthesize from available data |
| Cross-reference integrity | Analyst cites Researcher finding numbers that actually exist | Remove dangling references, note corrections |
| Confidence consistency | Integrator's confidence doesn't contradict Critic's revised confidence without explanation | Add reconciliation note if mismatch detected |
| Actionable recommendations | Each recommendation has a concrete next step (not vague platitudes) | Flag vague items with `[需具体化]` |
| Browser evidence integration | If BROWSER_RESEARCH=true, browser findings appear in the analysis (not silently dropped) | Insert browser evidence into relevant sections |

**Healing loop** (max 1 iteration to avoid runaway):

1. Run all checks above against the assembled report
2. If any check fails:
   - Log which checks failed
   - Apply auto-heal actions inline
   - Add a `### Healer Notes` section at the bottom documenting what was fixed:
     ```markdown
     ### Healer Notes
     - [Fixed] Missing comparison matrix — synthesized from Researcher findings
     - [Fixed] Recommendation #3 was vague — added specific file paths
     - [Passed] All other checks OK
     ```
3. If all checks pass: add `### Healer Notes: All checks passed ✅` and proceed to present

**When NOT to heal** (important guardrails):
- Do NOT change agent conclusions or confidence levels — only fix structural/formatting issues
- Do NOT add new research findings — only reference what agents actually provided
- Do NOT re-run agent phases — the healer works on the assembled output only
- If the report is fundamentally incomplete (e.g., all agents returned `[EMPTY]`), skip healing and present what's available with a clear disclaimer

---

## Error Handling

- **No results**: If a researcher finds no results for their angle, note the gap and proceed with available data
- **No counterarguments**: If the critic cannot find weak claims to challenge, explicitly state "All major claims verified — high confidence" (this is a valid positive outcome)
- **Timeout**: If any agent does not return within 3 minutes (roughly 15-20 turns), consider it timed out. Summarize what was gathered and note the incomplete phase. For Researcher timeouts in parallel, proceed with the successful researchers' data.
- **Empty output**: If a Task returns empty output (no expected format marker), resume the agent once with specific feedback about what's missing. If still empty after resume, extract any partial content, mark as `[PARTIAL]`, and pass downstream. Do NOT silently drop data.
- **Partial parallel failure**: If 2 of 3 Researchers succeed but 1 fails/times out, proceed with the 2 successful outputs. Note the missing angle in the Analyst prompt so it can flag the gap.

## Tips

- For technical comparison topics, angles could be: (A) official docs, (B) benchmarks/performance, (C) developer experience/community
- For strategic decisions, angles could be: (A) technical feasibility, (B) cost/effort analysis, (C) market trends/competition
- For debugging/investigation, angles could be: (A) error patterns, (B) similar issues in community, (C) code analysis
- For UI/UX research with browser: (A) our current UI (browser exploration), (B) competitor UI patterns (browser + web search), (C) best practices (web search)
- Quick mode is ideal for: "what is MCP", "explain WebSockets", "how does X work"
- Full mode is ideal for: "React Native vs Flutter", "compare database options", "should we migrate to X"
- Browser mode is ideal for: "看看竞品的页面", "检查我们的 web-admin UI", "compare UI of X vs Y sites"

## Design Philosophy

This skill incorporates patterns from **Playwright Test Agents** (v1.56+):

| Playwright Pattern | Agent-Team Adaptation | Benefit |
|-------------------|----------------------|---------|
| **Planner** — explore app, generate spec | Phase 0 Planning + Phase 1B Browser Explorer | Structured discovery from live sources |
| **Generator** — spec → executable test | Phase 2-4 pipeline (findings → analysis → report) | Systematic transformation of raw data to insights |
| **Healer** — run → diagnose → fix → retry | Phase 5.5 Self-Healing Validation | Auto-fix structural issues, ensure report quality |

The Healer pattern is the most impactful addition: instead of just checking for format markers and giving up, the system now performs content validation and auto-repairs structural issues before presenting to the user.
