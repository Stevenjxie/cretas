<script setup lang="ts">
/**
 * 采购入库 — 收货管理
 *
 * 六扇门 V1 §3.3 (14-requirements-matrix #9): 采购到货扫码/手动录入 + 自动更新库存.
 * Apr 26 audit: backend POST /purchase/receives + GET /receives + /receives/{id}/confirm
 * 都已 R26 P2 verified, 但缺 FE 入口. 本页补齐手动录入路径 (扫码模式 V2 backlog).
 *
 * Backend endpoints (PurchaseController):
 *   GET  /api/mobile/{factoryId}/purchase/receives?page=1&size=20
 *   POST /api/mobile/{factoryId}/purchase/receives                  (create DRAFT)
 *   GET  /api/mobile/{factoryId}/purchase/receives/{receiveId}      (detail)
 *   POST /api/mobile/{factoryId}/purchase/receives/{receiveId}/confirm  (DRAFT → CONFIRMED, 生成物料批次)
 */
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import UpstreamMissingHint from '@/components/common/UpstreamMissingHint.vue';
import { useCreateAndReturn } from '@/composables/useCreateAndReturn';
import { get, post } from '@/api/request';
import { listManufacturers, type ManufacturerRegistry } from '@/api/manufacturer';
// 单元 G (F006 R-B3): 跨页累计 + 分次收货时序明细 (后端权威值, 替代 page-local 聚合)
import {
  getCumulativeReceived,
  getOrderReceiveSequence,
  getPurchaseInboundDefaultWarehouse,
  type CumulativeReceived,
  type ReceiveSequenceEntry,
} from '@/api/purchaseReceive';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Refresh, Check, Document, ChatDotRound, List } from '@element-plus/icons-vue';
import ConceptDisambiguationAlert from '@/components/common/ConceptDisambiguationAlert.vue';
import { RowActionMenu } from '@/components/list';
import { computeRowActions } from '@/composables/useRowActions';
import { safePrint } from '@/api/printApi';
import AiEntryDrawer from '@/components/ai-entry/AiEntryDrawer.vue';
import { WH_INBOUND_CONFIG } from '@/components/ai-entry/types';
// 客户张权反馈 (2026-07-02, "小问题"): 入库单没法选仓库, 默认全进物流仓(WH-LOG).
// 后端早已支持 (PurchaseServiceImpl L1122/L2062-2071 CreateReceiveRecordRequest.warehouseId,
// 未传才兜底 resolveLogisticsId), 只缺前端选择器.
import { listWarehouses, type FactoryWarehouse, type WarehouseType } from '@/api/factoryWarehouse';
// Issue #794: 采购入库拍照附件 UI (六扇门 May 7 part 2 L177-180)
// "拍照也可以留个单谱吧... 留个附件类似一个拍照然后一个附件吗也可以的呀"
import AttachmentList from '@/components/attachment/AttachmentList.vue';
import AttachmentUploadButton from '@/components/attachment/AttachmentUploadButton.vue';

interface ReceiveRow {
  id: string;
  receiveNumber: string;
  purchaseOrderId?: string;
  purchaseOrderNumber?: string;
  supplierId: string;
  supplierName?: string;
  receiveDate: string;
  // PurchaseReceiveStatus.java real values — was 'DRAFT'|'CONFIRMED'|'CANCELLED'
  // ('CANCELLED' never exists on this entity; 'PENDING_QC'/'REJECTED' were missing).
  status: 'DRAFT' | 'PENDING_QC' | 'CONFIRMED' | 'REJECTED';
  warehouseId?: string;
  remark?: string;
  itemCount?: number;
  items?: ReceiveItem[];
  createdAt: string;
  createdByName?: string;
}

interface ReceiveItem {
  id?: string;
  materialTypeId: string;
  materialName: string;
  receivedQuantity: number;
  unit: string;
  unitPrice?: number;
  qcResult?: string;
  factoryNumber?: string;
  originPlace?: string;
  remark?: string;
  // UI-only (not sent to backend, stripped in handleSubmit): 客户张权反馈 (2026-07-02) 大类筛选,
  // 让仓管先选 原料/辅料/调料/包材 再选具体物料, 而不是在一个混杂几十项的大下拉里翻找。
  _bigCategory?: BigCategory | '';
  // UI-only (fool-proof-design Rule 1, 2026-07-04): 仅 PO 带入行有值 — 本行「待收量」(下单量-已收累计)
  // + 本次可收上限 (待收量所在 PO 行下单量 × (1+超收率) - 已收累计), 供 el-input-number :max 前置拦截 +
  // 灰字提示, 别等 confirm 阶段撞后端 409 才知道超收. 手动加行(非 PO 带入)无值 = 无前端上限 (后端仍兜底).
  _pendingQty?: number;
  _maxAllowed?: number;
}

interface SupplierOption { id: string; name: string }
interface MaterialTypeOption {
  id: string; name: string; code: string; unit: string; unitPrice?: number;
  // category: RawMaterialType.category 自由文本字段, 用于 bigCategoryOf() 归类到 4 大类.
  // createdAt: 用于物料下拉按创建时间倒序 (最新物料排最前).
  category?: string; createdAt?: string;
}
interface PurchaseOrderOption { id: string; orderNumber: string; supplierName?: string; supplierId?: string; status?: string }

// 客户张权反馈 (2026-07-02): 物料下拉太乱 (原料/辅料/调料/包材混在一起, 面酱/白醋/牛腩排/黄油…),
// 加"先选大类再选物料"两级筛选。归类逻辑提取到 utils/materialCategory.ts (2026-07-02 单一源,
// production/bom/index.vue 等其它物料选择器复用同一份归类规则)。
import { bigCategoryOf, BIG_CATEGORY_OPTIONS, filterOptionsByBigCategory, type BigCategory } from '@/utils/materialCategory';

function materialsForRow(row: ReceiveItem, options: MaterialTypeOption[]): MaterialTypeOption[] {
  return filterOptionsByBigCategory(options, row._bigCategory);
}

const authStore = useAuthStore();
const router = useRouter();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('procurement'));
const { goCreate } = useCreateAndReturn();
const canViewPrice = computed(() => permissionStore.canViewPrice);

