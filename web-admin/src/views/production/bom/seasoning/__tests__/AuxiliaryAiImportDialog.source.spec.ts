import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const dialogSource = readFileSync(resolve(import.meta.dirname, '../AuxiliaryAiImportDialog.vue'), 'utf8');
const workspaceSource = readFileSync(resolve(import.meta.dirname, '../BomAuxiliaryWorkspace.vue'), 'utf8');

describe('AI auxiliary material import', () => {
  it('recognizes files into a review table without creating material masters', () => {
    expect(dialogSource).toContain("entityType: 'BOM_AUXILIARY_BULK_IMPORT'");
    expect(dialogSource).toContain("name: 'auxiliaryMaterials'");
    expect(dialogSource).toContain('AI 只识别并匹配现有辅料档案，不会创建新物料');
    expect(dialogSource).toContain('data-testid="auxiliary-ai-file"');
    expect(dialogSource).toContain('匹配辅料档案');
    expect(dialogSource).toContain('投入工序');
    expect(dialogSource).toContain('每份用量');
  });

  it('writes matched rows sequentially with revision handoff and archive units', () => {
    expect(dialogSource).toContain('for (const row of readyRows.value)');
    expect(dialogSource).toContain('expectedRevision: revision');
    expect(dialogSource).toContain('revision = response.data.seasoningRevision');
    expect(dialogSource).toContain('const unit = canonicalUnitCode(material?.unit)');
    expect(dialogSource).toContain('档案单位缺少权威成本换算关系');
    expect(workspaceSource).toContain('data-testid="open-auxiliary-ai-import"');
    expect(workspaceSource).toContain('<AuxiliaryAiImportDialog');
    expect(workspaceSource).toContain('v-if="editable"');
  });
});
