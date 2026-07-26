import type {
  ApprovalWorkflowDTO,
  DecisionType,
  DecisionTypeCategory,
  DecisionTypeMetadataDTO,
} from '@/api/approvalWorkflow'

export type ApprovalCatalogStatus =
  | 'active'
  | 'active-with-draft'
  | 'draft'
  | 'published-disabled'
  | 'archived'
  | 'unconfigured'

export interface LegacyApprovalChainSummary {
  decisionType?: string
  enabled?: boolean
}

export interface ApprovalCatalogItem {
  decisionType: DecisionType
  chineseName: string
  description: string
  category: DecisionTypeCategory
  wired: boolean
  workflowCount: number
  legacyCount: number
  preferredWorkflowId?: string
  preferredWorkflowName?: string
  preferredWorkflowVersion?: number
  status: ApprovalCatalogStatus
}

const STATUS_RANK: Record<ApprovalCatalogStatus, number> = {
  active: 0,
  'active-with-draft': 1,
  draft: 2,
  'published-disabled': 3,
  archived: 4,
  unconfigured: 5,
}

function workflowRank(workflow: ApprovalWorkflowDTO): number {
  if (workflow.publishStatus === 'draft') return 0
  if (workflow.publishStatus === 'published' && workflow.enabled) return 1
  if (workflow.publishStatus === 'published') return 2
  return 3
}

export function selectPreferredWorkflow(
  workflows: ApprovalWorkflowDTO[],
): ApprovalWorkflowDTO | undefined {
  return [...workflows].sort((left, right) => (
    workflowRank(left) - workflowRank(right)
    || right.priority - left.priority
    || right.version - left.version
    || left.name.localeCompare(right.name, 'zh-CN')
  ))[0]
}

function resolveStatus(workflows: ApprovalWorkflowDTO[]): ApprovalCatalogStatus {
  const hasActive = workflows.some(
    (workflow) => workflow.publishStatus === 'published' && workflow.enabled,
  )
  const hasDraft = workflows.some((workflow) => workflow.publishStatus === 'draft')
  if (hasActive && hasDraft) return 'active-with-draft'
  if (hasActive) return 'active'
  if (hasDraft) return 'draft'
  if (workflows.some((workflow) => workflow.publishStatus === 'published')) {
    return 'published-disabled'
  }
  return workflows.length ? 'archived' : 'unconfigured'
}

export function buildApprovalCatalog(
  metadata: DecisionTypeMetadataDTO[],
  workflows: ApprovalWorkflowDTO[],
  legacyChains: LegacyApprovalChainSummary[],
): ApprovalCatalogItem[] {
  const workflowsByType = new Map<DecisionType, ApprovalWorkflowDTO[]>()
  for (const workflow of workflows) {
    const group = workflowsByType.get(workflow.decisionType) ?? []
    group.push(workflow)
    workflowsByType.set(workflow.decisionType, group)
  }

  const legacyCountByType = new Map<string, number>()
  for (const chain of legacyChains) {
    if (!chain.decisionType) continue
    legacyCountByType.set(
      chain.decisionType,
      (legacyCountByType.get(chain.decisionType) ?? 0) + 1,
    )
  }

  return metadata.map((item) => {
    const typeWorkflows = workflowsByType.get(item.decisionType) ?? []
    const preferred = selectPreferredWorkflow(typeWorkflows)
    return {
      decisionType: item.decisionType,
      chineseName: item.chineseName,
      description: item.description,
      category: item.category,
      wired: item.wired,
      workflowCount: typeWorkflows.length,
      legacyCount: legacyCountByType.get(item.decisionType) ?? 0,
      preferredWorkflowId: preferred?.id,
      preferredWorkflowName: preferred?.name,
      preferredWorkflowVersion: preferred?.version,
      status: resolveStatus(typeWorkflows),
    }
  }).sort((left, right) => (
    STATUS_RANK[left.status] - STATUS_RANK[right.status]
    || left.category.localeCompare(right.category)
    || left.chineseName.localeCompare(right.chineseName, 'zh-CN')
  ))
}

export function buildOaCanvasQuery(item: ApprovalCatalogItem): Record<string, string> {
  const query: Record<string, string> = {
    tab: 'approval',
    decisionType: item.decisionType,
    source: 'approval-chains',
  }
  if (item.preferredWorkflowId) {
    query.workflowId = item.preferredWorkflowId
  }
  return query
}