function rowActionsFor(row: ReceiveRow) {
  return computeRowActions(
    'whInbound',
    { status: String(row.status || 'DRAFT'), id: String(row.id || '') },
    { canViewPrice: canViewPrice.value }
  );
}
function handleRowActionClick(actionId: string, row: ReceiveRow) {
  switch (actionId) {
    case 'view-detail': handleDetail(row); break;
    case 'submit': handleConfirm(row); break;
    // 入库单复用 material-requisition 模板 (warehouse:read 准入)
    case 'print-pdf': void safePrint('material-requisition', factoryId.value, String(row.id), { fileName: `入库单_${row.receiveNumber || row.id}` }); break;
    default: ElMessage.warning(`该操作暂不支持: ${actionId}`);
  }
}
// AI 智能创建入库单 (Day 9, Issue #780.2)
const aiEntryVisible = ref(false);
function openAiForRow(_row: ReceiveRow) {
  // Row-context AI is future scope; current Day 9 opens drawer for CREATE flow
  aiEntryVisible.value = true;
}
function openAiCreate() {
  aiEntryVisible.value = true;
}
function handleAiFill(params: Record<string, unknown>) {
  // Pre-fill the create dialog with AI-collected data, then open it for user confirmation
  const supplierName = String(params.supplierName || '');
  const matchedSupplier = supplierOptions.value.find(
    (s) => String(s.name || '').includes(supplierName) || supplierName.includes(String(s.name || ''))
  );
  const poNumber = String(params.purchaseOrderNumber || '');
  const matchedPo = poNumber
    ? purchaseOrderOptions.value.find(
        (p) => String(p.orderNumber || '').toUpperCase() === poNumber.toUpperCase()
      )
    : undefined;

  form.value = {
    purchaseOrderId: matchedPo ? String(matchedPo.id) : '',
    supplierId: matchedSupplier ? String(matchedSupplier.id) : (matchedPo?.supplierId || ''),
    receiveDate: String(params.receiveDate || new Date().toISOString().slice(0, 10)),
    warehouseId: '',
    remark: String(params.remark || ''),
    items: [],
  };

  if (Array.isArray(params.items) && params.items.length > 0) {
    form.value.items = (params.items as Array<Record<string, unknown>>).map((it) => {
      const matName = String(it.materialName || '');
      const matched = materialOptions.value.find(
        (m) => String(m.name || '').includes(matName) || matName.includes(String(m.name || ''))
      );
      return {
        materialTypeId: matched ? String(matched.id) : '',
        materialName: matched ? matched.name : matName,
        receivedQuantity: Number(it.receivedQuantity || 0),
        unit: String(it.unit || matched?.unit || 'kg'),
        unitPrice: matched?.unitPrice,
        qcResult: '',
        factoryNumber: '',
        originPlace: '',
        remark: '',
        // 同 handlePoChange: 大类跟随 AI 匹配到的实际物料类别; 未匹配到 (AI 没认出/新物料) → 全部.
        _bigCategory: matched ? bigCategoryOf(matched.category) : '',
      };
    });
  }

  // Open create dialog (loadOptions handled by handleCreate, but we want pre-fill not reset)
  const optionsReady = supplierOptions.value.length > 0
    ? Promise.resolve()
    : loadOptions();
  optionsReady.then(() => {
    if (!form.value.warehouseId) {
      form.value.warehouseId = defaultReceiveWarehouseId(warehouseOptions.value);
    }
  });
  createVisible.value = true;
}

const loading = ref(false);
const tableData = ref<ReceiveRow[]>([]);
const pagination = ref({ page: 1, size: 20, total: 0 });

// Detail dialog
const detailVisible = ref(false);
const detailData = ref<ReceiveRow | null>(null);

// Issue #794: 附件刷新 key — 上传后递增触发 AttachmentList 重拉
const attachmentRefreshKey = ref(0);

// Create dialog
const createVisible = ref(false);
const submitting = ref(false);
const formRef = ref();
const form = ref({
  purchaseOrderId: '',
  supplierId: '',
  receiveDate: new Date().toISOString().slice(0, 10),
  warehouseId: '',
  remark: '',
  items: [] as ReceiveItem[],
});
const formRules = {
  supplierId: [{ required: true, message: '请选择供应商', trigger: 'change' }],
  receiveDate: [{ required: true, message: '请选择入库日期', trigger: 'change' }],
};

const supplierOptions = ref<SupplierOption[]>([]);
const materialOptions = ref<MaterialTypeOption[]>([]);
const purchaseOrderOptions = ref<PurchaseOrderOption[]>([]);
const manufacturerOptions = ref<ManufacturerRegistry[]>([]);
const warehouseOptions = ref<FactoryWarehouse[]>([]);
// 客户张权反馈 (2026-07-02→07-04): 入库仓库应默认工厂配置的「采购入库默认仓」(PURCHASE_INBOUND_DEFAULT),
// 而非硬编码物流仓。后端 GET /purchase/receives/default-warehouse 解析该配置 (未配置回退 WH-LOG)。
// 六扇门(F006) 已配采购入库默认仓=原料仓 → 仓管不用每次手动改选。null = 未拿到 (回退本地默认逻辑)。
const inboundDefaultWarehouse = ref<FactoryWarehouse | null>(null);

/**
 * 客户张权反馈 (2026-07-02): 入库仓库下拉不该列出成品仓/半成品仓/车间/研发库这些采购收货用不到的仓,
 * 只列能收原料的仓。
 *
 * 对齐后端 WarehouseInventoryGuardService.assertCanReceive: WIP 仓只收 SEMI_FINISHED、FINISHED 仓
 * 只收 FINISHED — 两者显式拒绝 RAW，选了会在确认入库时被 422 拦。RAW/SALTED 仓显式只收 RAW。
 * LOGISTICS(物流仓) 未被 guard 显式限制，且是 WarehouseResolver.resolveLogisticsId 的原料默认落点
 * (采购入库未指定仓库时的兜底仓)，纳入白名单。
 *
 * WORKSHOP(车间/鲜棉仓, WH-WKS = resolveWorkshopId 报工消耗默认仓) 和 RD(研发/中试库, WH-RD = 试制
 * 批次专属仓) 虽未被 guard 显式拦截，但有各自专属业务用途，不该出现在采购入库候选里 — 排除。
 * TEMP/QC/LINESIDE/RETURNS/SCRAP/OUTSOURCE/TRANSFER/OTHER 同理，都是专项流转仓非常规采购收货目标。
 */
const RAW_RECEIVABLE_WAREHOUSE_TYPES: WarehouseType[] = ['RAW', 'SALTED', 'LOGISTICS'];
const rawReceivableWarehouses = computed(() => {
  const list = warehouseOptions.value.filter((w) => RAW_RECEIVABLE_WAREHOUSE_TYPES.includes(w.type));
  // 若工厂把「采购入库默认仓」配到了白名单之外的仓型 (e.g. 半成品仓), 也把它并入候选,
  // 否则预填的默认值在下拉里没有匹配 option → el-select 显示空白 (仓管以为没选仓)。
  const cfg = inboundDefaultWarehouse.value;
  if (cfg && cfg.isActive !== false && !list.some((w) => w.id === cfg.id)) {
    list.push(cfg);
  }
  return list;
});

