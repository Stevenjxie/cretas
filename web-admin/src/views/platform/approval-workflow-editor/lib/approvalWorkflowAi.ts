import type {
  ApprovalDirectory,
  ApprovalWorkflowEdge,
  ApprovalWorkflowNode,
  DecisionType,
  NodeType,
} from '@/api/approvalWorkflow'
import { formatUserLabel } from './approvalDirectory'

const NODE_TYPES = new Set<NodeType>([
  'start',
  'approval',
  'condition',
  'parallel',
  'join',
  'notify',
  'end',
])
const SAFE_ID = /^[A-Za-z0-9][A-Za-z0-9_-]{0,79}$/

const CONFIG_KEYS: Record<NodeType, Set<string>> = {
  start: new Set(),
  approval: new Set([
    'approverRoles',
    'approverUserIds',
    'requiredApprovers',
    'timeoutMinutes',
    'departmentIds',
    'delegateUserId',
  ]),
  condition: new Set(['description']),
  parallel: new Set(['description']),
  join: new Set(['mode', 'n']),
  notify: new Set(['notifyRoles', 'channels', 'notifyTemplate']),
  end: new Set(['outcome']),
}

export interface ApprovalWorkflowAiDraft {
  name: string
  startNodeId: string
  nodes: ApprovalWorkflowNode[]
  edges: ApprovalWorkflowEdge[]
}

export interface CompileApprovalWorkflowAiOptions {
  spec: unknown
  currentName: string
  currentNodes: ApprovalWorkflowNode[]
  currentEdges: ApprovalWorkflowEdge[]
  decisionType: DecisionType
  directory: ApprovalDirectory
}

export function compileApprovalWorkflowAiDraft(
  options: CompileApprovalWorkflowAiOptions,
): ApprovalWorkflowAiDraft {
  const spec = asRecord(options.spec, 'AI 审批草稿')
  const rawNodes = asArray(spec.nodes, '审批节点')
  const rawEdges = asArray(spec.edges, '审批连线')
  const startNodeId = requiredId(spec.startNodeId, '开始节点')
  if (rawNodes.length < 2 || rawNodes.length > 100) {
    throw new Error('审批画布需要 2–100 个 Cell')
  }

  const roleByCode = new Map(options.directory.roles.map((role) => [role.name, role]))
  const userById = new Map(options.directory.users.map((user) => [String(user.id), user]))
  const currentNodeById = new Map(options.currentNodes.map((node) => [node.id, node]))
  const currentEdgeById = new Map(options.currentEdges.map((edge) => [edge.id, edge]))
  const currentRoleCodes = new Set<string>()
  const currentUserIds = new Set<string>()
  options.currentNodes.forEach((node) => {
    readStringArray(node.config?.approverRoles).forEach((code) => currentRoleCodes.add(code))
    readStringArray(node.config?.notifyRoles).forEach((code) => currentRoleCodes.add(code))
    readStringArray(node.config?.approverUserIds).forEach((id) => currentUserIds.add(id))
    if (node.config?.delegateUserId != null) {
      currentUserIds.add(String(node.config.delegateUserId))
    }
  })

  const nodes = rawNodes.map((value, index): ApprovalWorkflowNode => {
    const raw = asRecord(value, `第 ${index + 1} 个 Cell`)
    const id = requiredId(raw.id, `第 ${index + 1} 个 Cell`)
    const type = readNodeType(raw.type, id)
    const existing = currentNodeById.get(id)
    if (existing && existing.type !== type) {
      throw new Error(`Cell“${id}”不能改变类型`)
    }
    const configPatch = raw.config == null ? {} : asRecord(raw.config, `Cell“${id}”配置`)
    for (const key of Object.keys(configPatch)) {
      if (!CONFIG_KEYS[type].has(key)) {
        throw new Error(`Cell“${id}”包含不支持的配置“${key}”`)
      }
    }
    const config = normalizeNodeConfig({
      id,
      type,
      base: existing?.config ?? {},
      patch: configPatch,
      roleByCode,
      userById,
      currentRoleCodes,
      currentUserIds,
    })
    return {
      id,
      type,
      label: optionalText(raw.label) || existing?.label || friendlyNodeLabel(type),
      position: existing?.position ?? { x: 0, y: index * 120 },
      config,
    }
  })

  const nodeById = new Map<string, ApprovalWorkflowNode>()
  nodes.forEach((node) => {
    if (nodeById.has(node.id)) throw new Error(`Cell ID“${node.id}”重复`)
    nodeById.set(node.id, node)
  })
  const startNodes = nodes.filter((node) => node.type === 'start')
  if (startNodes.length !== 1 || startNodes[0].id !== startNodeId) {
    throw new Error('审批画布必须有且只有一个开始 Cell')
  }
  if (!nodes.some((node) => node.type === 'end')) {
    throw new Error('审批画布至少需要一个结束 Cell')
  }

  const edgePairs = new Set<string>()
  const edges = rawEdges.map((value, index): ApprovalWorkflowEdge => {
    const raw = asRecord(value, `第 ${index + 1} 条连线`)
    if ('condition' in raw) {
      throw new Error('AI 不能写入表达式，请改用金额阈值或默认分支')
    }
    const id = requiredId(raw.id, `第 ${index + 1} 条连线`)
    const source = requiredId(raw.source, `连线“${id}”起点`)
    const target = requiredId(raw.target, `连线“${id}”终点`)
    if (!nodeById.has(source) || !nodeById.has(target)) {
      throw new Error(`连线“${id}”引用了不存在的 Cell`)
    }
    if (source === target) throw new Error('不能把 Cell 连接到自身')
    if (nodeById.get(target)?.type === 'start') throw new Error('连线不能进入开始 Cell')
    if (nodeById.get(source)?.type === 'end') throw new Error('连线不能从结束 Cell发出')
    const pair = `${source}->${target}`
    if (edgePairs.has(pair)) throw new Error(`连线“${pair}”重复`)
    edgePairs.add(pair)

    const existing = currentEdgeById.get(id)
    const isDefault = raw.default === true
    const amountThreshold = optionalNumber(raw.amountThreshold)
    const condition = isDefault
      ? undefined
      : amountThreshold == null
        ? existing?.condition
        : `#amount > ${amountThreshold}`
    const label = isDefault
      ? 'DEFAULT'
      : optionalText(raw.label)
        || (amountThreshold == null ? existing?.label : `金额大于 ${amountThreshold} 元`)
    return {
      id,
      source,
      target,
      condition,
      label,
      priority: boundedInteger(raw.priority, existing?.priority ?? 0, 0, 1000, `连线“${id}”优先级`),
    }
  })

  assertAcyclic(nodes, edges)
  return {
    name: optionalText(spec.name) || options.currentName || `${options.decisionType} 审批`,
    startNodeId,
    nodes,
    edges,
  }
}

