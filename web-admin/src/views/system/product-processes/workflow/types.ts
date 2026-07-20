export type ProductProcessNodeKind =
  | 'RAW_MATERIAL'
  | 'PROCESS'
  | 'SEMI_FINISHED'
  | 'FINISHED_GOOD';

export type WorkflowStatus = 'DRAFT' | 'SNAPSHOT' | 'PUBLISHED';
export type ConversionMode = 'ACTUAL_WEIGHT' | 'FIXED_RATIO' | 'SUM_OUTPUTS' | 'FORMULA';
export type PortSelectionMode = 'ALL_REQUIRED' | 'EXACTLY_ONE' | 'AT_LEAST_ONE' | 'OPTIONAL';

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
  /** Legacy snapshot field. The editor no longer authors planned input/output quantities. */
  standardQuantity?: number;
  quantityMode?: 'AUTO_CONVERT' | 'FIXED_RATIO';
  conversionRefId?: string | null;
  conversionVersion?: number | null;
  ordinal: number;
}

export interface ProcessPortGroup {
  id: string;
  direction: 'INPUT' | 'OUTPUT';
  label: string;
  mode: PortSelectionMode;
  minSelections: number;
  maxSelections: number;
  portIds: string[];
}

export interface ProcessNodeData extends Record<string, unknown> {
  workProcessId: string;
  processName: string;
  inputUnit: string;
  outputUnit: string;
  ports: ProcessPort[];
  /** Optional for legacy workflow JSON. Missing groups mean every port is required. */
  portGroups?: ProcessPortGroup[];
  /** Read-only BOM-derived input requirements; absence means the BOM contract is not configured. */
  inputRequirementGroups?: ProcessPortGroup[];
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
    defaultOutputMaterialKind: 'SEMI_FINISHED' | 'FINISHED_GOOD';
  };
  productTypeId: string;
  productName: string;
  /** Bound finished-product identity owns its unit; an unbound semi-finished output has no unit yet. */
  productUnit?: string;
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
  unitReviewRequired?: boolean;
  nodes: ProductProcessWorkflowNode[];
  edges: ProductProcessWorkflowEdge[];
  viewport: WorkflowViewport;
}

export interface ProductProcessWorkflowActivation {
  id: number;
  factoryId: string;
  productTypeId: string;
  activeWorkflowId: number;
  activeDefinitionVersion: number;
  enabled: boolean;
  /** 后端按已发布画布拓扑派生，用户不可手选。 */
  workflowType?: 'SINGLE_OUTPUT_PRODUCT' | 'RAW_MATERIAL_SPLIT' | 'JOINT_PRODUCTION' | null;
  rootInputProductTypeIds?: string[] | null;
  terminalOutputProductTypeIds?: string[] | null;
  activatedBy?: number | null;
  activatedAt?: string | null;
  lockVersion: number;
}

export type WorkflowPatch =
  | { op: 'UPSERT_NODE'; node: ProductProcessWorkflowNode }
  | { op: 'REMOVE_NODE'; nodeId: string }
  | { op: 'UPSERT_EDGE'; edge: ProductProcessWorkflowEdge }
  | { op: 'REMOVE_EDGE'; edgeId: string }
  | { op: 'SET_NODE_FIELD'; nodeId: string; path: string; value: unknown };

export interface WorkflowValidationError {
  code: 'SCHEMA' | 'NODE_REFERENCE' | 'SKU_REQUIRED' | 'PORT_REQUIRED' | 'PORT_GROUP_INVALID' | 'CYCLE' | 'BOUNDARY_REQUIRED';
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
