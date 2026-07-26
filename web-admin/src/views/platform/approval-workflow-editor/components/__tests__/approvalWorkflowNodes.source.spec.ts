import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

function nodeSource(name: string): string {
  return readFileSync(
    resolve(
      process.cwd(),
      `src/views/platform/approval-workflow-editor/components/nodes/${name}Node.vue`,
    ),
    'utf8',
  )
}

describe('approval workflow Cell visual contract', () => {
  const editor = readFileSync(
    resolve(process.cwd(), 'src/views/platform/approval-workflow-editor/index.vue'),
    'utf8',
  )

  it('uses compact pills for start and end cells', () => {
    for (const name of ['Start', 'End']) {
      const source = nodeSource(name)
      expect(source).toContain('width: 136px')
      expect(source).toContain('border-radius: 999px')
      expect(source).not.toContain('border-radius: 50%')
    }
  })

  it('uses fixed-width business cards for process cells', () => {
    for (const name of ['Approval', 'Condition', 'Parallel', 'Join', 'Notify']) {
      const source = nodeSource(name)
      expect(source).toContain('width: 208px')
      expect(source).toContain('box-sizing: border-box')
      expect(source).toContain('overflow: hidden')
      expect(source).toContain('node-kind')
    }
  })

  it('does not render the condition cell as a rotated diamond', () => {
    const source = nodeSource('Condition')
    expect(source).toContain('按配置条件自动分流')
    expect(source).not.toContain('class="diamond"')
    expect(source).not.toContain('rotate(45deg)')
  })

  it('prevents long role and user labels from stretching approval cells', () => {
    const source = nodeSource('Approval')
    expect(source).toContain('text-overflow: ellipsis')
    expect(source).toContain('white-space: nowrap')
    expect(source).toContain(':title="approverUserLabels.join')
  })

  it('caps automatic canvas fitting without limiting manual zoom', () => {
    expect(editor).not.toContain('fit-view-on-init')
    expect(editor).toContain("padding: { top: '96px', right: '24px', bottom: '72px', left: '24px' }")
    expect(editor).toContain('maxZoom: 1.1')
    expect(editor).toContain(':max-zoom="1.8"')
    expect(editor).toContain('await createDefaultDraft()')
  })
})
