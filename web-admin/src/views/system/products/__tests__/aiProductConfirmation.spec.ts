import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(import.meta.dirname, '../index.vue'), 'utf8');

describe('AI product fixed-write confirmation contract', () => {
  it('keeps the preview token in memory and sends it only as the execute header', () => {
    const executeSource = source.slice(
      source.indexOf('async function handleAiProductCreate()'),
      source.indexOf('watch(\n  [', source.indexOf('async function handleAiProductCreate()')),
    );

    expect(source).toContain('interface AiProductPreviewResponse extends AiProductPreviewData');
    expect(source).toContain('const aiProductConfirmationToken = ref<string | null>(null)');
    expect(source).toContain('aiProductConfirmationToken.value = data.confirmationToken');
    expect(executeSource).toContain('buildAiProductBody(),');
    expect(executeSource).toContain("'X-Cretas-Confirmation-Token': aiProductConfirmationToken.value");
    expect(executeSource).not.toContain('confirmationToken: aiProductConfirmationToken.value');
  });

  it('invalidates preview proof after input changes or dialog close', () => {
    expect(source).toContain('() => aiProductForm.productName');
    expect(source).toContain('() => aiProductForm.inheritFrom');
    expect(source).toContain('watch(aiProductDialogVisible, (visible) =>');
    expect(source).toContain(':disabled="!aiProductPreview || !aiProductConfirmationToken"');

    const resetSource = source.slice(
      source.indexOf('function resetAiProductDialog()'),
      source.indexOf('function buildAiProductBody()'),
    );
    expect(resetSource).toContain('aiProductConfirmationToken.value = null');
  });
});
