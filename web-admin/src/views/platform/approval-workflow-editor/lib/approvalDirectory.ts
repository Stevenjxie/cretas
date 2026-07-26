import type {
  ApprovalRoleDirectoryItem,
  ApprovalUserDirectoryItem,
} from '@/api/approvalWorkflow'

export interface DirectoryOption {
  value: string
  label: string
  disabled?: boolean
  description?: string
}

const FALLBACK_ROLE_LABELS: Record<string, string> = {
  platform_admin: '平台管理员',
  factory_super_admin: '工厂总管理员',
  factory_admin: '工厂管理员',
  permission_admin: '权限管理员',
  department_admin: '部门管理员',
  procurement_manager: '采购主管',
  sales_manager: '销售主管',
  production_manager: '生产主管',
  dispatcher: '生产调度员',
  workshop_supervisor: '车间主管',
  quality_manager: '质量主管',
  quality_inspector: '质检员',
  warehouse_manager: '仓储主管',
  warehouse_worker: '仓管员',
  finance_manager: '财务主管',
  hr_admin: '人事管理员',
  restaurant_manager: '餐厅经理',
  team_leader: '班组长',
  group_leader: '组长',
  operator: '操作员',
  viewer: '只读人员',
}

export function friendlyRoleLabel(roleCode: string): string {
  return FALLBACK_ROLE_LABELS[roleCode] ?? '历史审批角色'
}

export function buildRoleOptions(
  roles: ApprovalRoleDirectoryItem[],
  selectedRoleCodes: string[] = [],
): DirectoryOption[] {
  const byCode = new Map<string, DirectoryOption>()
  roles.forEach((role) => {
    if (!role.name) return
    byCode.set(role.name, {
      value: role.name,
      label: role.displayName || friendlyRoleLabel(role.name),
      description: role.description,
    })
  })
  selectedRoleCodes.forEach((roleCode) => {
    if (!roleCode || byCode.has(roleCode)) return
    byCode.set(roleCode, {
      value: roleCode,
      label: `${friendlyRoleLabel(roleCode)}（历史配置）`,
      disabled: true,
      description: '该角色当前不在可选目录中，保留仅用于读取历史配置。',
    })
  })
  return [...byCode.values()]
}

export function formatUserLabel(user: ApprovalUserDirectoryItem): string {
  const name = user.fullName?.trim() || user.realName?.trim() || user.username
  return name === user.username ? name : `${name}（${user.username}）`
}

export function buildUserOptions(
  users: ApprovalUserDirectoryItem[],
  selectedUserIds: string[] = [],
): DirectoryOption[] {
  const byId = new Map<string, DirectoryOption>()
  users.forEach((user) => {
    const value = String(user.id)
    if (!value || value === 'undefined' || value === 'null') return
    const details = [user.roleDisplayName, user.departmentDisplayName].filter(Boolean).join(' · ')
    byId.set(value, {
      value,
      label: formatUserLabel(user),
      disabled: user.isActive === false,
      description: details || undefined,
    })
  })
  selectedUserIds.forEach((userId) => {
    if (!userId || byId.has(String(userId))) return
    byId.set(String(userId), {
      value: String(userId),
      label: '历史审批人（当前不可选）',
      disabled: true,
      description: '该人员当前不在本工厂可选目录中，保留仅用于读取历史配置。',
    })
  })
  return [...byId.values()]
}

export function labelsForValues(options: DirectoryOption[], values: string[]): string[] {
  const labels = new Map(options.map((option) => [option.value, option.label]))
  return values.map((value) => labels.get(String(value)) ?? '历史配置项')
}
