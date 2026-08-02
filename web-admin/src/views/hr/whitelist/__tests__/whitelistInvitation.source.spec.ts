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

  // 2026-08-02: 角色清单从 `[{value, label}]` 改成纯码数组 INVITABLE_ROLE_CODES,
  // label 一律取权威表 enumLabel(原来硬抄的 11 条里漂了 8 条, 见
  // whitelistRoleLabels.source.spec.ts)。本用例守的两件事没变 ——
  // ①用服务端角色码 ②工厂邀请里不许出现平台角色 —— 只是断言要跟着写法走。
  it('uses server role codes and never exposes a platform role in factory invitations', () => {
    const codes = page.match(/const INVITABLE_ROLE_CODES = \[([\s\S]*?)\] as const;/)?.[1] ?? ''
    expect(codes, '没抓到可邀请角色码清单, 断言无效').not.toBe('')
    expect(codes).toContain("'quality_inspector'")
    expect(codes).toContain("'yield_operator'")
    expect(codes).toContain("'factory_super_admin'")
    // ⛔ 平台角色绝不能出现在工厂邀请里(本用例的核心), 整页扫而不只扫清单
    expect(page).not.toContain("'platform_admin'")
    expect(page).not.toContain("'production_worker'")
  })

  it('shows account creation state and protects consumed invitations from edits', () => {
    expect(page).toContain('row.accountCreated && row.accountActive')
    expect(page).toContain("return '邀请待领取'")
    expect(page).toContain('v-if="canWrite && !row.accountCreated"')
    expect(page).toContain('response.data?.successCount')
  })
})
