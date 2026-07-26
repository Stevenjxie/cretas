import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

function source(path: string): string {
  return readFileSync(resolve(process.cwd(), path), 'utf8')
}

describe('factory account invitation UI contract', () => {
  const page = source('src/views/hr/whitelist/index.vue')
  const menu = source('src/components/layout/menuConfig.ts')
  const login = source('src/views/login/index.vue')

  it('explains the administrator invite and employee phone-registration flow', () => {
    expect(page).toContain('账号邀请（白名单）')
    expect(page).toContain('员工在手机端用该手机号注册并设置密码')
    expect(page).toContain('手机号将作为员工的登录账号')
    expect(menu).toContain("title: '账号邀请'")
    expect(login).toContain('placeholder="请输入手机号或用户名"')
  })

  it('uses server role codes and never exposes a platform role in factory invitations', () => {
    expect(page).toContain("value: 'quality_inspector'")
    expect(page).toContain("value: 'yield_operator'")
    expect(page).toContain("value: 'factory_super_admin'")
    expect(page).not.toContain("value: 'platform_admin'")
    expect(page).not.toContain("value: 'production_worker'")
  })

  it('shows account creation state and protects consumed invitations from edits', () => {
    expect(page).toContain('row.accountCreated && row.accountActive')
    expect(page).toContain("return '邀请待领取'")
    expect(page).toContain('v-if="canWrite && !row.accountCreated"')
    expect(page).toContain('response.data?.successCount')
  })
})
