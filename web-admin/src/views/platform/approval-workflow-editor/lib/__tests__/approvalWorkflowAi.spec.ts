import { describe, expect, it } from 'vitest'
import type { ApprovalDirectory } from '@/api/approvalWorkflow'
import { compileApprovalWorkflowAiDraft } from '../approvalWorkflowAi'

const directory: ApprovalDirectory = {
  roles: [
    { name: 'procurement_manager', displayName: '采购主管' },
    { name: 'factory_super_admin', displayName: '工厂总管理员' },
  ],
  users: [
    { id: 42, username: 'liushanmen_admin', fullName: '六扇门管理员', isActive: true },
  ],
}

function compile(spec: unknown) {
  return compileApprovalWorkflowAiDraft({
    spec,
    currentName: '采购订单审批',
    currentNodes: [],
    currentEdges: [],
    decisionType: 'PURCHASE_ORDER_APPROVAL',
    directory,
  })
}

describe('approval workflow AI draft compiler', () => {
  it('maps only real factory roles and users into a reversible local draft', () => {
    const draft = compile({
      name: '采购订单审批',
      startNodeId: 'start',
      nodes: [
        { id: 'start', type: 'start', label: '开始', config: {} },
        {
          id: 'approval_admin',
          type: 'approval',
          label: '管理员审批',
          config: {
            approverRoles: ['factory_super_admin'],
            approverUserIds: ['42'],
            requiredApprovers: 1,
            timeoutMinutes: 720,
          },
        },
        { id: 'end', type: 'end', label: '审批通过', config: { outcome: 'APPROVED' } },
      ],
      edges: [
        { id: 'edge_1', source: 'start', target: 'approval_admin', priority: 0 },
        { id: 'edge_2', source: 'approval_admin', target: 'end', priority: 0 },
      ],
    })

    expect(draft.nodes[1].config).toMatchObject({
      approverRoles: ['factory_super_admin'],
      approverRoleLabels: ['工厂总管理员'],
      approverUserIds: ['42'],
      approverUserLabels: ['六扇门管理员（liushanmen_admin）'],
      timeoutMinutes: 720,
    })
  })

  it('rejects an approver that is not in the current factory directory', () => {
    expect(() => compile({
      startNodeId: 'start',
      nodes: [
        { id: 'start', type: 'start', config: {} },
        {
          id: 'approval',
          type: 'approval',
          config: { approverUserIds: ['999'] },
        },
        { id: 'end', type: 'end', config: { outcome: 'APPROVED' } },
      ],
      edges: [
        { id: 'edge_1', source: 'start', target: 'approval' },
        { id: 'edge_2', source: 'approval', target: 'end' },
      ],
    })).toThrow('审批人不在当前工厂可选目录中')
  })

  it('rejects duplicate, self or cyclic connections before touching the draft', () => {
    expect(() => compile({
      startNodeId: 'start',
      nodes: [
        { id: 'start', type: 'start', config: {} },
        { id: 'approval_a', type: 'approval', config: {} },
        { id: 'approval_b', type: 'approval', config: {} },
        { id: 'end', type: 'end', config: { outcome: 'APPROVED' } },
      ],
      edges: [
        { id: 'edge_1', source: 'start', target: 'approval_a' },
        { id: 'edge_2', source: 'approval_a', target: 'approval_b' },
        { id: 'edge_3', source: 'approval_b', target: 'approval_a' },
        { id: 'edge_4', source: 'approval_b', target: 'end' },
      ],
    })).toThrow('审批画布不能形成循环')
  })
})