/**
 * 原料入库推荐仓。优先用工厂配置的「采购入库默认仓」(PURCHASE_INBOUND_DEFAULT, 后端解析),
 * 未配置/未拿到时回退 **物流仓(LOGISTICS/WH-LOG)** — 与 WarehouseResolver.resolvePurchaseInboundWh
 * 的 fallback 一致, 保证前端预选 == 后端未传 warehouseId 时的实际落点。
 *
 * 防呆 Rule 1 (2026-07-04): 六扇门(F006) 已配采购入库默认仓=原料仓, 但前端此前硬编码默认物流仓,
 * 导致仓管每次新建入库都要手动改选。改为读后端解析后的默认仓 → 常见场景零手动重选。
 *
 * ⚠️ 历史 (2026-07-02): resolveLogisticsId 曾被采购/生产/销售三语义共用, 改这里默认会连坐生产/销售;
 * 现后端已拆出独立 PURCHASE_INBOUND_DEFAULT purpose (只影响采购入库落点), 前端跟随配置安全无连坐。
 * RAW/SALTED 仍在候选(用户可手选)。
 */
function defaultReceiveWarehouseId(list: FactoryWarehouse[]): string {
  // 1) 工厂配置的采购入库默认仓 (后端解析), 且仍是本页可选的有效仓。
  const cfg = inboundDefaultWarehouse.value;
  if (cfg && cfg.isActive !== false) return cfg.id;
  // 2) 回退物流仓 (与后端 resolvePurchaseInboundWh fallback 一致)。
  const logistics = list.find((w) => w.type === 'LOGISTICS' && w.isActive !== false);
  if (logistics) return logistics.id;
  // 3) 再兜底原料仓。
  const raw = list.find((w) => w.type === 'RAW' && w.isActive !== false);
  return raw ? raw.id : '';
}

/**
 * 抄收上限率 — 与后端 PurchaseServiceImpl.overReceiveRate 默认值一致
 * (application.properties: cretas.purchase.over-receive-rate, 默认 0.30)。
 *
 * fool-proof-design Rule 1: 前端只用这个值做「上限提示 + :max 前置拦截」，权威校验仍在后端
 * (checkOverReceiveCap)。极少数 ops 临时调宽后端 rate 的场景下，前端提示会偏保守 (仍按 30% 算)，
 * 但不会误挡合法收货 — 后端才是唯一真相源，前端算出来的 :max 只是"不用等 409 才知道"的体验层。
 */
const OVER_RECEIVE_RATE = 0.3;

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await get<{ content: ReceiveRow[]; totalElements: number }>(
      `/${factoryId.value}/purchase/receives`,
      { params: { page: pagination.value.page, size: pagination.value.size } }
    );
    if (res.success && res.data) {
      tableData.value = res.data.content || [];
      pagination.value.total = res.data.totalElements || 0;
    }
  } catch { /* interceptor toasts */ }
  finally { loading.value = false; }
}

async function loadOptions() {
  if (!factoryId.value) return;
  try {
    const [sup, mat, po, manufacturers, warehouses, inboundDefault] = await Promise.all([
      get<{ content: SupplierOption[] } | SupplierOption[]>(
        `/${factoryId.value}/reference-data/suppliers?usage=active`
      ),
      // 客户张权反馈 (2026-07-02): 物料下拉需按大类筛选 + 按创建时间排序, 两者都需要
      // reference-data/materials 缺失的字段 (category 有但 createdAt 无)。改用
      // /raw-material-types/active — 同一批激活物料, DTO 含 category + createdAt +
      // 未加权限限制 (无 @RequirePermission), CRUD 已 @CacheEvict 保新鲜, 纯前端换数据源
      // 不改后端。
      get<MaterialTypeOption[] | { content: MaterialTypeOption[] }>(
        `/${factoryId.value}/raw-material-types/active`
      ),
      get<{ content: PurchaseOrderOption[] } | PurchaseOrderOption[]>(
        `/${factoryId.value}/reference-data/purchase-orders?usage=receivable`
      ),
      listManufacturers(factoryId.value, true),
      listWarehouses(factoryId.value),
      // 采购入库默认仓 (后端解析 PURCHASE_INBOUND_DEFAULT 配置)。失败不阻断整体加载 —
      // honest-null → defaultReceiveWarehouseId 回退物流仓本地逻辑。
      getPurchaseInboundDefaultWarehouse(factoryId.value).catch(() => ({ success: false, data: null })),
    ]);
    const ext = <T,>(r: any): T[] => (r?.data?.content || r?.data?.list || r?.data || []) as T[];
    supplierOptions.value = ext<SupplierOption>(sup);
    // Req 3 (2026-07-02): 按创建时间倒序 — 最新建档的物料排最前, 方便找刚录入的品类.
    materialOptions.value = ext<MaterialTypeOption>(mat).slice().sort((a, b) => {
      const ta = a.createdAt ? new Date(a.createdAt).getTime() : 0;
      const tb = b.createdAt ? new Date(b.createdAt).getTime() : 0;
      return tb - ta;
    });
    // 后端 /reference-data/purchase-orders 返回 { id, poNumber, supplierId }
    // (ReferenceDataController: displayField=poNumber per V13), 不含 orderNumber/supplierName。
    // normalize 到本组件契约 (orderNumber + supplierName), 否则下拉 label 显示 "undefined ·"
    // 且 matchedPo 按 orderNumber 匹配永远失败 → 选 PO 自动带物料/供应商全部失效。
    purchaseOrderOptions.value = ext<PurchaseOrderOption & { poNumber?: string }>(po).map((p) => ({
      ...p,
      orderNumber: p.orderNumber || p.poNumber || '',
      supplierName: p.supplierName || supplierOptions.value.find((s) => s.id === p.supplierId)?.name || '',
    }));
    manufacturerOptions.value = ext<ManufacturerRegistry>(manufacturers);
    warehouseOptions.value = (warehouses?.data || []).filter((w) => w.isActive !== false);
    // data 可能为 null (工厂缺仓库 seed / 解析失败) → 保持 null, 走 defaultReceiveWarehouseId 回退。
    inboundDefaultWarehouse.value = inboundDefault?.data ?? null;
  } catch { /* interceptor */ }
}

function handleCreate() {
  form.value = {
    purchaseOrderId: '',
    supplierId: '',
    receiveDate: new Date().toISOString().slice(0, 10),
    // 默认 = 工厂配置的采购入库默认仓 (无配置回退物流仓); loadOptions() 完成后回填 (见下 .then)
    warehouseId: '',
    remark: '',
    items: [],
  };
  loadOptions().then(() => {
    // Rule 1 (fool-proof-design): 预填工厂配置的采购入库默认仓, 用户仍可改选; 不选也不阻塞(后端兜底)。
    if (!form.value.warehouseId) {
      form.value.warehouseId = defaultReceiveWarehouseId(warehouseOptions.value);
    }
  });
  createVisible.value = true;
  // Auto-populate supplier when PO selected (single-source-of-truth FE convenience)
}

