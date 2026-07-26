import { describe, expect, it } from 'vitest'
import {
  buildRoleOptions,
  buildUserOptions,
  formatUserLabel,
  labelsForValues,
} from '../approvalDirectory'

describe('approvalDirectory', () => {
  it('uses friendly role labels while keeping stable role codes as values', () => {
    const options = buildRoleOptions([
      {
        name: 'finance_manager',
        displayName: '财务主管',
        description: '负责财务审批',
      },
    ])

    expect(options).toEqual([
      {
        value: 'finance_manager',
        label: '财务主管',
        description: '负责财务审批',
      },
    ])
  })

  it('preserves missing historical roles as disabled read-only options', () => {
    const options = buildRoleOptions([], ['warehouse_manager'])

    expect(options).toEqual([
      expect.objectContaining({
        value: 'warehouse_manager',
        label: '仓储主管（历史配置）',
        disabled: true,
      }),
    ])
  })

  it('formats users without exposing numeric ids and disables inactive users', () => {
    const user = {
      id: 42,
      username: 'liushanmen_admin',
      fullName: '六膳门管理员',
      roleDisplayName: '工厂总管理员',
      departmentDisplayName: '总部',
      isActive: false,
    }
    const options = buildUserOptions([user])

    expect(formatUserLabel(user)).toBe('六膳门管理员（liushanmen_admin）')
    expect(options).toEqual([
      {
        value: '42',
        label: '六膳门管理员（liushanmen_admin）',
        disabled: true,
        description: '工厂总管理员 · 总部',
      },
    ])
  })

  it('keeps unknown historical users readable without showing their raw id', () => {
    const options = buildUserOptions([], ['987654'])

    expect(options[0]).toEqual(expect.objectContaining({
      value: '987654',
      label: '历史审批人（当前不可选）',
      disabled: true,
    }))
    expect(options[0]?.label).not.toContain('987654')
  })

  it('produces display snapshots for saved workflow nodes', () => {
    const options = buildRoleOptions([
      { name: 'finance_manager', displayName: '财务主管' },
    ])

    expect(labelsForValues(options, ['finance_manager'])).toEqual(['财务主管'])
  })
})
