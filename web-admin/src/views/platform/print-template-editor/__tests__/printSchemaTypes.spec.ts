/**
 * Schema type helpers — wrap/unwrap (Formily envelope ↔ raw schema),
 * isValidEntityType, emptySchema. These functions are the storage-format
 * boundary between FormTemplate.schemaJson (a string) and the editor's
 * in-memory PrintTemplateSchema.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  wrapForStorage,
  unwrapFromStorage,
  emptySchema,
  isValidEntityType,
  ENTITY_TYPES,
  DEFAULT_A4_PORTRAIT,
  type PrintTemplateSchema,
} from '../utils/printSchemaTypes';

describe('printSchemaTypes', () => {
  describe('wrapForStorage / unwrapFromStorage', () => {
    it('round-trips a non-trivial schema byte-for-byte', () => {
      const original: PrintTemplateSchema = {
        version: 1,
        canvas: { width: 595, height: 842, orientation: 'portrait' },
        elements: [
          { id: 't', type: 'text', x: 50, y: 50, text: 'Hello', fontSize: 12 },
          { id: 'f', type: 'field', x: 50, y: 80, binding: '{{a.b}}', fontSize: 12 },
        ],
      };
      const wrapped = wrapForStorage(original);
      // Outer envelope must look like Formily (satisfies backend validate)
      expect(wrapped.type).toBe('object');
      expect(wrapped.properties._printSchema).toBeDefined();

      const restored = unwrapFromStorage(JSON.stringify(wrapped));
      expect(restored).toEqual(original);
    });

    it('returns null for empty / null input', () => {
      expect(unwrapFromStorage(null)).toBeNull();
      expect(unwrapFromStorage(undefined)).toBeNull();
      expect(unwrapFromStorage('')).toBeNull();
    });

    it('returns null for malformed JSON', () => {
      expect(unwrapFromStorage('{not-json')).toBeNull();
    });

    it('returns null when properties._printSchema is missing', () => {
      const bad = JSON.stringify({ type: 'object', properties: { other: 1 } });
      expect(unwrapFromStorage(bad)).toBeNull();
    });

    // --- Issue #719 P0: backend may return schemaJson already parsed -----
    // FormTemplate.schemaJson is a TEXT column holding JSON. Spring/Jackson
    // can deliver it either as a JSON string (default) OR — when wrapped in
    // a DTO whose field is declared as `Map<String,Object>` / `JsonNode` —
    // as an already-parsed Object. The legacy implementation called
    // JSON.parse(raw) unconditionally, which coerces the object to the
    // string "[object Object]" and throws, then the catch silently returned
    // null → canvas rendered 0 of 65 backend-seeded elements (#719 repro).

    describe('Issue #719 — schemaJson silent-drop (Jackson 直返 Object)', () => {
      it('handles already-parsed Formily envelope object input', () => {
        const schema: PrintTemplateSchema = {
          version: 1,
          canvas: { width: 595, height: 842, orientation: 'portrait' },
          elements: [
            { id: 'a', type: 'text', x: 10, y: 10, text: 'hi', fontSize: 12 },
            { id: 'b', type: 'field', x: 10, y: 30, binding: '{{x}}', fontSize: 12 },
          ],
        };
        // Plain object — NOT JSON.stringify'd
        const rawObject = wrapForStorage(schema);
        const restored = unwrapFromStorage(rawObject);
        expect(restored).toEqual(schema);
        expect(restored?.elements).toHaveLength(2);
      });

      it('handles already-parsed Formily envelope from real backend shape', () => {
        // Mirrors V20260603_09 seed for PRINT_SALES_ORDER (11 elements).
        const seed = {
          type: 'object' as const,
          properties: {
            _printSchema: {
              version: 1 as const,
              canvas: { width: 595, height: 842, orientation: 'portrait' as const },
              elements: Array.from({ length: 11 }, (_, i) => ({
                id: `el-${i}`,
                type: 'text' as const,
                x: 0,
                y: i * 30,
                text: `Row ${i}`,
                fontSize: 12,
              })),
            },
          },
        };
        const restored = unwrapFromStorage(seed);
        expect(restored).not.toBeNull();
        expect(restored?.elements).toHaveLength(11);
      });

      it('returns null when raw object is missing properties._printSchema', () => {
        // Object input but wrong shape (e.g. a different Formily form schema)
        const wrongObject = { type: 'object', properties: { other: { x: 1 } } };
        expect(unwrapFromStorage(wrongObject)).toBeNull();
      });

      it('returns null safely for non-Formily primitive object input', () => {
        // Defensive: arbitrary object/array input should not throw
        expect(unwrapFromStorage({ foo: 'bar' })).toBeNull();
        expect(unwrapFromStorage([1, 2, 3])).toBeNull();
        expect(unwrapFromStorage(42)).toBeNull();
        expect(unwrapFromStorage(true)).toBeNull();
      });
    });

    // --- Diagnostic logging (Issue #719 root-cause discoverability) ------
    describe('Issue #719 — diagnostic logging on parse failure', () => {
      afterEach(() => {
        vi.restoreAllMocks();
      });

      it('logs console.error when malformed JSON string is received', () => {
        const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
        const result = unwrapFromStorage('{not-json');
        expect(result).toBeNull();
        expect(errorSpy).toHaveBeenCalled();
        // Sanity: the log includes the unwrap helper name so devs can grep
        const callArgs = errorSpy.mock.calls[0]?.join(' ') ?? '';
        expect(callArgs).toMatch(/unwrapFromStorage/);
      });

      it('does NOT log for valid empty/null input (not an error path)', () => {
        const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
        unwrapFromStorage(null);
        unwrapFromStorage(undefined);
        unwrapFromStorage('');
        expect(errorSpy).not.toHaveBeenCalled();
      });
    });

    it('survives a schema that includes all 7 element types', () => {
      const all: PrintTemplateSchema = {
        version: 1,
        canvas: { width: 595, height: 842, orientation: 'portrait' },
        elements: [
          { id: '1', type: 'text', x: 0, y: 0, text: 'x', fontSize: 12 },
          { id: '2', type: 'field', x: 0, y: 30, binding: '{{a}}', fontSize: 12 },
          { id: '3', type: 'table', x: 0, y: 60, width: 200, binding: '{{items}}',
            rowHeight: 24, columns: [{ header: 'h', binding: '{{item.a}}', width: 100 }] },
          { id: '4', type: 'qr', x: 0, y: 100, size: 80, content: 'x' },
          { id: '5', type: 'barcode', x: 0, y: 200, width: 100, height: 40, content: 'x' },
          { id: '6', type: 'image', x: 0, y: 250, width: 100, height: 50, src: '' },
          { id: '7', type: 'stamp', x: 0, y: 320, size: 80, stampId: 'default' },
        ],
      };
      const restored = unwrapFromStorage(JSON.stringify(wrapForStorage(all)));
      expect(restored).toEqual(all);
      expect(restored?.elements).toHaveLength(7);
    });
  });

  describe('ENTITY_TYPES and isValidEntityType', () => {
    it('lists exactly the 6 PRINT_* codes the backend whitelisted in Day 4', () => {
      // Mirrors FormTemplateServiceImpl.SUPPORTED_ENTITY_TYPES additions.
      expect(ENTITY_TYPES.map(e => e.code).sort()).toEqual([
        'PRINT_MATERIAL_REQUISITION',
        'PRINT_PRODUCTION_TASK',
        'PRINT_PURCHASE_ORDER',
        'PRINT_QUOTATION',
        'PRINT_SALES_ORDER',
        'PRINT_WEIGHING_SLIP',
      ]);
    });

    it('includes the F006 weighing slip ', () => {
      expect(ENTITY_TYPES.some(e => e.code === 'PRINT_WEIGHING_SLIP')).toBe(true);
    });

    it('isValidEntityType accepts known codes', () => {
      expect(isValidEntityType('PRINT_SALES_ORDER')).toBe(true);
      expect(isValidEntityType('PRINT_WEIGHING_SLIP')).toBe(true);
    });

    it('isValidEntityType rejects unknown codes including legacy non-PRINT_ ones', () => {
      expect(isValidEntityType('SALES_ORDER')).toBe(false);
      expect(isValidEntityType('PURCHASE_ORDER')).toBe(false); // legacy form schema, not print
      expect(isValidEntityType('QUALITY_CHECK')).toBe(false);
      expect(isValidEntityType('')).toBe(false);
    });
  });

  describe('emptySchema', () => {
    it('returns a fresh A4 portrait schema with no elements', () => {
      const s = emptySchema();
      expect(s.version).toBe(1);
      expect(s.canvas).toEqual(DEFAULT_A4_PORTRAIT);
      expect(s.elements).toEqual([]);
    });

    it('returns independent canvas objects (mutating one does not affect another)', () => {
      const a = emptySchema();
      const b = emptySchema();
      a.canvas.width = 999;
      expect(b.canvas.width).toBe(595);
    });
  });
});
