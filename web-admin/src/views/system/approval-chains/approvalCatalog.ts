import type {
  ApprovalWorkflowDTO,
  ApprovalCutoverReadinessDTO,
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
  | 'legacy-migration-required'
  | 'canvas-conflict'
  | 'business-not-wired'
  | 'unconfigured'

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
  activeWorkflowVersion?: number
  draftWorkflowVersion?: number
  latestUpdatedAt?: string
  approvalEnabled: boolean
  hasDraft: boolean
  status: ApprovalCatalogStatus
}

const STATUS_RANK: Record<ApprovalCatalogStatus, number> = {
  active: 0,
  'active-with-draft': 1,
  draft: 2,
  'published-disabled': 3,
  archived: 4,
  'legacy-migration-required': 5,
  'canvas-conflict': 6,
  'business-not-wired': 7,
  unconfigured: 8,
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
  readiness: ApprovalCutoverReadinessDTO[],
): ApprovalCatalogItem[] {
  const workflowsByType = new Map<DecisionType, ApprovalWorkflowDTO[]>()
  for (const workflow of workflows) {
    const group = workflowsByType.get(workflow.decisionType) ?? []
    group.push(workflow)
    workflowsByType.set(workflow.decisionType, group)
  }

  const readinessByType = new Map(
    readiness.map((item) => [item.decisionType, item]),
  )

  return metadata.map((item) => {
    const typeWorkflows = workflowsByType.get(item.decisionType) ?? []
    const preferred = selectPreferredWorkflow(typeWorkflows)
    const active = [...typeWorkflows]
      .filter((workflow) => workflow.publishStatus === 'published' && workflow.enabled)
      .sort((left, right) => right.version - left.version)[0]
    const draft = [...typeWorkflows]
      .filter((workflow) => workflow.publishStatus === 'draft')
      .sort((left, right) => right.version - left.version)[0]
    const latestUpdatedAt = typeWorkflows
      .map((workflow) => workflow.updatedAt ?? workflow.createdAt)
      .filter((value): value is string => Boolean(value))
      .sort((left, right) => right.localeCompare(left))[0]
    const cutover = readinessByType.get(item.decisionType)
    const workflowStatus = resolveStatus(typeWorkflows)
    const status = cutover?.runtimeStatus === 'CANVAS_CONFLICT'
      ? 'canvas-conflict'
      : cutover?.runtimeStatus === 'BUSINESS_NOT_WIRED'
      ? 'business-not-wired'
      : cutover?.runtimeStatus === 'LEGACY_MIGRATION_REQUIRED'
        ? 'legacy-migration-required'
        : workflowStatus
    return {
      decisionType: item.decisionType,
      chineseName: item.chineseName,
      description: item.description,
      category: item.category,
      wired: item.wired,
      workflowCount: typeWorkflows.length,
      legacyCount: cutover?.legacyEnabled ? 1 : 0,
      preferredWorkflowId: preferred?.id,
      preferredWorkflowName: preferred?.name,
      preferredWorkflowVersion: preferred?.version,
      activeWorkflowVersion: active?.version,
      draftWorkflowVersion: draft?.version,
      latestUpdatedAt,
      approvalEnabled: cutover?.approvalRequired ?? Boolean(active),
      hasDraft: Boolean(draft),
      status,
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
