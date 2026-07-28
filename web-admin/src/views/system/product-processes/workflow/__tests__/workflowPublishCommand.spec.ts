import { describe, expect, it, vi } from 'vitest';
import { resolveWorkflowPublishCommand } from '../workflowPublishCommand';

describe('resolveWorkflowPublishCommand', () => {
  it('reuses the idempotency key for the same exact saved revision identity', () => {
    const createKey = vi.fn()
      .mockReturnValueOnce('key-1')
      .mockReturnValueOnce('key-2');
    const identity = {
      factoryId: 'F006',
      productTypeId: 'PT-A',
      lockVersion: 7,
      revisionId: 222,
      revisionHash: 'revision-222',
      definitionVersion: 4,
    };

    const first = resolveWorkflowPublishCommand(null, identity, createKey);
    const retry = resolveWorkflowPublishCommand(first, identity, createKey);

    expect(retry).toBe(first);
    expect(retry.request).toEqual({
      lockVersion: 7,
      idempotencyKey: 'key-1',
      revisionId: 222,
      revisionHash: 'revision-222',
      definitionVersion: 4,
    });
    expect(createKey).toHaveBeenCalledTimes(1);
  });

  it.each([
    [{ factoryId: 'F007', productTypeId: 'PT-A', lockVersion: 7, revisionId: 222, revisionHash: 'revision-222', definitionVersion: 4 }],
    [{ factoryId: 'F006', productTypeId: 'PT-B', lockVersion: 7, revisionId: 222, revisionHash: 'revision-222', definitionVersion: 4 }],
    [{ factoryId: 'F006', productTypeId: 'PT-A', lockVersion: 8, revisionId: 222, revisionHash: 'revision-222', definitionVersion: 4 }],
    [{ factoryId: 'F006', productTypeId: 'PT-A', lockVersion: 7, revisionId: 222, revisionHash: 'revision-222', definitionVersion: 5 }],
  ])('creates a new key when the publish identity changes', (nextIdentity) => {
    const createKey = vi.fn()
      .mockReturnValueOnce('key-1')
      .mockReturnValueOnce('key-2');
    const first = resolveWorkflowPublishCommand(null, {
      factoryId: 'F006',
      productTypeId: 'PT-A',
      lockVersion: 7,
      revisionId: 222,
      revisionHash: 'revision-222',
      definitionVersion: 4,
    }, createKey);

    const next = resolveWorkflowPublishCommand(first, nextIdentity, createKey);

    expect(next.idempotencyKey).toBe('key-2');
    expect(next.request).toEqual({
      lockVersion: nextIdentity.lockVersion,
      idempotencyKey: 'key-2',
      revisionId: nextIdentity.revisionId,
      revisionHash: nextIdentity.revisionHash,
      definitionVersion: nextIdentity.definitionVersion,
    });
    expect(createKey).toHaveBeenCalledTimes(2);
  });

  it('rejects blank keys before a publish request can be sent', () => {
    expect(() => resolveWorkflowPublishCommand(null, {
      factoryId: 'F006',
      productTypeId: 'PT-A',
      lockVersion: 7,
      revisionId: 222,
      revisionHash: 'revision-222',
      definitionVersion: 4,
    }, () => '   ')).toThrow('Workflow publish idempotency key must not be blank');
  });

  it('rejects a publish command without an exact saved revision identity', () => {
    expect(() => resolveWorkflowPublishCommand(null, {
      factoryId: 'F006',
      productTypeId: 'PT-A',
      lockVersion: 7,
      revisionId: null,
      revisionHash: null,
      definitionVersion: 4,
    }, () => 'key-1')).toThrow('Workflow publish requires an exact saved revision identity');
  });

  it.each([
    [{ revisionId: 223, revisionHash: 'revision-223' }],
    [{ revisionId: 222, revisionHash: 'revision-222-new-draft' }],
  ])('creates a new key when a new draft reuses the same lock version', (revision) => {
    const createKey = vi.fn()
      .mockReturnValueOnce('key-1')
      .mockReturnValueOnce('key-2');
    const first = resolveWorkflowPublishCommand(null, {
      factoryId: 'F006',
      productTypeId: 'PT-A',
      lockVersion: 0,
      revisionId: 222,
      revisionHash: 'revision-222',
      definitionVersion: 4,
    }, createKey);

    const next = resolveWorkflowPublishCommand(first, {
      factoryId: 'F006',
      productTypeId: 'PT-A',
      lockVersion: 0,
      definitionVersion: 4,
      ...revision,
    }, createKey);

    expect(next.idempotencyKey).toBe('key-2');
  });
});