async function handlePoChange(poId: string) {
  if (!poId) return;
  const po = purchaseOrderOptions.value.find(p => p.id === poId);
  if (po?.supplierId) form.value.supplierId = po.supplierId;
  // 防呆 Rule1/2 (fool-proof-design; 客户原话: "你告诉他这个东西你要收多少就行了"):
  // 选 PO 后自动带入待收物料明细, 默认实收数量 = 待收量, 仓管只需核对实收 + 厂号/产地,
  // 不用手动「添加物料」再逐个选物料类型 (对低技术素养仓管员太重)。
  try {
    const res = await get<{ items?: Array<Record<string, unknown>> }>(
      `/${factoryId.value}/purchase/orders/${poId}`
    );
    const items = (res?.data?.items || []).filter(
      (it) => Number((it.pendingQuantity ?? it.quantity) || 0) > 0
    );
    if (items.length) {
      form.value.items = items.map((it) => {
        const materialTypeId = String(it.materialTypeId || '');
        // 已知具体物料 (来自 PO 明细) → 大类跟随该物料实际类别, 而非强制"原料",
        // 避免选了辅料/包材的 PO 行后大类下拉把刚带入的物料筛没了.
        const matched = materialOptions.value.find((m) => m.id === materialTypeId);
        const orderedQty = Number(it.quantity || 0);
        const alreadyReceived = Number(it.receivedQuantity || 0); // PO 行累计已收 (非本次到货数量)
        const pendingQty = Number(it.pendingQuantity ?? Math.max(orderedQty - alreadyReceived, 0));
        // fool-proof-design Rule 1: 本次最多可收 = 下单量×(1+超收率) - 已收累计. 早显给仓管,
        // 不等 confirm 阶段撞后端 409「超出可入库上限」才知道.
        const maxAllowed = orderedQty > 0
          ? Math.max(orderedQty * (1 + OVER_RECEIVE_RATE) - alreadyReceived, 0)
          : undefined;
        return {
          materialTypeId,
          materialName: String(it.materialName || ''),
          receivedQuantity: pendingQty,
          unit: String(it.unit || 'kg'),
          unitPrice: it.unitPrice != null ? Number(it.unitPrice) : undefined,
          qcResult: '',
          factoryNumber: '',
          originPlace: '',
          remark: '',
          _bigCategory: matched ? bigCategoryOf(matched.category) : '',
          _pendingQty: pendingQty,
          _maxAllowed: maxAllowed,
        };
      });
      ElMessage.success(`已带入 ${items.length} 项待收物料 (默认实收=待收量)，请核对实收数量与厂号/产地`);
    }
  } catch { /* interceptor 已 toast */ }
}

function addItem() {
  form.value.items.push({
    materialTypeId: '',
    materialName: '',
    receivedQuantity: 0,
    unit: 'kg',
    unitPrice: undefined,
    qcResult: '',
    factoryNumber: '',
    originPlace: '',
    remark: '',
    // Req 2: 默认大类=原料 (fool-proof-design Rule 3: 约束选择而非自由翻找; 最常见场景优先)
    _bigCategory: '原料',
  });
}

function removeItem(idx: number) {
  form.value.items.splice(idx, 1);
}

// fool-proof-design Rule 1: 逐行判断是否超出「本次可收上限」— 供 :max 提示 + 提交前置拦截共用.
function isOverLimit(row: ReceiveItem): boolean {
  return row._maxAllowed != null && Number(row.receivedQuantity) > row._maxAllowed;
}
const overLimitRowIndex = computed(() => form.value.items.findIndex(isOverLimit));
const hasOverLimitItem = computed(() => overLimitRowIndex.value >= 0);

function handleMaterialChange(idx: number, materialId: string) {
  const m = materialOptions.value.find(o => o.id === materialId);
  if (m) {
    form.value.items[idx].materialName = m.name;
    if (m.unit) form.value.items[idx].unit = m.unit;
    if (m.unitPrice && !form.value.items[idx].unitPrice) form.value.items[idx].unitPrice = m.unitPrice;
  }
}

function handleManufacturerChange(idx: number, code: string) {
  const manufacturer = manufacturerOptions.value.find((item) => item.code === code);
  if (manufacturer?.originPlace) {
    form.value.items[idx].originPlace = manufacturer.originPlace;
  }
}

async function handleSubmit() {
  if (!formRef.value) return;
  const valid = await formRef.value.validate().catch(() => false);
  if (!valid) return;
  if (form.value.items.length === 0) {
    ElMessage.warning('请至少添加一行入库物料');
    return;
  }
  const noMatIdx = form.value.items.findIndex(i => !i.materialTypeId);
  if (noMatIdx >= 0) {
    ElMessage.warning(`第 ${noMatIdx + 1} 行请选择物料类型`);
    return;
  }
  const badQtyIdx = form.value.items.findIndex(i => !(Number(i.receivedQuantity) > 0));
  if (badQtyIdx >= 0) {
    ElMessage.warning(`第 ${badQtyIdx + 1} 行到货数量必须大于 0`);
    return;
  }
  const noUnitIdx = form.value.items.findIndex(i => !i.unit);
  if (noUnitIdx >= 0) {
    ElMessage.warning(`第 ${noUnitIdx + 1} 行请填写单位`);
    return;
  }
  // fool-proof-design Rule 1 前置拦截 (与 :max + 提交按钮 disable 三重防御,
  // 防止绕开 UI 直接调用 handleSubmit / :max 未及时响应式更新等边界情况).
  if (hasOverLimitItem.value) {
    const idx = overLimitRowIndex.value;
    const row = form.value.items[idx];
    // fool-proof-design 4位一体: 提交禁用之外的最后防线 (绕开 UI 直调 handleSubmit 等边界),
    // sticky (duration:0 + showClose) — 别让用户没看清就自动消失.
    ElMessage({
      type: 'error',
      duration: 0,
      showClose: true,
      message:
        `第 ${idx + 1} 行「${row.materialName || '该物料'}」到货数量超出本次可收上限 ` +
        `${row._maxAllowed?.toFixed(3).replace(/\.?0+$/, '')}${row.unit || ''} (含30%超收), 请调整或分单另采购`,
    });
    return;
  }
  submitting.value = true;
  try {
    const payload = {
      ...form.value,
      // _bigCategory/_pendingQty/_maxAllowed 是纯前端筛选/防呆提示状态 (Req 2 / fool-proof-design
      // Rule 1), 不是后端 CreateReceiveRecordRequest 契约字段, 剥离掉.
      items: form.value.items.map(({ _bigCategory, _pendingQty, _maxAllowed, ...item }) => item),
    };
    if (!payload.purchaseOrderId) delete (payload as Record<string, unknown>).purchaseOrderId;
    const res = await post<ReceiveRow>(`/${factoryId.value}/purchase/receives`, payload);
    if (res.success && res.data) {
      ElMessage.success('入库单已创建,DRAFT 状态。点击「确认入库」生成物料批次');
      createVisible.value = false;
      loadData();
    }
  } catch (e) {
    if (e === 'cancel') return;
    const err = e as { status?: number; actionHint?: string | null } | undefined;
    if (!err || (err.status !== 409 && !err.actionHint)) {
      ElMessage.error('创建入库单失败');
    }
  } finally {
    submitting.value = false;
  }
}

