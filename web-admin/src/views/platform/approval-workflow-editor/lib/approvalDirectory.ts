import type {
  ApprovalRoleDirectoryItem,
  ApprovalUserDirectoryItem,
} from '@/api/approvalWorkflow'
import { ROLE_LABELS } from '@/utils/enumDisplay'

export interface DirectoryOption {
  value: string
  label: string
  disabled?: boolean
  description?: string
}

export function friendlyRoleLabel(roleCode: string): string {
  // 角色中文的单一来源是 utils/enumDisplay 的 ROLE_LABELS —— 这里原本自带一份 21 条的
  // 副本, 而仓库里另有 3 份写法不同的角色表 (`warehouse_worker` 有「仓库工人」/「仓库员」/
  // 「仓管员」三种)。共享表直接沿用了本文件的措辞, 所以此处渲染结果不变。
  // 未登记的码仍返回「历史审批角色」—— 这是本页特有语义 (保留只为读旧配置), 不能退到
  // enumLabel 的「未知状态（…）」。
  return ROLE_LABELS[roleCode] ?? '历史审批角色'
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
