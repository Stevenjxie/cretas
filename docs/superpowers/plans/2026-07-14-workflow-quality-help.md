# Workflow Quality Help Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add separate quality-inspection help affordances to the finished-product and raw-material workflow modes.

**Architecture:** Keep the behavior in the product-process configuration header. Each mode label owns an Element Plus tooltip with business-accurate copy; selection, workflow data, and APIs remain unchanged.

**Tech Stack:** Vue 3, Element Plus, Vitest, Vue Test Utils.

## Global Constraints

- Do not change workflow selection, graph validation, API contracts, or backend behavior.
- Finished mode has one finished-product output and supports multiple raw-material inputs.
- Raw-material mode has one raw-material output and supports multiple finished-product outputs.

---

### Task 1: Mode quality-help UI and regression coverage

**Files:**

- Modify: `web-admin/src/views/system/product-processes/index.vue`
- Create: `web-admin/src/views/system/product-processes/__tests__/workflowModeQualityHelp.spec.ts`

**Interfaces:**

- Consumes: `ownerMode` and `QuestionFilled`.
- Produces: accessible triggers `成品质检说明` and `原料质检说明`.

- [ ] Write a failing component test asserting both labelled triggers and both approved descriptions.
- [ ] Run the one test and confirm it fails because the triggers and descriptions are absent.
- [ ] Add one `el-tooltip` beside each existing mode button, with no change to the radio-group selection handlers.
- [ ] Run the focused test, the workflow-editor suite, and `npm run build:check`.
- [ ] Commit the focused implementation and this plan.
