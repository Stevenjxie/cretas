export type ProcessSheetViewMode = 'grid' | 'card';

export function initialProcessSheetViewMode(savedView: string | null): ProcessSheetViewMode {
  return savedView === 'card' ? 'card' : 'grid';
}