async function handleConfirm(row: ReceiveRow) {
  if (row.status !== 'DRAFT') return;
  // Rule 2: 入库确认前展示物料清单, 防止误操作
  const itemLines = (row.items ?? [])
    .map((item) => `  • ${item.materialName || item.materialTypeId}: ${item.receivedQuantity} ${item.unit || ''}`.trimEnd())
    .join('\n');
  const msgBody = itemLines
    ? `物料清单:\n${itemLines}\n\n确认后将生成物料批次，无法撤销。`
    : '确认后将生成物料批次，无法撤销。';
  try {
    await ElMessageBox.confirm(
      msgBody,
      `确认入库 — ${row.receiveNumber}${row.supplierName ? ' (' + row.supplierName + ')' : ''}`,
      { type: 'warning', confirmButtonText: '确认入库', cancelButtonText: '取消' }
    );
    await post(`/${factoryId.value}/purchase/receives/${row.id}/confirm`);
    ElMessage.success('已确认入库,物料批次已创建');
    loadData();
  } catch (e) {
    if (e === 'cancel') return;
    const err = e as { status?: number; actionHint?: string | null } | undefined;
    if (!err || (err.status !== 409 && !err.actionHint)) {
      ElMessage.error('确认入库失败');
    }
    if (err?.status === 409) loadData();
  }
}

async function handleDetail(row: ReceiveRow) {
  try {
    const res = await get<ReceiveRow>(`/${factoryId.value}/purchase/receives/${row.id}`);
    if (res.success && res.data) {
      detailData.value = res.data;
      detailVisible.value = true;
    }
  } catch { /* interceptor */ }
}

function statusTag(status: string): { type: string; label: string } {
  // PurchaseReceiveStatus.java real values — 'CANCELLED' never exists on this
  // entity; PENDING_QC/REJECTED were missing (fell to default: raw untranslated status).
  switch (status) {
    case 'DRAFT': return { type: 'info', label: '草稿' };
    case 'PENDING_QC': return { type: 'warning', label: '待质检' };
    case 'CONFIRMED': return { type: 'success', label: '已确认' };
    case 'REJECTED': return { type: 'danger', label: '已退回' };
    default: return { type: '', label: status };
  }
}

/**
 * 汇总收货数量 (六扇门 May 7 transcript MINOR gap close):
 * 客户想直接在列表看到这次收了多少。
 * 多 item 同单位 → 求和; 多单位 → 显式分组显示 (e.g. "120 kg + 30 L")。
 */
function totalReceivedQuantity(row: ReceiveRow): string {
  const items = row.items || [];
  if (items.length === 0) return '-';
  // group by unit, accumulate quantity
  const byUnit = new Map<string, number>();
  for (const it of items) {
    const qty = Number(it.receivedQuantity) || 0;
    const unit = (it.unit || '').trim() || '-';
    byUnit.set(unit, (byUnit.get(unit) || 0) + qty);
  }
  // format: strip trailing zeros, max 3 decimals
  const fmt = (n: number) => {
    const s = n.toFixed(3);
    return s.replace(/\.?0+$/, '');
  };
  return Array.from(byUnit.entries())
    .map(([unit, qty]) => `${fmt(qty)} ${unit}`)
    .join(' + ');
}

/**
 * Issue #775: F006 客户要求列表显示分次收货进度.
 * "本次收货 vs 累计已收 / 下单数" — 让用户直接看出"第一次 100 / 第二次 200 / 共 300"形态.
 *
 * 实现策略: 仅聚合**当前页已加载的 rows** 同一 PO 的 items 累加, 不发额外 HTTP.
 * 跨页/历史收货请进入采购单详情查看完整进度.
 *
 * 返回 {cumulative, ordered, percent}; ordered 在当前页 rows 中找不到时返回 null.
 */
interface CumulativeStats {
  cumulative: number;
  ordered: number | null;
  byUnit: Map<string, number>;
  unit: string;
}
function cumulativeForRow(row: ReceiveRow): CumulativeStats | null {
  if (!row.purchaseOrderId) return null;
  const poId = row.purchaseOrderId;
  // 同 PO 全部 rows (当前页内, 含本行)
  const sameOrderRows = tableData.value.filter(r => r.purchaseOrderId === poId);
  let cumulative = 0;
  let ordered = 0;
  let hasOrdered = false;
  const byUnit = new Map<string, number>();
  let firstUnit = '';
  for (const r of sameOrderRows) {
    for (const it of r.items || []) {
      const qty = Number(it.receivedQuantity) || 0;
      cumulative += qty;
      const u = (it.unit || '').trim();
      if (u) {
        byUnit.set(u, (byUnit.get(u) || 0) + qty);
        if (!firstUnit) firstUnit = u;
      }
      // PO 下单数 (orderedQuantity) — 若 backend 在 receive item 上回传 PO 计划量
      const planned = Number((it as Record<string, unknown>).orderedQuantity ?? (it as Record<string, unknown>).plannedQuantity ?? 0);
      if (planned > 0) {
        ordered += planned;
        hasOrdered = true;
      }
    }
  }
  return {
    cumulative,
    ordered: hasOrdered ? ordered : null,
    byUnit,
    unit: firstUnit,
  };
}

function cumulativeDisplay(row: ReceiveRow): string {
  const stats = cumulativeForRow(row);
  if (!stats) return '-';
  const fmt = (n: number) => {
    const s = n.toFixed(3);
    return s.replace(/\.?0+$/, '');
  };
  // 单位混合 → 列出全部
  if (stats.byUnit.size > 1) {
    return Array.from(stats.byUnit.entries())
      .map(([u, q]) => `${fmt(q)} ${u}`)
      .join(' + ');
  }
  const cumStr = `${fmt(stats.cumulative)} ${stats.unit || ''}`.trim();
  if (stats.ordered != null && stats.ordered > 0) {
    return `${cumStr} / 计划 ${fmt(stats.ordered)}`;
  }
  return cumStr;
}

function cumulativeProgress(row: ReceiveRow): number | null {
  const stats = cumulativeForRow(row);
  if (!stats || stats.ordered == null || stats.ordered <= 0) return null;
  return Math.min(100, Math.round((stats.cumulative / stats.ordered) * 100));
}