interface NormalizeNodeConfigOptions {
  id: string
  type: NodeType
  base: Record<string, unknown>
  patch: Record<string, unknown>
  roleByCode: Map<string, ApprovalDirectory['roles'][number]>
  userById: Map<string, ApprovalDirectory['users'][number]>
  currentRoleCodes: Set<string>
  currentUserIds: Set<string>
}

function normalizeNodeConfig(options: NormalizeNodeConfigOptions): Record<string, unknown> {
  const merged: Record<string, unknown> = { ...options.base }
  const patch = options.patch
  if (options.type === 'approval') {
    if ('approverRoles' in patch) {
      const roles = uniqueStrings(patch.approverRoles)
      assertDirectoryValues(roles, options.roleByCode, options.currentRoleCodes, '审批角色')
      merged.approverRoles = roles
      merged.approverRoleLabels = roles.map((code) => (
        options.roleByCode.get(code)?.displayName || code
      ))
    }
    if ('approverUserIds' in patch) {
      const userIds = uniqueStrings(patch.approverUserIds)
      assertDirectoryValues(userIds, options.userById, options.currentUserIds, '审批人')
      merged.approverUserIds = userIds
      merged.approverUserLabels = userIds.map((id) => {
        const user = options.userById.get(id)
        return user ? formatUserLabel(user) : '历史审批人'
      })
    }
    if ('requiredApprovers' in patch) {
      merged.requiredApprovers = boundedInteger(
        patch.requiredApprovers, 1, 1, 20, `Cell“${options.id}”审批人数`,
      )
    }
    if ('timeoutMinutes' in patch) {
      merged.timeoutMinutes = boundedInteger(
        patch.timeoutMinutes, 0, 0, 43_200, `Cell“${options.id}”审批时限`,
      )
    }
    if ('departmentIds' in patch) {
      merged.departmentIds = uniqueNumbers(patch.departmentIds)
    }
    if ('delegateUserId' in patch) {
      const delegate = patch.delegateUserId == null || patch.delegateUserId === ''
        ? ''
        : String(patch.delegateUserId)
      if (delegate) {
        assertDirectoryValues(
          [delegate], options.userById, options.currentUserIds, '超时转派人',
        )
        merged.delegateUserId = delegate
        const user = options.userById.get(delegate)
        merged.delegateUserLabel = user ? formatUserLabel(user) : '历史审批人'
      } else {
        delete merged.delegateUserId
        delete merged.delegateUserLabel
      }
    }
  } else if (options.type === 'notify') {
    if ('notifyRoles' in patch) {
      const roles = uniqueStrings(patch.notifyRoles)
      assertDirectoryValues(roles, options.roleByCode, options.currentRoleCodes, '通知角色')
      merged.notifyRoles = roles
      merged.notifyRoleLabels = roles.map((code) => (
        options.roleByCode.get(code)?.displayName || code
      ))
    }
    if ('channels' in patch) {
      const channels = uniqueStrings(patch.channels)
      if (channels.some((channel) => !['wechat', 'dingtalk', 'email'].includes(channel))) {
        throw new Error(`Cell“${options.id}”包含不支持的通知渠道`)
      }
      merged.channels = channels
    }
    if ('notifyTemplate' in patch) merged.notifyTemplate = optionalText(patch.notifyTemplate)
  } else if (options.type === 'join') {
    if ('mode' in patch) {
      const mode = String(patch.mode)
      if (!['ALL', 'N_OF_M', 'ANY'].includes(mode)) throw new Error('汇聚模式无效')
      merged.mode = mode
    }
    if ('n' in patch) merged.n = boundedInteger(patch.n, 2, 2, 20, '汇聚数量')
  } else if (options.type === 'end') {
    if ('outcome' in patch) {
      const outcome = String(patch.outcome)
      if (!['APPROVED', 'REJECTED', 'TIMEOUT', 'CANCELLED'].includes(outcome)) {
        throw new Error('结束结果无效')
      }
      merged.outcome = outcome
    }
  } else if ('description' in patch) {
    merged.description = optionalText(patch.description)
  }
  return merged
}

