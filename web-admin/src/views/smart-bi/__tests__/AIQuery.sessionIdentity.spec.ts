import { describe, expect, it, vi } from 'vitest';

// Loading named exports from the SFC also evaluates its module imports. Keep
// this pure contract test independent of canvas/echarts-wordcloud support.
vi.mock('@/utils/echarts', () => ({ default: {} }));

import {
  buildChatSessionStorageKey,
  getOrCreateScopedChatSessionId,
  type ChatSessionStorage,
} from '../AIQuery.vue';


class RecordingStorage implements ChatSessionStorage {
  readonly values = new Map<string, string>();
  readonly reads: string[] = [];
  readonly writes: Array<[string, string]> = [];

  getItem(key: string): string | null {
    this.reads.push(key);
    return this.values.get(key) ?? null;
  }

  setItem(key: string, value: string): void {
    this.writes.push([key, value]);
    this.values.set(key, value);
  }
}


describe('AIQuery conversation session identity scope', () => {
  it('creates different storage keys for two users in the same factory', () => {
    const user1 = buildChatSessionStorageKey('FACTORY_A', 101);
    const user2 = buildChatSessionStorageKey('FACTORY_A', 202);

    expect(user1).toBe('smartbi.chatSessionId.FACTORY_A.101');
    expect(user2).toBe('smartbi.chatSessionId.FACTORY_A.202');
    expect(user1).not.toBe(user2);
    expect(buildChatSessionStorageKey(undefined, undefined))
      .toBe('smartbi.chatSessionId.anon.anon');
  });

  it('does not read or continue the legacy factory-only storage key', () => {
    const storage = new RecordingStorage();
    const legacyKey = 'smartbi.chatSessionId.FACTORY_A';
    const scopedKey = buildChatSessionStorageKey('FACTORY_A', 101);
    storage.values.set(legacyKey, 'legacy-shared-session');

    const sessionId = getOrCreateScopedChatSessionId(
      storage,
      scopedKey,
      () => 'new-user-session',
    );

    expect(sessionId).toBe('new-user-session');
    expect(storage.reads).toEqual([scopedKey]);
    expect(storage.reads).not.toContain(legacyKey);
    expect(storage.writes).toEqual([[scopedKey, 'new-user-session']]);
    expect(storage.values.get(legacyKey)).toBe('legacy-shared-session');
  });
});