/**
 * 单元 G (F006 R-B3): 收货明细对话框 — 客户张权 "第一次收了多少第二次收了多少更直观".
 *
 * page-local cumulativeForRow() 只聚合当前页 rows, 跨页不准. 本对话框打开时拉两个后端权威 endpoint:
 *   - getCumulativeReceived: 跨页累计 vs 计划 (按 PO item receivedQuantity 累计, 准确)
 *   - getOrderReceiveSequence: 分次收货时序 (第N次 — 日期 — 数量)
 */
const seqDialogVisible = ref(false);
const seqLoading = ref(false);
const seqCumulative = ref<CumulativeReceived | null>(null);
const seqEntries = ref<ReceiveSequenceEntry[]>([]);
const seqOrderNumber = ref('');

const fmtQty = (n: number | null | undefined): string => {
  if (n == null) return '—';
  const s = Number(n).toFixed(3);
  return s.replace(/\.?0+$/, '');
};

async function openSequenceDialog(row: ReceiveRow) {
  if (!row.purchaseOrderId) {
    ElMessage.info('无单入库, 无累计收货明细');
    return;
  }
  const orderId = row.purchaseOrderId;
  seqOrderNumber.value = row.purchaseOrderNumber || orderId;
  seqCumulative.value = null;
  seqEntries.value = [];
  seqDialogVisible.value = true;
  seqLoading.value = true;
  try {
    const [cumRes, seqRes] = await Promise.all([
      getCumulativeReceived(factoryId.value, orderId),
      getOrderReceiveSequence(factoryId.value, orderId),
    ]);
    if (cumRes.success && cumRes.data) seqCumulative.value = cumRes.data;
    if (seqRes.success && Array.isArray(seqRes.data)) seqEntries.value = seqRes.data;
  } catch { /* interceptor toasts */ }
  finally { seqLoading.value = false; }
}

function formatDate(s: string): string {
  if (!s) return '';
  return new Date(s).toLocaleString('zh-CN', { hour12: false });
}

function handleSizeChange(val: number) { pagination.value.size = val; pagination.value.page = 1; loadData(); }
function handlePageChange(val: number) { pagination.value.page = val; loadData(); }

onMounted(() => { loadData(); loadOptions(); });
</script>

