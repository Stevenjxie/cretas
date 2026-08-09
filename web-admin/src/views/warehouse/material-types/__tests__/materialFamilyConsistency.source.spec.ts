import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(
  resolve(process.cwd(), 'src/views/warehouse/material-types/list.vue'),
  'utf8',
);

describe('material type family source contract', () => {
  it('uses material code L1 families as the only category option source', () => {
    expect(source).toContain('const materialFamilyOptions = computed');
    expect(source).toContain('segmentL1Options.value.map');
    // 类别选项**不许**再去拉 enum 接口(那是被 L1 类族取代的旧来源);
    // 无字典工厂的兜底走编译期常量, 不是运行期字典。
    expect(source).not.toContain('system-config/enums/MATERIAL_CATEGORY');

    const sharedOptionLoops = source.match(/v-for="opt in materialFamilyOptions"/g) ?? [];
    expect(sharedOptionLoops).toHaveLength(2);
    expect(source).not.toContain('<el-option label="原料" value="原料" />');
  });

  it('loads L1 families with the page and keeps category and L1 synchronized', () => {
    expect(source).toContain('await Promise.all([loadDictionaries(), loadSegmentTree()])');
    expect(source).toContain('syncMaterialFamilyFromCategory');
    expect(source).toContain('syncMaterialFamilyFromSegment');
  });

  it('canonicalizes legacy smart suggestions before writing a new category', () => {
    expect(source).toContain('resolveMaterialFamily(d.category)');
    expect(source).toContain("bucket === '调料' ? '辅料' : bucket");
  });

  it('requires a complete L1-L3 selection for new and legacy-code material types', () => {
    // 2026-08-07: 强制选 L1/L2/L3 只对**配了分段字典**的工厂成立 —— 字典空的工厂
    // 一个分类都选不出来, 再强制就是把人堵死(见 hasSegmentDictionary 那组断言)。
    expect(source).toContain('const showSegmentEditor = computed(() => hasSegmentDictionary.value');
    expect(source).toContain('&& (!editingId.value || editingNeedsSegmentRepair.value));');
    expect(source).toContain("if (showSegmentEditor.value && (!segmentL1.value || !segmentL2.value || !segmentL3.value))");
    expect(source).toContain('物料分类与业务编码（必填）');
    expect(source).not.toContain('>16位编码级联（必填）<');
    expect(source).not.toContain('16位编码级联（可选）');
  });

  it('filters material types by the selected L1, L2 or L3 code prefix', () => {
    expect(source).toContain("const filterSegmentL1 = ref('')");
    expect(source).toContain("const filterSegmentL2 = ref('')");
    expect(source).toContain("const filterSegmentL3 = ref('')");
    expect(source).toContain('const selectedSegmentPrefix = computed');
    expect(source).toContain('codePrefix: selectedSegmentPrefix.value || undefined');
    expect(source).toContain('keyword: searchKeyword.value.trim() || undefined');
    expect(source).not.toContain('FETCH_ALL_SIZE = 2000');
    expect(source).toContain('v-model="filterSegmentL1"');
    expect(source).toContain('v-model="filterSegmentL2"');
    expect(source).toContain('v-model="filterSegmentL3"');
  });

  it('never fabricates a 16-digit preview when the preview API fails', () => {
    expect(source).not.toContain('segmentCodePreview.value = `${segmentL1.value}${segmentL2.value}${segmentL3.value}...`');
    expect(source).not.toContain('segmentCodePreview.value = `${segmentL1.value}-${segmentL2.value}-${segmentL3.value}`');
  });

  it('previews and saves through the same material code contract', () => {
    expect(source).toContain('raw-material-types/preview-code');
    expect(source).toContain('segmentCode: segmentL3.value');
    expect(source).toContain('businessCodePreview.value = res.data.businessCode');
    expect(source).toContain('历史兼容编码（16位）');
    expect(source).toContain('if (!editingId.value && hasSegmentDictionary.value && !(await generateSP8Code(true)))');
    expect(source).toContain('不会按分类名称猜测或覆盖历史前缀');
  });

  it('uses the short business code as the primary user-visible material identity', () => {
    expect(source).toContain('function materialDisplayCode(row: TableRow)');
    expect(source).toContain('row.displayCode || row.businessCode || row.code');
    expect(source).toContain('<el-table-column label="业务编码"');
    expect(source).toContain('{{ materialDisplayCode(row) }}');
    expect(source).toContain('v-if="!materialHasBusinessCode(row)"');
    expect(source).toContain('历史编码</el-tag>');
    expect(source).toContain('历史兼容编码：${row.code}');
    expect(source).not.toContain('<el-table-column prop="code" label="原料编码"');
    expect(source).toContain('placeholder="搜索原料名称 / 业务编码 / 历史编码"');
  });

  it('presents classification names before their internal numeric category codes', () => {
    expect(source).toContain('function formatSegmentOptionLabel');
    // 2026-08-07: 改从局部变量 code 取 —— 无字典工厂的选项没有分类码, 空括号会误导
    expect(source).toContain('（分类码 ${code}）');
    expect(source).toContain(':label="formatSegmentOptionLabel(opt)"');
    expect(source).not.toContain(':label="`${opt.segmentCode} — ${opt.segmentLabel}`"');
  });

  it('shows the business code in edit mode and keeps a clear historical fallback', () => {
    expect(source).toContain('editingDisplayCode.value = materialDisplayCode(row)');
    expect(source).toContain('<el-form-item v-if="editingId" label="业务编码">');
    expect(source).toContain(':model-value="editingDisplayCode"');
    expect(source).toContain('该历史记录尚未分配业务编码，当前回退显示原16位编码');
    expect(source).not.toContain('<el-input v-model="form.code" disabled');
  });

  it('does not add a second error toast after the request interceptor handled the failure', () => {
    expect(source).toContain("handleCatchError(e, '原料类型保存失败，请稍后重试')");
    expect(source).not.toContain('if (e instanceof Error) ElMessage.error(e.message)');
  });

  it('uses the requested material-family form defaults and visibility contracts', () => {
    expect(source).toContain("taxRate: 'TAX_13'");
    expect(source).toContain('label="入库计量单位"');
    expect(source).toContain('新建默认 kg（公斤）');
    expect(source).toContain('v-if="!isPackagingMaterial" label="储存类型"');
    expect(source).toContain('<template v-if="canViewPrice">');
    expect(source).toContain('<template v-if="isPackagingMaterial">');
    expect(source).toContain('包材专属字段（选填）');
    // 2026-08-06: 包装换算改为**可选**(抄码/不定重原料没有固定包装, 强制填只会逼出假换算),
    // 所以这里不再带 required —— 详见 materialPackagingConversion.source.spec.ts
    expect(source).toContain('<el-form-item v-if="form.unit" label="包装换算">');
    expect(source).not.toContain('采购与库存单位换算（可选）');
    expect(source).toContain('v-for="(rule, index) in packagingRules"');
    expect(source).toContain('packagingSpecs: submittedPackagingRules');
    expect(source).not.toContain('包装层级（包材专属，可选）');
  });

  it('matches historical L3 under the selected L1/L2 and reuses the real dictionary create endpoint', () => {
    expect(source).toContain('params: { page: 1, size: 20, codePrefix: l2, keyword: normalizedName }');
    expect(source).toContain('label="＋ 新建共享 L3 分类"');
    expect(source).toContain('v-if="canManageClassification"');
    expect(source).toContain('系统不会复制当前原料名称');
    expect(source).toContain('`/${factoryId.value}/material-segments`');
    expect(source).toContain('level: 3');
    expect(source).toContain('parentCode: segmentL2.value');
    expect(source).toContain('创建并选中');
    expect(source).toContain(':model-value="nextL3Code"');
    expect(source).toContain('已直接选中');
    expect(source).not.toContain('createL3Form.suffix');
    expect(source).not.toContain('L3 四位编码');
  });

  /**
   * 🔴 2026-08-06 客户事故: 系统编码原来在前端对**活着的**兄弟节点取 max+1。
   * 分类是软删除、编码软删后仍被占用, 于是把一层删干净后算出的编码正是被占的那个 →
   * INSERT 撞 `uk_mcs_factory_segment` → 用户收到「已存在同名分类, 请改个名字」,
   * 而改名字永远修不好编码冲突。
   *
   * 前端看不到软删行, 所以分配只能在服务端做。这里锁住的是**编码来源**,
   * 不是某一种写法 —— 原断言 `const nextL3Code = computed` 锁的是后者。
   */
  it('系统编码必须向服务端取, 不能在前端 max+1', () => {
    expect(source).toContain('material-segments/next-code');
    expect(source).toContain('refreshNextL3Code');
    // ⚠️ get(url, config) 的第二个参数是 axios config: query 摊平写后端一个都收不到
    // (2026-08-06 实测报「缺少必要参数: level」)。同文件其它调用也都是 params: {...}。
    expect(source).toContain('{ params: { level: 3, parentCode: segmentL2.value } }');
    // 前端自算的痕迹一个都不能留
    expect(source).not.toContain('nextL3Suffix');
    expect(source).not.toContain('padStart(4');
  });

  /**
   * 🔴 2026-08-07 客户事故 (六膳门): 7-17 上线的「新建物料必须选 L1/L2/L3」把**没有配分段
   * 字典**的工厂堵死了 —— 类别下拉由字典 L1 派生, 字典空则一个选项都没有, 而表单里也没有
   * 任何输入框能填料号(code 全靠 generateSP8Code 生成)。客户于是被推离自己的 WL/YL/BC 料号,
   * 长出 14 个 16 位分类码(`LegacyClassificationCode` —— 它在代码里的正式名字就是 legacy)。
   *
   * 这里锁的是**判据本身**: 有没有字典看已取回的 segmentTree, 不看租户名、不新增接口。
   */
  it('无分段字典的工厂改走「用户自填料号 + 平台类别枚举」, 判据不写租户名', () => {
    expect(source).toContain('const hasSegmentDictionary = computed(() => segmentL1Options.value.length > 0)');
    expect(source).toContain('MATERIAL_CATEGORY_ENUM_VALUES');
    expect(source).toContain("from '@/utils/materialCategory'");
    expect(source).toContain('v-else-if="!hasSegmentDictionary" label="料号" required');
    expect(source).toContain('v-model="form.code"');
    expect(source).toContain('请填写料号（本工厂未配置物料分段字典，料号由你自己维护）');
    // ⛔ 判据只能来自数据形状 —— 写死租户 id 就等于给下一个同样没配字典的工厂留同一个坑
    expect(source).not.toContain('LIUSHANMEN');
  });

  /**
   * 🔴 清掉某工厂的分段字典 = 让它从「有字典」切成「无字典」, 而字典模式下写进去的
   * category 是 L1 类族的名字(六膳门那 14 个换过料号的物料就是 `原料`), 不在平台枚举里。
   * 只给枚举五项的话, 这些历史取值在下拉里一个都对不上 —— 编辑时类别框空白、列表按它
   * 筛不出来, 而用户什么都没做错。选项必须并上**存量在用**的取值。
   */
  it('无字典模式的类别选项要并上存量在用的历史取值, 不能只给平台枚举', () => {
    expect(source).toContain('const seenCategories = ref<string[]>([])');
    expect(source).toContain('function rememberCategories');
    // 跨页累积: 翻页不能把上一页见过的取值弄丢
    expect(source).toContain('rememberCategories(tableData.value)');
    // 正在编辑的那条即使不在已加载列表里, 也要能显示自己的类别
    expect(source).toContain("const current = (form.value?.category || '').trim()");
  });

  /**
   * 🔴 2026-08-07 真机验证抓到的缺陷: 「料号」输入框有了、后端消费 dto.getCode() 的分支
   * 也有了, 但提交前 `delete materialPayload.code` 是**无条件**的 —— 两头各自都对,
   * 中间被掐断。实测 POST body 里一个 code 字段都没有, 后端如实回 400「请填写物料料号」,
   * 而 el-message 3 秒自动消失, 页面上只表现为「点保存没反应」。
   *
   * 这条断言锁的是「无字典新建时 code 必须进 payload」, 不是某种写法。
   */
  it('无字典新建时不能把用户填的料号从 payload 里删掉', () => {
    expect(source).toContain('if (editingId.value || hasSegmentDictionary.value) {');
    expect(source).toContain('delete materialPayload.code;');
    // 删除必须在那个 if 里面 —— 用缩进锁住层级, 无条件版本是 4 空格缩进
    expect(source).toContain('      delete materialPayload.code;');
  });

  it('没有分类码时类别选项不拼空括号', () => {
    expect(source).toContain("if (!code) return label || '未命名分类';");
  });
});