function assertDirectoryValues<T>(
  values: string[],
  directory: Map<string, T>,
  historical: Set<string>,
  label: string,
) {
  const missing = values.filter((value) => !directory.has(value) && !historical.has(value))
  if (missing.length) throw new Error(`${label}不在当前工厂可选目录中`)
}

function assertAcyclic(nodes: ApprovalWorkflowNode[], edges: ApprovalWorkflowEdge[]) {
  const indegree = new Map(nodes.map((node) => [node.id, 0]))
  const outgoing = new Map(nodes.map((node) => [node.id, [] as string[]]))
  edges.forEach((edge) => {
    indegree.set(edge.target, (indegree.get(edge.target) ?? 0) + 1)
    outgoing.get(edge.source)?.push(edge.target)
  })
  const queue = [...indegree.entries()].filter(([, degree]) => degree === 0).map(([id]) => id)
  let visited = 0
  while (queue.length) {
    const id = queue.shift()!
    visited += 1
    outgoing.get(id)?.forEach((target) => {
      const next = (indegree.get(target) ?? 0) - 1
      indegree.set(target, next)
      if (next === 0) queue.push(target)
    })
  }
  if (visited !== nodes.length) throw new Error('审批画布不能形成循环')
}

function asRecord(value: unknown, label: string): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error(`${label}格式无效`)
  }
  return value as Record<string, unknown>
}

function asArray(value: unknown, label: string): unknown[] {
  if (!Array.isArray(value)) throw new Error(`${label}格式无效`)
  return value
}

function requiredId(value: unknown, label: string): string {
  const id = String(value ?? '').trim()
  if (!SAFE_ID.test(id)) throw new Error(`${label}标识无效`)
  return id
}

function readNodeType(value: unknown, id: string): NodeType {
  const type = String(value ?? '') as NodeType
  if (!NODE_TYPES.has(type)) throw new Error(`Cell“${id}”类型无效`)
  return type
}

function optionalText(value: unknown): string {
  return typeof value === 'string' ? value.trim().slice(0, 120) : ''
}

function optionalNumber(value: unknown): number | undefined {
  if (value == null || value === '') return undefined
  const number = Number(value)
  if (!Number.isFinite(number) || number < 0) throw new Error('金额阈值必须为非负数')
  return number
}

function boundedInteger(
  value: unknown,
  fallback: number,
  min: number,
  max: number,
  label: string,
): number {
  if (value == null || value === '') return fallback
  const number = Number(value)
  if (!Number.isInteger(number) || number < min || number > max) {
    throw new Error(`${label}必须在 ${min}–${max} 之间`)
  }
  return number
}

function readStringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.map(String).filter(Boolean) : []
}

function uniqueStrings(value: unknown): string[] {
  return [...new Set(readStringArray(value).map((item) => item.trim()).filter(Boolean))]
}

function uniqueNumbers(value: unknown): number[] {
  if (!Array.isArray(value)) throw new Error('部门范围格式无效')
  const numbers = value.map(Number)
  if (numbers.some((number) => !Number.isInteger(number) || number <= 0)) {
    throw new Error('部门范围包含无效值')
  }
  return [...new Set(numbers)]
}

function friendlyNodeLabel(type: NodeType): string {
  return {
    start: '开始',
    approval: '审批',
    condition: '条件判断',
    parallel: '并行审批',
    join: '汇聚',
    notify: '结果通知',
    end: '结束',
  }[type]
}
