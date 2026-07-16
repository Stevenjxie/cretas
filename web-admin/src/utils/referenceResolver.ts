export interface ReferenceCandidate {
  id?: string | number | null
  name?: string | null
}

export type ReferenceResolution =
  | { status: 'MATCHED'; id: string; candidate: ReferenceCandidate }
  | { status: 'AMBIGUOUS'; candidates: ReferenceCandidate[] }
  | { status: 'UNRESOLVED'; candidates: ReferenceCandidate[] }

function normalize(value: string | null | undefined): string {
  return String(value || '')
    .trim()
    .toLocaleLowerCase()
    .replace(/[\s，,。.;；:：()（）\[\]【】_-]+/g, '')
}

export function resolveReferenceByName(
  requestedName: string | null | undefined,
  candidates: ReferenceCandidate[],
): ReferenceResolution {
  const requested = normalize(requestedName)
  if (!requested) return { status: 'UNRESOLVED', candidates: [] }

  const exact = candidates.filter((candidate) => normalize(candidate.name) === requested)
  if (exact.length === 1 && exact[0].id != null) {
    return { status: 'MATCHED', id: String(exact[0].id), candidate: exact[0] }
  }
  if (exact.length > 1) return { status: 'AMBIGUOUS', candidates: exact }

  const partial = candidates.filter((candidate) => {
    const name = normalize(candidate.name)
    return name.includes(requested) || requested.includes(name)
  })
  if (partial.length === 1 && partial[0].id != null) {
    return { status: 'MATCHED', id: String(partial[0].id), candidate: partial[0] }
  }
  if (partial.length > 1) return { status: 'AMBIGUOUS', candidates: partial }
  return { status: 'UNRESOLVED', candidates: [] }
}
