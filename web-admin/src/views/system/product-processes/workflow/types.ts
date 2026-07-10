export type ProductProcessNodeKind =
  | 'RAW_MATERIAL'
  | 'PROCESS'
  | 'SEMI_FINISHED'
  | 'FINISHED_GOOD';

export type WorkflowStatus = 'DRAFT' | 'PUBLISHED';
export type ConversionMode = 'ACTUAL_WEIGHT' | 'FIXED_RATIO' | 'SUM_OUTPUTS' | 'FORMULA';

export interface WorkflowPosition {
  x: number;
  y: number;
}

export interface WorkflowViewport extends WorkflowPosition {
  zoom: number;
}

export interface MaterialNodeData extends Record<string, unknown> {
  name: string;
  skuId: string;
  skuCode?: string | null;
  specification?: string | null;
  baseUnit?: string;
  bound?: boolean;
}

export interface ProcessPort {
  id: string;
  direction: 'INPUT' | 'OUTPUT';
  materialNodeId?: string;
  materialName?: string;
  skuId?: string;
  materialKind?: Exclude<ProductProcessNodeKind, 'PROCESS'>;
  unit: string;
  ordinal: number;
}

export interface ProcessNodeData extends Record<string, unknown> {
  workProcessId: string;
  processName: string;
  inputUnit: string;
  outputUnit: string;
  ports: ProcessPort[];
  conversionRule: {
    mode: ConversionMode;
    expression?: string | null;
  };
  reportingRequired: boolean;
  processCategory?: string | null;
  standardTime?: number | null;
  allowMultipleUpstreamSources?: boolean;
  allowFinishedGoodsSource?: boolean;
}

export type WorkflowNodeData = MaterialNodeData | ProcessNodeData;

export interface ProductProcessWorkflowNode {
  id: string;
  kind: ProductProcessNodeKind;
  position: WorkflowPosition;
  data: WorkflowNodeData;
}

export interface ProductProcessWorkflowEdge {
  id: string;
  source: string;
  sourceHandle: string;
  target: string;
  targetHandle: string;
}

export interface ProcessBranchInput {
  source: ProductProcessWorkflowNode;
  workProcess: {
    id: string;
    processName: string;
    unit: string;
    outputUnit?: string | null;
    defaultOutputMaterialKind: 'SEMI_FINISHED' | 'FINISHED_GOOD';
  };
  productTypeId: string;
  productName: string;
  timestamp: number;
}

export interface ProductProcessWorkflowDefinition {
  id?: number;
  factoryId?: string;
  productTypeId?: string;
  schemaVersion: 1;
  status: WorkflowStatus;
  version: number;
  lockVersion?: number;
  nodes: ProductProcessWorkflowNode[];
  edges: ProductProcessWorkflowEdge[];
  viewport: WorkflowViewport;
}

export type WorkflowPatch =
  | { op: 'UPSERT_NODE'; node: ProductProcessWorkflowNode }
  | { op: 'REMOVE_NODE'; nodeId: string }
  | { op: 'UPSERT_EDGE'; edge: ProductProcessWorkflowEdge }
  | { op: 'REMOVE_EDGE'; edgeId: string }
  | { op: 'SET_NODE_FIELD'; nodeId: string; path: string; value: unknown };

export interface WorkflowValidationError {
  code: 'SCHEMA' | 'NODE_REFERENCE' | 'SKU_REQUIRED' | 'PORT_REQUIRED' | 'CYCLE' | 'BOUNDARY_REQUIRED';
  message: string;
  nodeId?: string;
  edgeId?: string;
}

export interface LegacyProductWorkProcess {
  id: number;
  workProcessId: string;
  processName?: string;
  processOrder?: number;
  defaultUnit?: string;
  unitOverride?: string;
  reportingRequired?: boolean;
  allowMultipleUpstreamSources?: boolean;
  allowFinishedGoodsSource?: boolean;
}
