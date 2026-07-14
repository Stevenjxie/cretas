import type {
  SeasoningBindingView,
  SeasoningMaterialSummary,
  SeasoningProcessView,
} from '@/api/bom';

export function groupBindingsByProcess(
  processes: SeasoningProcessView[],
): Record<string, SeasoningBindingView[]> {
  return Object.fromEntries(
    processes.map((process) => [process.workProcessId, [...(process.bindings || [])]]),
  );
}

export function buildMaterialSummaries(
  processes: SeasoningProcessView[],
): Array<SeasoningMaterialSummary & { usages: NonNullable<SeasoningMaterialSummary['usages']> }> {
  const summaries = new Map<string, SeasoningMaterialSummary & { usages: NonNullable<SeasoningMaterialSummary['usages']> }>();
  for (const process of [...processes].sort((a, b) => a.processOrder - b.processOrder)) {
    for (const binding of process.bindings || []) {
      if (!binding.materialTypeId) continue;
      const existing = summaries.get(binding.materialTypeId) ?? {
        materialTypeId: binding.materialTypeId,
        materialName: binding.materialName || binding.name,
        materialCode: binding.materialCode ?? null,
        unit: binding.unit ?? 'g',
        priceSnapshot: binding.priceSnapshot ?? binding.priceSource1 ?? null,
        usages: [],
      };
      existing.usages.push({
        bindingId: binding.id,
        workProcessId: process.workProcessId,
        processOrder: process.processOrder,
        processName: process.processName,
        dosagePerKgG: binding.dosagePerKgG,
        subsequentPotRatio: binding.subsequentPotRatio,
        countInSeasoning: binding.countInSeasoning,
      });
      summaries.set(binding.materialTypeId, existing);
    }
  }
  return [...summaries.values()];
}

export function findDuplicateBinding(
  process: SeasoningProcessView,
  materialTypeId: string,
  excludeBindingId?: number | null,
): SeasoningBindingView | undefined {
  return (process.bindings || []).find(
    (binding) => binding.materialTypeId === materialTypeId && binding.id !== excludeBindingId,
  );
}

export function otherProcessUsages(
  processes: SeasoningProcessView[],
  materialTypeId: string | null,
  currentWorkProcessId: string,
): SeasoningProcessView[] {
  if (!materialTypeId) return [];
  return processes.filter(
    (process) => process.workProcessId !== currentWorkProcessId
      && (process.bindings || []).some((binding) => binding.materialTypeId === materialTypeId),
  );
}
