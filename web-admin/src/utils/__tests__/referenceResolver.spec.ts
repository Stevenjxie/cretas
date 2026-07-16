import { describe, expect, it } from 'vitest'
import { resolveReferenceByName } from '../referenceResolver'

describe('AI reference resolver', () => {
  const candidates = [
    { id: '1', name: '六膳门食品有限公司' },
    { id: '2', name: '六膳门供应链有限公司' },
  ]

  it('prefers a normalized exact name match', () => {
    expect(resolveReferenceByName(' 六膳门食品有限公司 ', candidates)).toMatchObject({ status: 'MATCHED', id: '1' })
  })

  it('returns ambiguous instead of silently choosing the first substring match', () => {
    expect(resolveReferenceByName('六膳门', candidates)).toMatchObject({ status: 'AMBIGUOUS' })
  })

  it('returns unresolved for an empty AI name instead of matching the first row', () => {
    expect(resolveReferenceByName('', candidates)).toMatchObject({ status: 'UNRESOLVED' })
  })
})
