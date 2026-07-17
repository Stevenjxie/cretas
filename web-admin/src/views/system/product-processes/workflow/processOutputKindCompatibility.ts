import type { WorkProcessOutputMaterialKind } from '@/api/processProduction';

export function needsPrimaryOutputKindUpdate(
  expectedKind: WorkProcessOutputMaterialKind | null | undefined,
  selectedKind: WorkProcessOutputMaterialKind,
  isPrimaryOutput: boolean,
): boolean {
  return isPrimaryOutput && expectedKind != null && expectedKind !== selectedKind;
}
