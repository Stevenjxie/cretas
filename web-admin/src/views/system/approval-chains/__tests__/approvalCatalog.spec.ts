import { describe, expect, it } from 'vitest'
import type {
  ApprovalWorkflowDTO,
  DecisionTypeMetadataDTO,
} from '@/api/approvalWorkflow'
import { isDecisionType } from '@/api/approvalWorkflow'
import {
  buildApprovalCatalog,
  buildOaCanvasQuery,
  selectPreferredWorkflow,
} from '../approvalCatalog'

const metadata: DecisionTypeMetadataDTO[] = [
  {
    decisionType: 'PURCHASE_ORDER_APPROVAL',
    chineseName: '采购订单审批',
    description: '采购订单提交后的审批流程',
    category: 'PURCHASE_SUPPLIER',
    defaultApproverRoles: ['factory_super_admin'],
    moduleCode: 'purchase_order',
    wired: true,
  },
]

function workflow(
  overrides: Partial<ApprovalWorkflowDTO>,
): ApprovalWorkflowDTO {
  return {
    id: 'workflow-default',
    factoryId: 'F006',
    decisionType: 'PURCHASE_ORDER_APPROVAL',
    name: '采购审批',
    nodesJson: '[]',
    edgesJson: '[]',
    startNodeId: 'start',
    version: 1,
    publishStatus: 'draft',
    enabled: false,
    priority: 0,
    ...overrides,
  }
}

describe('approval business catalog', () => {
  it('accepts every supported deep-link type and rejects arbitrary query values', () => {
    expect(isDecisionType('PURCHASE_ORDER_APPROVAL')).toBe(true)
    expect(isDecisionType('PRODUCTION_REVERSAL_APPROVAL')).toBe(true)
    expect(isDecisionType('RESTAURANT_AGENT_ACTION_REVIEW')).toBe(true)
    expect(isDecisionType('NOT_A_DECISION_TYPE')).toBe(false)
  })

  it('opens the editable draft while showing that the published workflow remains active', () => {
    const active = workflow({
      id: 'active',
      version: 2,
      publishStatus: 'published',
      enabled: true,
    })
    const draft = workflow({ id: 'draft', version: 9, publishStatus: 'draft' })

    expect(selectPreferredWorkflow([draft, active])?.id).toBe('draft')

    const [item] = buildApprovalCatalog(metadata, [draft, active], [
      { decisionType: 'PURCHASE_ORDER_APPROVAL', enabled: true },
    ])
    expect(item).toMatchObject({
      status: 'active-with-draft',
      preferredWorkflowId: 'draft',
      workflowCount: 2,
      legacyCount: 1,
    })
  })

  it('deep-links directly to the matching OA canvas workflow', () => {
    const [item] = buildApprovalCatalog(metadata, [
      workflow({ id: 'purchase-flow', publishStatus: 'published', enabled: true }),
    ], [])

    expect(buildOaCanvasQuery(item)).toEqual({
      tab: 'approval',
      decisionType: 'PURCHASE_ORDER_APPROVAL',
      workflowId: 'purchase-flow',
      source: 'approval-chains',
    })
  })

  it('opens a business-scoped empty canvas when no workflow exists', () => {
    const [item] = buildApprovalCatalog(metadata, [], [])

    expect(item.status).toBe('unconfigured')
    expect(buildOaCanvasQuery(item)).toEqual({
      tab: 'approval',
      decisionType: 'PURCHASE_ORDER_APPROVAL',
      source: 'approval-chains',
    })
  })
})
