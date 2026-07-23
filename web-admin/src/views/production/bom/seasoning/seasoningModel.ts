import type {
  SeasoningBindingView,
  SeasoningMaterialSummary,
  SeasoningProcessView,
} from '@/api/bom';

/** A target-specific DAG can have several roots converging on one process node. */
export function uniqueProcessesByNode(processes: SeasoningProcessView[]): SeasoningProcessView[] {
  const byNode = new Map<string, SeasoningProcessView>();
  for (const process of [...processes].sort((a, b) => a.processOrder - b.processOrder)) {
    if (!byNode.has(process.workflowProcessNodeId)) byNode.set(process.workflowProcessNodeId, process);
  }
  return [...byNode.values()];
}

export function groupBindingsByProcess(
  processes: SeasoningProcessView[],
): Record<string, SeasoningBindingView[]> {
  return Object.fromEntries(
    processes.map((process) => [process.workflowProcessNodeId, [...(process.bindings || [])]]),
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
        priceSnapshot: binding.priceSnapshot ?? binding.priceSource1 ?? binding.priceSource2 ?? null,
        usages: [],
      };
      existing.usages.push({
        bindingId: binding.id,
        workflowProcessNodeId: process.workflowProcessNodeId,
        workProcessId: process.workProcessId,
        processOrder: process.processOrder,
        processName: process.processName,
        basisQuantity: process.standardBasisQuantity ?? process.basisQuantity ?? null,
        basisUnit: process.standardBasisUnit ?? process.basisUnit ?? null,
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
  currentWorkflowProcessNodeId: string,
): SeasoningProcessView[] {
  if (!materialTypeId) return [];
  return processes.filter(
    (process) => process.workflowProcessNodeId !== currentWorkflowProcessNodeId
      && (process.bindings || []).some((binding) => binding.materialTypeId === materialTypeId),
  );
}
