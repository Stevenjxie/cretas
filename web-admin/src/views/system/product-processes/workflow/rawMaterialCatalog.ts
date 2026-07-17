import { bigCategoryOf } from '@/utils/materialCategory';

export interface RawMaterialPickerOption {
  id: string;
  name: string;
  code?: string;
  unit?: string;
  category?: string | null;
  segmentCode?: string | null;
}

export interface MaterialSegmentNode {
  id?: number;
  level: number;
  segmentCode: string;
  segmentLabel: string;
  parentCode?: string | null;
  isActive?: boolean;
  children?: MaterialSegmentNode[] | null;
}

export function isRawMaterialOption(option: RawMaterialPickerOption): boolean {
  return bigCategoryOf(option.category) === '原料';
}

export function buildRawMaterialSegmentTree(tree: MaterialSegmentNode[]): MaterialSegmentNode[] {
  return tree
    .filter((node) => node.isActive !== false)
    .filter((node) => node.segmentCode === '001' || String(node.segmentLabel || '').trim() === '原料')
    .map(copyActiveNode);
}

export function filterRawMaterialsBySegment(
  options: RawMaterialPickerOption[],
  tree: MaterialSegmentNode[],
  selectedPath: string[],
): RawMaterialPickerOption[] {
  const rawOptions = options.filter(isRawMaterialOption);
  const selectedCode = selectedPath.at(-1);
  if (!selectedCode || selectedCode === '001') return rawOptions;

  const selectedNode = findSegmentNode(tree, selectedCode);
  if (!selectedNode) return [];
  const descendantNames = new Set<string>();
  collectLeafNames(selectedNode, descendantNames);

  return rawOptions.filter((option) => {
    const structuredCode = String(option.segmentCode || option.code || '').trim();
    if (/^\d{10,16}$/.test(structuredCode) && structuredCode.startsWith(selectedCode)) return true;
    return descendantNames.has(normalizeMaterialName(option.name));
  });
}

function copyActiveNode(node: MaterialSegmentNode): MaterialSegmentNode {
  const children = (node.children || [])
    .filter((child) => child.isActive !== false)
    .map(copyActiveNode);
  return {
    ...node,
    children: children.length > 0 ? children : undefined,
  };
}

function findSegmentNode(nodes: MaterialSegmentNode[], segmentCode: string): MaterialSegmentNode | undefined {
  for (const node of nodes) {
    if (node.segmentCode === segmentCode) return node;
    const found = findSegmentNode(node.children || [], segmentCode);
    if (found) return found;
  }
  return undefined;
}

function collectLeafNames(node: MaterialSegmentNode, names: Set<string>): void {
  const children = node.children || [];
  if (children.length === 0) {
    names.add(normalizeMaterialName(node.segmentLabel));
    return;
  }
  children.forEach((child) => collectLeafNames(child, names));
}

function normalizeMaterialName(value: string): string {
  return value.trim().toLocaleLowerCase().replace(/\s+/g, '');
}
