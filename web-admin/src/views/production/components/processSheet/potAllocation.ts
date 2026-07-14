const KG_PER_UNIT: Record<string, number> = {
  kg: 1,
  g: 1 / 1_000,
  mg: 1 / 1_000_000,
};

/**
 * Convert the process input to kg and split it equally for the backend potRawKgs contract.
 * Unknown/count units are rejected so their numeric value is never silently treated as kg.
 */
export function buildEqualPotWeightsKg(
  inputQuantity: number,
  inputUnit: string,
  potCount: number,
): number[] {
  if (!Number.isInteger(potCount) || potCount < 1) {
    throw new Error('锅数必须是大于等于 1 的整数');
  }
  if (!Number.isFinite(inputQuantity) || inputQuantity <= 0) {
    throw new Error('请先填写大于 0 的投入量');
  }

  const normalizedUnit = inputUnit.trim().toLowerCase();
  const kgFactor = KG_PER_UNIT[normalizedUnit];
  if (kgFactor == null) {
    throw new Error(`投入单位「${inputUnit || '未配置'}」不支持按锅等分，请将工序投入单位配置为 kg、g 或 mg`);
  }

  // potRawKgs is serialized to six decimal places. Allocate integer micro-kilograms so
  // division remainders are retained instead of losing mass (for example 10 / 3).
  const totalMicroKg = Math.round(inputQuantity * kgFactor * 1_000_000);
  const baseMicroKg = Math.floor(totalMicroKg / potCount);
  const remainder = totalMicroKg % potCount;
  return Array.from(
    { length: potCount },
    (_, index) => (baseMicroKg + (index < remainder ? 1 : 0)) / 1_000_000,
  );
}
