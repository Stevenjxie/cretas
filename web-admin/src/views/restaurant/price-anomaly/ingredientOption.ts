export interface IngredientIdentity {
  normalizedName?: unknown;
  ingredientName?: unknown;
}

export interface IngredientOption {
  value: string;
  label: string;
}

function cleanScalar(value: unknown): string {
  if (typeof value === 'string') {
    return value.trim().replace(/^['"]|['"]$/g, '');
  }
  if (typeof value === 'number' && Number.isFinite(value)) {
    return String(value);
  }
  return '';
}

function parseDelimitedOption(text: string): Record<string, string> | null {
  const body = text.trim().replace(/^\{|\}$/g, '');
  if (!/(?:^|,)\s*(?:value|label)\s*[:=]/i.test(body)) return null;
  const parsed: Record<string, string> = {};
  for (const part of body.split(',')) {
    const match = part.match(/^\s*(value|label)\s*[:=]\s*(.*?)\s*$/i);
    if (match) parsed[match[1].toLowerCase()] = cleanScalar(match[2]);
  }
  return parsed;
}

function optionField(raw: unknown, preferredKeys: string[]): string {
  if (raw && typeof raw === 'object' && !Array.isArray(raw)) {
    const record = raw as Record<string, unknown>;
    for (const key of preferredKeys) {
      const scalar = cleanScalar(record[key]);
      if (scalar) return scalar;
    }
    return '';
  }

  const scalar = cleanScalar(raw);
  if (!scalar) return '';
  if (scalar.startsWith('{') && scalar.endsWith('}')) {
    try {
      return optionField(JSON.parse(scalar), preferredKeys);
    } catch {
      // Some historical rows contain "value:x,label:y" rather than JSON.
    }
  }
  const delimited = parseDelimitedOption(scalar);
  if (delimited) {
    for (const key of preferredKeys) {
      if (delimited[key]) return delimited[key];
    }
  }
  return scalar;
}

export function normalizeIngredientOption(
  identity: IngredientIdentity,
): IngredientOption | null {
  const value = optionField(
    identity.normalizedName,
    ['value', 'normalizedName', 'name', 'label'],
  );
  if (!value) return null;
  const label = optionField(
    identity.ingredientName,
    ['label', 'ingredientName', 'name', 'value'],
  ) || optionField(
    identity.normalizedName,
    ['label', 'ingredientName', 'name', 'value'],
  ) || value;
  return { value, label };
}

export function normalizedIngredientValue(identity: IngredientIdentity): string {
  return normalizeIngredientOption(identity)?.value ?? '';
}

export function ingredientDisplayName(identity: IngredientIdentity): string {
  return normalizeIngredientOption(identity)?.label ?? '—';
}
