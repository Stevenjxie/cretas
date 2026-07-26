import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

function source(path: string): string {
  return readFileSync(resolve(process.cwd(), path), 'utf8')
}

describe('approval directory and business-mode UI contract', () => {
  const properties = source(
    'src/views/platform/approval-workflow-editor/components/PropertyPanel.vue',
  )
  const editor = source('src/views/platform/approval-workflow-editor/index.vue')
  const approvalNode = source(
    'src/views/platform/approval-workflow-editor/components/nodes/ApprovalNode.vue',
  )
  const ruleEditor = source(
    'src/views/platform/approval-workflow-editor/components/RuleEditor.vue',
  )
  const canvas = source('src/views/platform/canvas-editor/index.vue')
  const statusBar = source('src/views/platform/canvas-editor/components/StatusBar.vue')
  const diffViewer = source('src/views/platform/canvas-editor/components/ConfigDiffViewer.vue')

  it('uses strict factory-directory selects instead of free-form role or user id inputs', () => {
    expect(properties).toContain('getApprovalDirectory')
    expect(properties).toContain('v-model="approverUserIds"')
    expect(properties).toContain('v-model="delegateUserId"')
    expect(properties).not.toContain('multiple filterable allow-create')
    expect(properties).not.toContain('审批人 userId')
    expect(properties).not.toContain('逗号分隔')
    expect(properties).not.toContain('userId (如:')
    expect(ruleEditor).toContain('roleOptions')
    expect(ruleEditor).not.toContain('allow-create')
    expect(ruleEditor).not.toContain("value: 'factory_admin'")
  })

  it('fails closed when the directory is unavailable and explains configured self approval', () => {
    expect(properties).toContain('系统不会回退为手工填写角色代码或用户 ID')
    expect(properties).toContain(':disabled="Boolean(directoryError)"')
    expect(properties).toContain('只有“指定审批人”明确包含发起人本人时才允许自审')
  })

  it('does not render raw identity codes on approval nodes', () => {
    expect(approvalNode).toContain('approverRoleLabels')
    expect(approvalNode).toContain('approverUserLabels')
    expect(approvalNode).toContain("delegateUserLabel || '已指定人员'")
    expect(approvalNode).not.toContain("approverRoles.join")
    expect(approvalNode).not.toContain('委托: {{ delegateUserId }}')
  })

  it('hides technical expressions, raw rule tests, schema JSON, and diff values in business mode', () => {
    expect(properties).toContain('v-if="!props.businessMode"')
    expect(ruleEditor).toContain('v-if="!businessMode"')
    expect(editor).toContain('v-if="!props.lockDecisionType"')
    expect(editor).toContain('v-if="!props.lockDecisionType && simulatorOpen && simulatorInput"')
    expect(canvas).toContain(':hide-technical-details="approvalBusinessLocked"')
    expect(canvas).toContain(':show-technical-values="!approvalBusinessLocked"')
    expect(canvas).toContain('v-if="!approvalBusinessLocked" v-model="showSchemaPreview"')
    expect(statusBar).toContain('v-if="!hideTechnicalDetails"')
    expect(diffViewer).toContain('v-if="showTechnicalValues"')
  })
})
