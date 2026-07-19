interface Attempt {
  businessSignature: string;
  requestId: string;
}

type RequestIdFactory = () => string;

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export function secureRequestId(): string {
  if (typeof globalThis.crypto?.randomUUID !== 'function') {
    throw new Error('SECURE_REQUEST_ID_UNAVAILABLE');
  }
  return globalThis.crypto.randomUUID();
}

export function stableBusinessSignature(value: unknown): string {
  return JSON.stringify(sortValue(value));
}

export class InMemoryIdempotencyAttempts {
  private readonly attempts = new Map<string, Attempt>();

  constructor(private readonly requestIdFactory: RequestIdFactory = secureRequestId) {}

  requestId(action: string, businessSignature: string): string {
    const existing = this.attempts.get(action);
    if (existing?.businessSignature === businessSignature) return existing.requestId;

    const requestId = this.requestIdFactory();
    if (!UUID.test(requestId)) throw new Error('SECURE_REQUEST_ID_INVALID');
    this.attempts.set(action, { businessSignature, requestId });
    return requestId;
  }

  complete(action: string, requestId: string): void {
    if (this.attempts.get(action)?.requestId === requestId) {
      this.attempts.delete(action);
    }
  }
}

function sortValue(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sortValue);
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value as Record<string, unknown>)
        .sort(([left], [right]) => left.localeCompare(right))
        .map(([key, item]) => [key, sortValue(item)]),
    );
  }
  return value;
}