<template>
  <div class="page-wrapper" v-loading="loading">
    <ConceptDisambiguationAlert
      here-name="采购入库"
      here="把供应商发来的物料正式收货登记，更新原料库存（采购订单的执行环节）"
      other-name="采购管理 → 采购订单"
      other="向供应商下的订单（金额、付款条款）。入库是采购订单的物流接收环节"
      other-path="/procurement/orders"
    />
    <el-card shadow="never">
      <template #header>
        <div style="display:flex;justify-content:space-between;align-items:center">
          <span style="font-size:16px;font-weight:600">采购入库管理</span>
          <div style="display:flex;gap:8px">
            <el-button :icon="Refresh" @click="loadData">刷新</el-button>
            <el-button v-if="canWrite" type="success" :icon="ChatDotRound" @click="openAiCreate">AI 入库</el-button>
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="handleCreate">新建入库单</el-button>
          </div>
        </div>
      </template>

      <el-alert
        type="info" show-icon :closable="false"
        style="margin-bottom:12px"
        title="入库流程"
        description="新建入库单 → DRAFT 状态 → 点「确认入库」生成物料批次,自动更新库存。可关联采购订单或无单入库。"
      />

      <el-table :data="tableData" empty-text="暂无入库单" stripe border style="width:100%">
        <el-table-column prop="receiveNumber" label="入库单号" width="170" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status).type as any" size="small">{{ statusTag(row.status).label }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="采购订单" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.purchaseOrderNumber || row.purchaseOrderId || '— (无单入库)' }}</template>
        </el-table-column>
        <el-table-column label="供应商" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.supplierName || row.supplierId }}</template>
        </el-table-column>
        <el-table-column prop="receiveDate" label="入库日期" width="110" />
        <el-table-column prop="itemCount" label="物料行数" width="90" align="center">
          <template #default="{ row }">{{ row.itemCount ?? row.items?.length ?? '-' }}</template>
        </el-table-column>
        <!-- Issue #775: 本次 vs 累计/计划 — 分次收货进度可见. -->
        <el-table-column label="本次收货" min-width="120" align="right" show-overflow-tooltip>
          <template #default="{ row }">{{ totalReceivedQuantity(row) }}</template>
        </el-table-column>
        <el-table-column label="累计已收 / 计划" min-width="170" align="right" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ cumulativeDisplay(row) }}</span>
            <el-progress
              v-if="cumulativeProgress(row) != null"
              :percentage="cumulativeProgress(row) as number"
              :show-text="false"
              :stroke-width="4"
              :status="cumulativeProgress(row) === 100 ? 'success' : ''"
              style="margin-top:4px"
            />
          </template>
        </el-table-column>
        <el-table-column prop="createdByName" label="创建人" width="110" show-overflow-tooltip />
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="380" fixed="right">
          <template #default="{ row }">
            <el-button size="small" :icon="Document" @click="handleDetail(row)">详情</el-button>
            <!-- 单元 G (F006 R-B3): 跨页准确累计 + 分次收货时序 (仅关联采购单的行可看) -->
            <el-button
              v-if="row.purchaseOrderId"
              size="small" :icon="List"
              @click="openSequenceDialog(row)"
            >收货明细</el-button>
            <el-button
              v-if="canWrite && row.status === 'DRAFT'"
              type="success" size="small" :icon="Check"
              @click="handleConfirm(row)"
            >确认入库</el-button>
            <RowActionMenu
              :actions="rowActionsFor(row)"
              button-label="更多"
              @action-click="(id: string) => handleRowActionClick(id, row)"
              @ai-trigger="() => openAiForRow(row)"
            />
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="pagination.total > 0"
        :current-page="pagination.page"
        :page-size="pagination.size"
        :total="pagination.total"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        style="margin-top:16px;justify-content:flex-end"
        @size-change="handleSizeChange"
        @current-change="handlePageChange"
      />
    </el-card>

    <!-- Create dialog -->
    <el-dialog v-model="createVisible" title="新建入库单" width="900px" :close-on-click-modal="false">
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="100px">
        <el-form-item label="采购订单" prop="purchaseOrderId">
          <el-select
            v-model="form.purchaseOrderId" placeholder="(可选) 关联已批准的采购订单"
            clearable filterable style="width:100%"
            @change="handlePoChange"
          >
            <el-option
              v-for="po in purchaseOrderOptions" :key="po.id"
              :label="`${po.orderNumber} · ${po.supplierName || ''}`"
              :value="po.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="供应商" prop="supplierId">
          <el-select v-model="form.supplierId" placeholder="选择供应商" filterable style="width:100%">
            <el-option v-for="s in supplierOptions" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
          <UpstreamMissingHint
            v-if="supplierOptions.length === 0"
            description="本工厂暂无供应商，无法录入采购入库"
            target-module="procurement"
            require-write
            action-text="去创建供应商"
            contact-text="请联系采购或管理员先创建供应商"
            @action="goCreate('/procurement/suppliers')"
          />
        </el-form-item>
        <el-form-item label="入库日期" prop="receiveDate">
          <el-date-picker v-model="form.receiveDate" type="date" value-format="YYYY-MM-DD" style="width:200px" />
        </el-form-item>
        <!--
          客户张权反馈 (2026-07-02→07-04): 入库单没法选仓库, 之前硬编码默认物流仓(WH-LOG)。
          现默认 = 工厂配置的「采购入库默认仓」(PURCHASE_INBOUND_DEFAULT, 后端解析; 六扇门=原料仓),
          未配置回退物流仓。不选也不阻塞 — 后端 CreateReceiveRecordRequest.warehouseId 未传时同样
          兜底 resolvePurchaseInboundWh。选了 WIP/成品仓会被后端 422 拦(assertCanReceive)。
        -->
        <el-form-item label="入库仓库">
          <!-- Req 1 (2026-07-02): 只列能收原料的仓 (原料仓/盐化仓/物流仓) + 工厂配置的采购入库默认仓,
               不列成品仓/半成品仓/车间/研发库 — 见 rawReceivableWarehouses 定义处注释. -->
          <el-select v-model="form.warehouseId" placeholder="(可选,默认采购入库仓) 选择入库仓库" clearable filterable style="width:100%">
            <el-option
              v-for="w in rawReceivableWarehouses" :key="w.id"
              :label="`${w.name} (${w.code})`"
              :value="w.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="入库备注(可选)" />
        </el-form-item>

        <el-divider content-position="left">入库物料</el-divider>
        <div class="line-toolbar">
          <el-button size="small" :icon="Plus" @click="addItem">添加物料</el-button>
          <el-button size="small" @click="router.push('/warehouse/manufacturers')">厂商登记表</el-button>
        </div>
        <el-table :data="form.items" border>
          <!-- Req 2 (2026-07-02): 先选大类(原料/辅料/调料/包材)再选物料, 避免几十项混杂下拉难找. -->
          <el-table-column label="大类" width="100">
            <template #default="{ row }">
              <el-select v-model="row._bigCategory" size="small" style="width:100%">
                <el-option v-for="opt in BIG_CATEGORY_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="物料类型" min-width="200">
            <template #header><span class="req-star">*</span>物料类型</template>
            <template #default="{ row, $index }">
              <el-select
                v-model="row.materialTypeId" placeholder="选择物料"
                filterable size="small" style="width:100%"
                @change="(val: string) => handleMaterialChange($index, val)"
              >
                <!-- Req 3: materialOptions 已按创建时间倒序排好, 这里只按大类筛选, 顺序原样保留. -->
                <el-option
                  v-for="m in materialsForRow(row, materialOptions)" :key="m.id"
                  :label="`${m.name} (${m.code})`" :value="m.id"
                />
              </el-select>
            </template>
          </el-table-column>
          <!-- fool-proof-design Rule 1: PO 带入行显「待收量」(下单-已收), 仓管核对用, 不是硬上限
               (硬上限是含30%超收的 _maxAllowed, 体现在右侧到货数量列的 :max + 灰字提示). -->
          <el-table-column label="待收量" width="100" align="right">
            <template #default="{ row }">
              <span v-if="row._pendingQty != null">{{ Number(row._pendingQty).toFixed(3).replace(/\.?0+$/, '') }}</span>
              <span v-else class="price-masked">—</span>
            </template>
          </el-table-column>
          <el-table-column label="到货数量" width="160">
            <template #header><span class="req-star">*</span>到货数量</template>
            <template #default="{ row }">
              <!-- fool-proof-design Rule 1 (block-not-clamp fix): 故意不绑定 el-input-number 的
                   :max prop — ElementPlus InputNumber 在 handleInput 时会对每个按键自行 clamp
                   modelValue 到 max (input-number.js verifyValue), 值在用户看到红字/disable 前
                   就已被静默纠正回上限, 令下面 isOverLimit/hasOverLimitItem 判断永远读不到超限值
                   (仓管以为录入了 140, 实际早被吞成 130, 且全程无红字无提示). 改为只用 :min 硬下限
                   (下限纠正无歧义, 非本反模式), 超限判定完全交给下方 isOverLimit computed, 保证用户
                   输入的原始数值可见 + 真触发红框/红字/禁用提交. -->
              <el-input-number
                v-model="row.receivedQuantity"
                :min="0.001" :precision="3" :controls="false"
                size="small" style="width:100%"
                :class="{ 'over-limit-input': isOverLimit(row) }"
              />
              <div v-if="row._maxAllowed != null" class="over-limit-hint" :class="{ 'over-limit-hint--danger': isOverLimit(row) }">
                可收上限 {{ Number(row._maxAllowed).toFixed(3).replace(/\.?0+$/, '') }}{{ row.unit || '' }} (含30%超收)
              </div>
            </template>
          </el-table-column>
          <el-table-column label="单位" width="80">
            <template #header><span class="req-star">*</span>单位</template>
            <template #default="{ row }">
              <el-input v-model="row.unit" size="small" />
            </template>
          </el-table-column>
          <el-table-column v-if="canViewPrice" label="单价" width="120">
            <template #default="{ row }">
              <el-input-number v-model="row.unitPrice" :min="0" :precision="2" :controls="false" size="small" style="width:100%" />
            </template>
          </el-table-column>
          <el-table-column label="质检结果" width="140">
            <template #default="{ row }">
              <el-select v-model="row.qcResult" size="small" placeholder="(可选)" clearable>
                <el-option label="合格" value="PASS" />
                <el-option label="不合格" value="FAIL" />
                <el-option label="待复检" value="RECHECK" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="厂号" width="190">
            <template #default="{ row, $index }">
              <el-select
                v-model="row.factoryNumber"
                placeholder="选择或输入厂号(选填)"
                filterable
                clearable
                allow-create
                default-first-option
                size="small"
                style="width:100%"
                @change="(val: string) => handleManufacturerChange($index, val)"
              >
                <el-option
                  v-for="manufacturer in manufacturerOptions"
                  :key="manufacturer.id"
                  :label="`${manufacturer.code} · ${manufacturer.name}`"
                  :value="manufacturer.code"
                />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="产地" width="150">
            <template #default="{ row }">
              <el-input v-model="row.originPlace" size="small" placeholder="产地(选填)" maxlength="200" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="80" align="center">
            <template #default="{ $index }">
              <el-button type="danger" link size="small" @click="removeItem($index)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button
          type="primary" :loading="submitting" :disabled="hasOverLimitItem"
          @click="handleSubmit"
        >创建</el-button>
      </template>
    </el-dialog>

    <!-- Detail dialog -->
    <el-dialog v-model="detailVisible" :title="`入库单详情 — ${detailData?.receiveNumber || ''}`" width="800px">
      <el-descriptions v-if="detailData" :column="2" border>
        <el-descriptions-item label="入库单号">{{ detailData.receiveNumber }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="statusTag(detailData.status).type as any" size="small">{{ statusTag(detailData.status).label }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="采购订单">{{ detailData.purchaseOrderNumber || detailData.purchaseOrderId || '— (无单)' }}</el-descriptions-item>
        <el-descriptions-item label="供应商">{{ detailData.supplierName || detailData.supplierId }}</el-descriptions-item>
        <el-descriptions-item label="入库日期">{{ detailData.receiveDate }}</el-descriptions-item>
        <el-descriptions-item label="创建人">{{ detailData.createdByName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="备注" :span="2">{{ detailData.remark || '-' }}</el-descriptions-item>
      </el-descriptions>
      <el-divider content-position="left">入库物料</el-divider>
      <el-table v-if="detailData?.items?.length" :data="detailData.items" border size="small">
        <el-table-column prop="materialName" label="物料名称" min-width="160" />
        <el-table-column prop="receivedQuantity" label="到货数量" width="110" align="right" />
        <el-table-column prop="unit" label="单位" width="80" align="center" />
        <el-table-column v-if="canViewPrice" label="单价" width="100" align="right">
          <!--
            RBAC defense-in-depth (PR #415 Option B 2026-05-12):
            backend PriceFieldResponseAdvice strips unitPrice → null for roles
            lacking procurement:price:view (warehouse_mgr / inspector / operator).
            v-if guard avoids rendering "0" or empty when stripped.
          -->
          <template #default="{ row }">
            <span v-if="row.unitPrice != null">{{ row.unitPrice }}</span>
            <span v-else class="price-masked">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="qcResult" label="质检" width="100" align="center" />
        <el-table-column prop="factoryNumber" label="厂号" width="110" show-overflow-tooltip>
          <template #default="{ row }">{{ row.factoryNumber || '-' }}</template>
        </el-table-column>
        <el-table-column prop="originPlace" label="产地" width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.originPlace || '-' }}</template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="120" show-overflow-tooltip />
      </el-table>
      <el-empty v-else description="无物料明细" />

      <!--
        Issue #794: 拍照附件 (六扇门 May 7 part 2 L177-180).
        客户原话: "拍照也可以留个单谱吧, 就是你留个附件类似一个拍照然后一个附件吗也可以的呀"
        Reuses C-ATT-1 通用附件组件 (entityType=PURCHASE_RECEIPT, Attachment.java:115 enum 已支持).
      -->
      <template v-if="detailData?.id">
        <el-divider content-position="left">收货照片 / 附件</el-divider>
        <AttachmentList
          entity-type="PURCHASE_RECEIPT"
          :entity-id="String(detailData.id)"
          :factory-id="factoryId"
          :refresh-key="attachmentRefreshKey"
          empty-text="暂无收货照片"
        />
        <div v-if="canWrite" style="margin-top: 12px">
          <AttachmentUploadButton
            entity-type="PURCHASE_RECEIPT"
            :entity-id="String(detailData.id)"
            :factory-id="factoryId"
            business-tag="RECEIVE_PHOTO"
            file-category="PHOTO"
            accept="image/*"
            button-label="拍照 / 上传照片"
            @uploaded="attachmentRefreshKey++"
          />
        </div>
      </template>

      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <el-button
          v-if="canWrite && detailData?.status === 'DRAFT'"
          type="success" :icon="Check"
          @click="() => detailData && handleConfirm(detailData)"
        >确认入库</el-button>
      </template>
    </el-dialog>

    <!-- 单元 G (F006 R-B3): 收货明细对话框 — 跨页准确累计 + 分次收货时序 -->
    <el-dialog
      v-model="seqDialogVisible"
      :title="`收货明细 — ${seqOrderNumber}`"
      width="720px"
    >
      <div v-loading="seqLoading">
        <!-- 跨页准确累计 vs 计划 (后端 cumulative-received endpoint) -->
        <el-descriptions v-if="seqCumulative" :column="2" border size="small">
          <el-descriptions-item label="采购单号">{{ seqCumulative.orderNumber || '—' }}</el-descriptions-item>
          <el-descriptions-item label="计划总量">{{ fmtQty(seqCumulative.plannedTotal) }}</el-descriptions-item>
          <el-descriptions-item label="累计已收 (跨页准确)">
            <strong>{{ fmtQty(seqCumulative.cumulativeReceived) }}</strong>
          </el-descriptions-item>
          <el-descriptions-item label="待收">
            {{ fmtQty((seqCumulative.plannedTotal ?? 0) - (seqCumulative.cumulativeReceived ?? 0)) }}
          </el-descriptions-item>
        </el-descriptions>

        <el-divider content-position="left">分次收货时序</el-divider>
        <el-table
          v-if="seqEntries.length"
          :data="seqEntries" border size="small" style="width:100%"
        >
          <el-table-column label="第几次" width="80" align="center">
            <template #default="{ row }">第 {{ row.seq }} 次</template>
          </el-table-column>
          <el-table-column prop="receiveNumber" label="收货单号" min-width="170" show-overflow-tooltip />
          <el-table-column prop="receiveDate" label="日期" width="120" />
          <el-table-column label="数量" min-width="140" align="right">
            <template #default="{ row }">
              <span>{{ fmtQty(row.totalQuantity) }}</span>
              <span v-if="row.items && row.items[0]?.unit" style="margin-left:4px;color:#909399">
                {{ row.items[0].unit }}
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="createdByName" label="收货人" width="100" show-overflow-tooltip>
            <template #default="{ row }">{{ row.createdByName || '—' }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-else-if="!seqLoading" description="暂无收货记录" :image-size="80" />
      </div>
      <template #footer>
        <el-button @click="seqDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- AI 入库录入抽屉 (Day 9, Issue #780.2) -->
    <AiEntryDrawer
      v-model="aiEntryVisible"
      :config="WH_INBOUND_CONFIG"
      @fill-form="handleAiFill"
    />
  </div>
</template>

<style scoped>
.page-wrapper {
  padding: 16px;
}

.line-toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}

.req-star { color: #f56c6c; margin-right: 2px; }

/* fool-proof-design Rule 1: 到货数量灰字上限提示 (正常) / 超限变红 (与 :max 前置拦截 + 提交禁用三重防御) */
.over-limit-hint {
  font-size: 11px;
  color: #909399;
  line-height: 1.4;
  margin-top: 2px;
  white-space: normal;
}
.over-limit-hint--danger {
  color: #f56c6c;
}
:deep(.over-limit-input .el-input__wrapper) {
  box-shadow: 0 0 0 1px #f56c6c inset;
}
</style>
