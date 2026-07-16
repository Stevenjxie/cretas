export function reconcilePackagingSpecs<T>(
  productCategory: string | null | undefined,
  specs: T[],
  createDefault: () => T,
): T[] {
  if (productCategory === 'SEMI_FINISHED') return [];
  return specs.length > 0 ? specs : [createDefault()];
}

export function isCurrentCategorySuggestion(
  currentName: string,
  currentCategory: string | null | undefined,
  requestedName: string,
  requestedCategory: string | null | undefined,
  responseCategory?: string | null,
): boolean {
  return currentName.trim() === requestedName
    && currentCategory === requestedCategory
    && (!responseCategory || responseCategory === requestedCategory);
}
