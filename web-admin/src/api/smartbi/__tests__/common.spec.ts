/**
 * Unit tests for SmartBI common utilities
 * Per B-1 P1 bug fix: pythonFetch 204 No Content handling
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { pythonFetch, transformKeys } from '../common';

// Mock fetch globally
global.fetch = vi.fn();

// Mock localStorage for auth headers
global.localStorage = {
  getItem: vi.fn((key: string) => {
    if (key === 'cretas_access_token') return 'mock-token-123';
    if (key === 'cretas_user') return JSON.stringify({ factoryId: 'F001' });
    return null;
  }),
  setItem: vi.fn(),
  removeItem: vi.fn(),
  clear: vi.fn(),
  length: 0,
  key: vi.fn(),
} as unknown as Storage;

describe('pythonFetch', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('returns null for 204 No Content without attempting JSON parse', async () => {
    const jsonMock = vi.fn().mockRejectedValue(new SyntaxError('Unexpected end of JSON input'));
    (global.fetch as any).mockResolvedValue({
      ok: true,
      status: 204,
      statusText: 'No Content',
      json: jsonMock,
    } as unknown as Response);

    const result = await pythonFetch('/api/restaurant/outliers/dismiss/123', {
      method: 'DELETE',
    });

    expect(result).toBeNull();
    // Verify json() was never called (early return on 204)
    expect(jsonMock).not.toHaveBeenCalled();
  });

  it('parses JSON and transforms keys for 200 response', async () => {
    const mockData = {
      success: true,
      data: { total_count: 42, average_price: 100.5 },
    };

    (global.fetch as any).mockResolvedValue({
      ok: true,
      status: 200,
      statusText: 'OK',
      json: vi.fn().mockResolvedValue(mockData),
    } as unknown as Response);

    const result = await pythonFetch('/api/data/get');

    expect(result).toEqual({
      success: true,
      data: { totalCount: 42, averagePrice: 100.5 }, // snake_case → camelCase
    });
  });

  it('throws on non-ok response status', async () => {
    (global.fetch as any).mockResolvedValue({
      ok: false,
      status: 500,
      statusText: 'Internal Server Error',
    } as unknown as Response);

    await expect(pythonFetch('/api/test')).rejects.toThrow(
      'Python service error: 500 Internal Server Error'
    );
  });

  it('throws on request timeout', async () => {
    (global.fetch as any).mockImplementation(() =>
      new Promise((_, reject) => {
        const abortError = new DOMException('Aborted', 'AbortError');
        reject(abortError);
      })
    );

    await expect(pythonFetch('/api/test', { timeoutMs: 100 })).rejects.toThrow(
      'Python service request timed out'
    );
  });

  it('keeps JWT auth and omits browser-side internal credentials for non-stream requests', async () => {
    (global.fetch as any).mockResolvedValue({
      ok: true,
      status: 200,
      json: vi.fn().mockResolvedValue({ success: true }),
    } as unknown as Response);

    vi.stubEnv('VITE_PYTHON_SECRET', 'must-not-leak');
    vi.resetModules();
    try {
      expect(import.meta.env.VITE_PYTHON_SECRET).toBe('must-not-leak');
      const { pythonFetch: freshPythonFetch } = await import('../common');

      await freshPythonFetch('/api/chat/general-analysis', {
        method: 'POST',
        body: JSON.stringify({ query: 'sales trend' }),
      });

      const callArgs = (global.fetch as any).mock.calls[0];
      const fetchOptions = callArgs[1];
      expect(fetchOptions.headers).toMatchObject({
        'Content-Type': 'application/json',
        Authorization: 'Bearer mock-token-123',
      });
      expect(fetchOptions.headers).not.toHaveProperty('X-Internal-Secret');
    } finally {
      vi.unstubAllEnvs();
      vi.resetModules();
    }
  });

  it('returns JWT-only auth headers for direct SSE stream callers even when a secret env is set', async () => {
    vi.stubEnv('VITE_PYTHON_SECRET', 'must-not-leak');
    vi.resetModules();
    try {
      expect(import.meta.env.VITE_PYTHON_SECRET).toBe('must-not-leak');
      const { getPythonAuthHeaders: freshGetPythonAuthHeaders } = await import('../common');
      const headers = freshGetPythonAuthHeaders();

      expect(headers).toMatchObject({
        'Content-Type': 'application/json',
        Authorization: 'Bearer mock-token-123',
      });
      expect(headers).not.toHaveProperty('X-Internal-Secret');
    } finally {
      vi.unstubAllEnvs();
      vi.resetModules();
    }
  });

  it('handles arrays in response', async () => {
    const mockData = [
      { item_name: 'product1', sale_amount: 100 },
      { item_name: 'product2', sale_amount: 200 },
    ];

    (global.fetch as any).mockResolvedValue({
      ok: true,
      status: 200,
      json: vi.fn().mockResolvedValue(mockData),
    } as unknown as Response);

    const result = await pythonFetch('/api/items');

    expect(result).toEqual([
      { itemName: 'product1', saleAmount: 100 },
      { itemName: 'product2', saleAmount: 200 },
    ]);
  });
});

describe('transformKeys', () => {
  it('converts snake_case to camelCase recursively', () => {
    const input = {
      user_id: 123,
      user_name: 'John',
      nested_obj: {
        field_one: 'value1',
        field_two: 'value2',
      },
      items: [
        { item_id: 1, item_name: 'Item 1' },
        { item_id: 2, item_name: 'Item 2' },
      ],
    };

    const expected = {
      userId: 123,
      userName: 'John',
      nestedObj: {
        fieldOne: 'value1',
        fieldTwo: 'value2',
      },
      items: [
        { itemId: 1, itemName: 'Item 1' },
        { itemId: 2, itemName: 'Item 2' },
      ],
    };

    expect(transformKeys(input)).toEqual(expected);
  });

  it('preserves non-object types', () => {
    expect(transformKeys(null)).toBeNull();
    expect(transformKeys(undefined)).toBeUndefined();
    expect(transformKeys(123)).toBe(123);
    expect(transformKeys('string')).toBe('string');
    expect(transformKeys(true)).toBe(true);
  });

  it('preserves Date objects', () => {
    const date = new Date('2026-04-28');
    const input = { created_at: date };
    const result = transformKeys(input);
    expect((result as any).createdAt).toEqual(date);
    expect((result as any).createdAt instanceof Date).toBe(true);
  });
});
