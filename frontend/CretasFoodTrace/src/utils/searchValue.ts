/** Normalize API identifiers that may arrive as either JSON numbers or strings. */
export function normalizedSearchValue(value: unknown): string {
  return value == null ? '' : String(value).toLowerCase();
}
