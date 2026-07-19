import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(import.meta.dirname, '../index.vue'), 'utf8');

describe('BOM adjust fixed-write confirmation contract', () => {
  it('keeps the preview token in memory and sends it only as the execute header', () => {
    const executeSource = source.slice(
      source.indexOf('async function handleAdjustConfirm()'),
      source.indexOf('watch([adjustInstruction', source.indexOf('async function handleAdjustConfirm()')),
    );

    expect(source).toContain('interface AdjustPreviewResponse extends AdjustPreviewResult');
    expect(source).toContain('const adjustConfirmationToken = ref<string | null>(null)');
    expect(source).toContain('adjustConfirmationToken.value = data.confirmationToken');
    expect(executeSource).toContain('instruction: adjustInstruction.value.trim(),');
    expect(executeSource).toContain("'X-Cretas-Confirmation-Token': adjustConfirmationToken.value");
    expect(executeSource).not.toContain('confirmationToken: adjustConfirmationToken.value');
  });

  it('invalidates preview proof after request input changes or dialog close', () => {
    expect(source).toContain('watch([adjustInstruction, selectedProductTypeId], () =>');
    expect(source).toContain('watch(adjustDialogVisible, (visible) =>');
    expect(source).toContain("adjustPreviewResult.value?.status === 'PREVIEW' && !!adjustConfirmationToken.value");

    const openSource = source.slice(
      source.indexOf('function handleOpenAdjustDialog()'),
      source.indexOf('async function handleAdjustPreview()'),
    );
    expect(openSource).toContain('adjustConfirmationToken.value = null');
  });
});
