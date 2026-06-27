<template>
  <div class="page-wrapper">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">配方管理</span>
            <span class="data-count">共 {{ pagination.total }} 条</span>
          </div>
          <div class="header-right">
 <el-button type="info" plain @click="handleAiAnalyze"> AI 分析</el-button>
 <el-button v-if="canWrite" type="primary" plain @click="aiDraftDialog = true"> AI 录配方</el-button>
 <el-button v-if="canWrite" type="primary" @click="openAiBatchDraftDialog"> AI 批量录配方 <el-badge v-if="unmatchedCount > 0" :value="unmatchedCount" type="warning" /></el-button>
 <el-button v-if="canWrite" type="warning" plain @click="batchImportDialog = true"> 批量导入</el-button>
 <el-button v-if="canWrite" type="success" plain @click="openAliasPanel"> 菜名对齐 <el-badge v-if="unmatchedCount > 0" :value="unmatchedCount" type="warning" /></el-button>
            <el-button :icon="Download" @click="handleExport">导出</el-button>
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="handleCreate">新增配方</el-button>
          </div>
        </div>
      </template>

      <!-- 统计卡片 -->
      <el-row :gutter="16" class="stat-row" v-if="statsLoaded">
        <el-col :xs="12" :sm="6">
          <div class="stat-item">
            <span class="stat-label">配方总数</span>
            <span class="stat-value">{{ statsData.totalRecipes ?? pagination.total }}</span>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div class="stat-item">
            <span class="stat-label">涉及菜品</span>
            <span class="stat-value">{{ statsData.dishCount ?? 0 }}</span>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div class="stat-item">
            <span class="stat-label">有配方菜品</span>
            <span class="stat-value" style="color: var(--el-color-success)">{{ statsData.activeRecipes ?? 0 }}</span>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div class="stat-item">
            <span class="stat-label">列表总数</span>
            <span class="stat-value">{{ pagination.total }}</span>
          </div>
        </el-col>
      </el-row>

      <!-- Apr 24 P1 analytics strip: ranking by dish (food count + standard qty sum) -->
      <AnalyticsStrip
        :rows="tableData"
        date-field="createdAt"
        value-field="standardQuantity"
        category-field="productTypeId"
        :category-name-map="productNameMap"
        trend-title="配方录入趋势"
        ranking-title="菜品食材用量 Top 10"
        value-unit="kg"
        :top-n="10"
      />

      <div class="search-bar" role="search" aria-label="配方筛选">
        <el-input v-model="filterKeyword" placeholder="搜索食材名称" clearable style="width: 180px" :prefix-icon="Search" @keyup.enter="handleSearch" />
        <el-select v-model="filterDish" placeholder="按菜品筛选" filterable clearable style="width: 180px" @change="handleSearch">
          <el-option v-for="pt in productTypes" :key="pt.id" :label="pt.name" :value="pt.id" />
        </el-select>
        <el-select v-model="filterActive" placeholder="状态" clearable style="width: 120px" @change="handleSearch">
          <el-option label="启用" :value="true" />
          <el-option label="停用" :value="false" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
        <el-button :icon="Refresh" @click="handleRefresh">重置</el-button>
      </div>

      <el-table :data="tableData" row-key="id" v-loading="loading" stripe border style="width: 100%" empty-text="暂无配方数据，点击「新增配方」添加" aria-label="配方列表">
        <el-table-column prop="productTypeId" label="菜品" width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ productNameMap[row.productTypeId] || row.productTypeId }}</template>
        </el-table-column>
        <el-table-column prop="rawMaterialTypeId" label="食材" width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ materialNameMap[row.rawMaterialTypeId] || row.rawMaterialTypeId }}</template>
        </el-table-column>
        <el-table-column label="标准用量" width="120" align="right">
          <template #default="{ row }">
            {{ row.standardQuantity }} {{ row.unit }}
          </template>
        </el-table-column>
        <el-table-column label="净料率" width="100" align="center" class-name="hidden-sm-col">
          <template #default="{ row }">
            {{ row.netYieldRate ? (row.netYieldRate * 100).toFixed(1) + '%' : '-' }}
          </template>
        </el-table-column>
        <el-table-column label="主料/辅料" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.isMainIngredient ? '' : 'info'" size="small">
              {{ row.isMainIngredient ? '主料' : '辅料' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.isActive ? 'success' : 'danger'" size="small">
              {{ row.isActive ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="notes" label="备注" show-overflow-tooltip :formatter="emptyCell" />
        <el-table-column label="操作" width="180" fixed="right" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="showDetail(row)">查看</el-button>
            <el-button type="success" link size="small" @click="openCostCard(row.productTypeId)">成本卡</el-button>
            <el-button v-if="canWrite" type="primary" link size="small" @click="handleEdit(row)">编辑</el-button>
            <el-button v-if="canWrite && row.isActive" type="warning" link size="small" @click="handleDeactivate(row)">停用</el-button>
            <el-button v-if="canWrite && !row.isActive" type="success" link size="small" @click="handleActivate(row)">启用</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.size"
          :page-sizes="[10, 20, 50]"
          :total="pagination.total"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="loadData"
          @size-change="() => { pagination.page = 1; loadData(); }"
        />
      </div>
    </el-card>

    <!-- 新增/编辑对话框 -->
    <el-dialog v-model="dialogVisible" :title="dialogForm.id ? '编辑配方' : '新增配方'" width="500px" :close-on-click-modal="false" destroy-on-close>
      <el-form ref="formRef" :model="dialogForm" :rules="formRules" label-width="100px">
        <el-form-item label="菜品" prop="productTypeId">
          <el-select v-model="dialogForm.productTypeId" filterable placeholder="选择菜品" style="width: 100%">
            <el-option v-for="pt in productTypes" :key="pt.id" :label="pt.name" :value="pt.id" />
          </el-select>
          <UpstreamMissingHint v-if="productTypes.length === 0" description="本店暂无菜品，无法配置配方" target-module="system" require-write action-text="去创建菜品" contact-text="请联系管理员先创建菜品" @action="goCreate('/system/products')" />
        </el-form-item>
        <el-form-item label="食材" prop="rawMaterialTypeId">
          <el-select v-model="dialogForm.rawMaterialTypeId" filterable placeholder="选择食材" style="width: 100%">
            <el-option v-for="mt in materialTypes" :key="mt.id" :label="mt.name" :value="mt.id" />
          </el-select>
          <UpstreamMissingHint v-if="materialTypes.length === 0" description="本店暂无食材，无法配置配方" target-module="warehouse" require-write action-text="去创建物料类型" contact-text="请联系仓库或管理员先创建物料类型" @action="goCreate('/warehouse/material-types')" />
        </el-form-item>
        <el-form-item label="标准用量" prop="standardQuantity">
          <el-input-number v-model="dialogForm.standardQuantity" :precision="4" :step="0.1" :min="0" />
        </el-form-item>
        <el-form-item label="单位">
          <el-input v-model="dialogForm.unit" placeholder="kg / L / 个" style="width: 120px" />
        </el-form-item>
        <el-form-item label="净料率">
          <el-input-number v-model="dialogForm.netYieldRate" :precision="4" :step="0.05" :min="0.01" :max="1" />
        </el-form-item>
        <el-form-item label="主料">
          <el-switch v-model="dialogForm.isMainIngredient" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="dialogForm.notes" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="submitting" @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitForm">确定</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailVisible" title="配方详情" size="400px">
      <el-descriptions :column="1" border v-if="detailData">
        <el-descriptions-item label="菜品">{{ productNameMap[detailData.productTypeId] || detailData.productTypeId }}</el-descriptions-item>
        <el-descriptions-item label="食材">{{ materialNameMap[detailData.rawMaterialTypeId] || detailData.rawMaterialTypeId }}</el-descriptions-item>
        <el-descriptions-item label="标准用量">{{ detailData.standardQuantity }} {{ detailData.unit }}</el-descriptions-item>
        <el-descriptions-item label="净料率">{{ detailData.netYieldRate ? (detailData.netYieldRate * 100).toFixed(1) + '%' : '-' }}</el-descriptions-item>
        <el-descriptions-item label="主料/辅料">{{ detailData.isMainIngredient ? '主料' : '辅料' }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ detailData.isActive ? '启用' : '停用' }}</el-descriptions-item>
        <el-descriptions-item label="备注">{{ detailData.notes || '-' }}</el-descriptions-item>
      </el-descriptions>
    </el-drawer>

    <!-- P0-1: 批量导入配方 Dialog -->
 <el-dialog v-model="batchImportDialog" title=" 批量导入配方" width="640px">
      <div style="line-height: 1.8">
        <p><b>1. 下载模板</b> — Excel/CSV 格式, 6 列: 菜品名称 / 食材名称 / 用量 / 单位 / 食材单价 / 是否主料</p>
        <el-button type="primary" plain size="small" @click="downloadTemplate">下载 CSV 模板</el-button>
        <el-divider />
        <p><b>2. 填写数据</b> — 一行 = 一个菜品的一种食材. 同一菜多食材填多行. 新菜品/食材自动创建.</p>
        <p><b>3. 上传</b></p>
        <el-upload
          action=""
          :auto-upload="false"
          :limit="1"
          :on-change="onFileSelect"
          accept=".xlsx,.xls,.csv"
          drag
        >
          <div class="el-upload__text">拖拽文件到此处, 或<em>点击选择</em></div>
          <template #tip>
            <div class="el-upload__tip">仅支持 .xlsx / .xls / .csv 格式</div>
          </template>
        </el-upload>
        <el-button v-if="selectedFile" type="primary" :loading="importing" @click="submitImport" style="margin-top: 12px">
          确认导入 {{ selectedFile.name }}
        </el-button>
        <el-alert v-if="importResult" :type="importResult.success ? 'success' : 'error'" :closable="false" style="margin-top: 12px">
          <template #title>
            <div v-if="importResult.success">
 导入完成: 新增菜品 {{ importResult.data.dishesCreated }} / 新增食材 {{ importResult.data.ingredientsCreated }} / 新增配方 {{ importResult.data.recipesCreated }}
              <span v-if="importResult.data.errorCount > 0" style="color: var(--el-color-warning)"> · {{ importResult.data.errorCount }} 条行错误 (见下方)</span>
            </div>
            <div v-else>导入失败: {{ importResult.message }}</div>
          </template>
          <div v-if="importResult.data?.errors?.length" style="font-size: 12px; max-height: 120px; overflow-y: auto">
            <div v-for="(err, i) in importResult.data.errors" :key="i">• {{ err }}</div>
          </div>
        </el-alert>
      </div>
    </el-dialog>

    <!-- P0-2: 菜名对齐 (未匹配菜品面板) -->
 <el-dialog v-model="aliasDialog" title=" 菜名对齐 — POS 菜名 → 配方菜品" width="900px">
      <div v-if="unmatchedData" style="margin-bottom: 12px">
        <el-alert type="info" :closable="false">
          <template #title>
            POS 上传中共 {{ unmatchedData.totalPosDishes }} 个菜名, 其中 <b>{{ unmatchedData.unmatchedCount }}</b> 个未绑定配方菜品 (占 {{ (unmatchedData.unmatchedRevenueRatio * 100).toFixed(1) }}% 营收).
            按下方营收排序, 优先绑定高营收菜品.
          </template>
        </el-alert>
      </div>
      <el-table v-if="unmatchedData" :data="unmatchedData.dishes" max-height="400" border @selection-change="onSelectionChange">
        <el-table-column type="selection" width="45" />
        <el-table-column prop="name" label="POS 菜名" min-width="200" show-overflow-tooltip />
        <el-table-column v-if="canViewPrice" prop="revenue" label="营收" width="120" align="right">
          <template #default="{ row }">¥{{ row.revenue.toLocaleString('zh-CN', { maximumFractionDigits: 0 }) }}</template>
        </el-table-column>
        <el-table-column prop="qty" label="销量" width="90" align="right" />
        <el-table-column prop="bills" label="账单数" width="90" align="right" />
        <el-table-column label="绑定到现有菜品" width="260">
          <template #default="{ row }">
            <el-select
              v-model="row._bindTo"
              placeholder="搜索已录配方的菜品"
              filterable
              size="small"
              style="width: 220px"
              clearable
              @change="(val: string) => val && bindAlias(row.name, val)"
            >
              <el-option v-for="p in availableProducts" :key="p.id" :label="p.name" :value="p.id" />
            </el-select>
          </template>
        </el-table-column>
      </el-table>
      <div style="margin-top: 12px; display: flex; gap: 10px; align-items: center; flex-wrap: wrap">
        <el-button
          v-if="selectedUnmatched.length > 0"
          type="warning"
          size="small"
          @click="markAsNoise"
        >
          标记为"非菜品"(噪音) ({{ selectedUnmatched.length }})
        </el-button>
        <span style="font-size: 12px; color: #909399; flex: 1">
 绑定完成后点 <el-link type="primary" href="/restaurant/analytics/gross-margin" underline="never">毛利分析页 立即同步</el-link> 刷新覆盖率.
          "非菜品"的条目 (如 打包盒/餐具/广告词) 会从覆盖率分母中剔除.
        </span>
      </div>
    </el-dialog>

    <!-- P2-7 批量版: AI 批量录配方 (覆盖 top N 未录菜品) -->
 <el-dialog v-model="aiBatchDraftDialog" title=" AI 批量录配方 — 按营收从高到低录 Top N" width="1100px" top="5vh">
      <div v-if="!batchDraftResults">
        <el-alert type="info" :closable="false" style="margin-bottom: 12px">
          <template #title>
            智能批量配方生成: AI 按营收排序读取未录配方菜品, 并发调用 LLM (10 并发), 约 1 分钟生成 top 100 配方草稿. 客户再逐个或一键采纳.
          </template>
        </el-alert>
        <div style="display: flex; gap: 10px; align-items: center; margin-bottom: 14px">
          <span>目标营收覆盖:</span>
          <el-radio-group v-model="batchDraftTopN">
            <el-radio-button :value="30">Top 30 (约 60% 营收)</el-radio-button>
            <el-radio-button :value="60">Top 60 (约 75% 营收)</el-radio-button>
            <el-radio-button :value="100">Top 100 (约 85% 营收)</el-radio-button>
            <el-radio-button :value="130">Top 130 (约 90% 营收)</el-radio-button>
          </el-radio-group>
        </div>
        <div style="display: flex; gap: 10px; align-items: center; margin-bottom: 14px">
          <span>菜系提示 (可选):</span>
          <el-input v-model="batchDraftHint" placeholder="如 川菜 / 粤菜 / 日料 / 面食" size="small" style="width: 240px" />
        </div>
        <el-button type="primary" size="large" :loading="batchDraftLoading" @click="runBatchDraft" style="margin-top: 12px">
 <span v-if="!batchDraftLoading"> 开始 AI 批量生成</span>
          <span v-else>AI 并发生成中 ({{ batchDraftStatus }}) ...</span>
        </el-button>
      </div>

      <div v-if="batchDraftResults" style="max-height: 65vh; overflow-y: auto">
        <el-alert :type="batchDraftResults.data.failed === 0 ? 'success' : 'warning'" :closable="false" style="margin-bottom: 14px">
          <template #title>
            AI 生成完成 · {{ batchDraftResults.data.succeeded }}/{{ batchDraftResults.data.requested }} 成功 ·
            用时 {{ batchDraftResults.data.elapsedSec }} 秒 ·
            <span v-if="batchDraftResults.data.failed > 0">{{ batchDraftResults.data.failed }} 失败</span>
          </template>
        </el-alert>
        <div style="display: flex; gap: 10px; margin-bottom: 10px">
          <el-button type="success" size="large" :loading="batchSaving" @click="batchSaveAll">
 一键采纳全部 {{ selectedDraftCount }} 道菜 (生成 {{ selectedRecipeLineCount }} 条配方行)
          </el-button>
          <el-button plain size="large" @click="batchDraftResults = null; batchDraftLoading = false">清空重来</el-button>
        </div>
        <el-table
          :data="batchDraftResults.data.drafts.filter(d => d.success)"
          :default-sort="{ prop: 'dishName', order: 'ascending' }"
          border
          size="small"
          @selection-change="onBatchDraftSelection"
          ref="batchDraftTableRef"
        >
          <el-table-column type="selection" width="45" />
          <el-table-column label="菜品" prop="dishName" min-width="160" show-overflow-tooltip />
          <el-table-column :label="canViewPrice ? '食材+用量+主料+建议单价' : '食材+用量+主料'" min-width="500">
            <template #default="{ row }">
              <div v-for="(ing, i) in row.ingredients" :key="i" style="display: flex; gap: 6px; align-items: center; font-size: 12px; margin-bottom: 4px">
                <el-tag size="small" :type="ing.is_main ? 'success' : ''">{{ ing.is_main ? '主' : '辅' }}</el-tag>
                <el-input v-model="ing.name" size="small" style="width: 110px" />
                <el-input-number v-model="ing.qty" :precision="4" :step="0.01" :min="0" size="small" :controls="false" style="width: 80px" />
                <el-input v-model="ing.unit" size="small" style="width: 50px" />
                <el-input-number v-if="canViewPrice" v-model="ing.suggested_unit_price" :precision="2" :step="1" :min="0" size="small" :controls="false" placeholder="单价" style="width: 80px" />
 <el-button type="danger" link size="small" @click="row.ingredients.splice(i, 1)"></el-button>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="成本率" width="90" align="center">
            <template #default="{ row }">
              <el-tag size="small">{{ row.estimatedCostRatio ? (row.estimatedCostRatio * 100).toFixed(0) + '%' : '—' }}</el-tag>
            </template>
          </el-table-column>
        </el-table>

        <div v-if="batchDraftResults.data.failed > 0" style="margin-top: 14px">
          <div style="font-size: 12px; color: var(--el-color-warning)">以下菜品 AI 生成失败, 可手动录入:</div>
          <div v-for="(d, i) in batchDraftResults.data.drafts.filter(d => !d.success)" :key="i" style="font-size: 12px; color: #909399">• {{ d.dishName }}: {{ d.message }}</div>
        </div>
      </div>
    </el-dialog>

    <!-- P2-7: AI 智能录配方 dialog -->
 <el-dialog v-model="aiDraftDialog" title=" AI 智能录配方" width="720px">
      <div style="line-height: 1.8">
        <p><b>1. 输入菜品名</b> — AI 会根据菜名生成 3-5 食材的配方草稿 (川菜成本率 25-40%)</p>
        <el-input v-model="draftDishName" placeholder="如: 宫保鸡丁 / 麻婆豆腐 / 青椒肉丝" size="large" style="margin-bottom: 8px">
          <template #append>
            <el-button type="primary" :loading="draftLoading" @click="generateDraft">AI 生成草稿</el-button>
          </template>
        </el-input>
        <el-input v-model="draftHint" placeholder="可选提示: 如 '川菜经典' / '素菜' / '粤式做法'" size="small" style="margin-bottom: 12px" />

        <div v-if="draftResult && draftResult.success" style="border: 1px solid #e4e7ed; border-radius: 6px; padding: 14px; background: #fafbfc">
          <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px">
            <b>AI 草稿: {{ draftResult.data.dishName }}</b>
            <el-tag v-if="draftResult.data.estimatedCostRatio" type="success">预估成本率 {{ (draftResult.data.estimatedCostRatio * 100).toFixed(0) }}%</el-tag>
          </div>
          <el-table :data="draftResult.data.ingredients" size="small" border style="margin-bottom: 10px">
            <el-table-column label="食材" prop="name" min-width="140">
              <template #default="{ row }">
                <el-input v-model="row.name" size="small" />
              </template>
            </el-table-column>
            <el-table-column label="用量" prop="qty" width="120">
              <template #default="{ row }">
                <el-input-number v-model="row.qty" :precision="4" :step="0.01" :min="0" size="small" :controls="false" style="width: 100px" />
              </template>
            </el-table-column>
            <el-table-column label="单位" prop="unit" width="90">
              <template #default="{ row }">
                <el-input v-model="row.unit" size="small" />
              </template>
            </el-table-column>
            <el-table-column label="主料" width="70">
              <template #default="{ row }">
                <el-switch v-model="row.is_main" size="small" />
              </template>
            </el-table-column>
            <el-table-column v-if="canViewPrice" label="单价(元/单位)" width="130">
              <template #default="{ row }">
                <el-input-number v-model="row.price" :precision="2" :step="1" :min="0" size="small" :controls="false" placeholder="采购价" style="width: 110px" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="60">
              <template #default="{ $index }">
                <el-button type="danger" link size="small" @click="draftResult!.data.ingredients.splice($index, 1)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-button size="small" plain @click="draftResult!.data.ingredients.push({ name: '', qty: 0.1, unit: 'kg', is_main: false, price: 0 })">+ 添加食材</el-button>
 <div v-if="draftResult.data.notes" style="font-size: 12px; color: #909399; margin-top: 10px"> AI 备注: {{ draftResult.data.notes }}</div>
          <div style="margin-top: 16px">
 <el-button type="success" :loading="savingDraft" @click="saveDraftRecipe"> 保存为配方 (一键创建菜品 + 食材 + 配方行)</el-button>
            <el-button plain @click="draftResult = null">清空重来</el-button>
          </div>
        </div>
        <el-alert v-else-if="draftResult && !draftResult.success" type="error" :closable="false" :title="draftResult.message || 'AI 生成失败'" />
        <div v-else style="color: #909399; font-size: 13px; padding: 20px 0; text-align: center">
 输入菜名 + 点 "AI 生成草稿". 草稿只是建议, 保存前可自由修改食材 / 用量 / 单价.
        </div>
      </div>
    </el-dialog>

    <!-- P1-3: 价格历史 drawer -->
    <el-drawer v-model="priceHistoryDrawer" title="食材价格历史" size="540px" direction="rtl">
      <div v-if="priceHistoryData">
        <div style="margin-bottom: 12px"><b>{{ priceHistoryData.materialName }}</b> <span style="color: #909399; font-size: 12px">{{ priceHistoryData.history.length }} 条变更记录</span></div>
        <div id="price-history-chart" style="height: 260px; margin-bottom: 14px" v-if="priceHistoryData.history.length > 1"></div>
        <el-empty v-else description="暂无历史变更 (食材价格首次录入后才会 snapshot)" :image-size="80" />
        <el-table :data="priceHistoryData.history" size="small" border v-if="priceHistoryData.history.length">
          <el-table-column v-if="canViewPrice" label="单价(元)" prop="unitPrice" width="90" align="right" />
          <el-table-column label="生效起" width="160">
            <template #default="{ row }">{{ row.effectiveFrom ? new Date(row.effectiveFrom).toLocaleDateString('zh-CN') : '-' }}</template>
          </el-table-column>
          <el-table-column label="生效止" width="160">
            <template #default="{ row }">{{ row.effectiveTo ? new Date(row.effectiveTo).toLocaleDateString('zh-CN') : '当前' }}</template>
          </el-table-column>
          <el-table-column label="原因" prop="changeReason" show-overflow-tooltip />
        </el-table>
      </div>
    </el-drawer>

    <!-- #57: 成本卡 drawer (出菜反推) -->
    <el-drawer v-model="costCardDrawer" title="菜品成本卡" size="560px" direction="rtl">
      <div v-loading="costCardLoading">
        <!-- 防呆 Rule 2: dish header (品名 + 编号 + 配方行数 + 更新时间) -->
        <div v-if="costCard" class="cost-card-header">
          <div class="cc-title">{{ costCard.productName }}</div>
          <div class="cc-sub">
            编号 {{ costCard.productTypeId }} · 配方 {{ costCard.recipeLineCount ?? 0 }} 种食材
            <span v-if="costCard.recipeUpdatedAt">· 更新 {{ new Date(costCard.recipeUpdatedAt).toLocaleDateString('zh-CN') }}</span>
          </div>
        </div>

        <!-- 防呆 Rule 1: pre-show missing-price warning (don't block partial) -->
        <el-alert
          v-if="costCard && costCard.hasMissingPrices"
          type="warning"
          :closable="false"
          show-icon
          style="margin: 10px 0"
        >
          <template #title>
            部分食材未配单价，食材总成本不完整。
            <el-link type="primary" href="/warehouse/material-types" underline="never" style="vertical-align: baseline">
              去原料管理补充单价
            </el-link>
          </template>
        </el-alert>

        <!-- portions input (防呆 Rule 1: max 9999, live scaling) -->
        <div v-if="costCard" class="cc-portions">
          <span>份数</span>
          <el-input-number
            v-model="costCardPortions"
            :min="1"
            :max="9999"
            :step="1"
            size="small"
            @change="reloadCostCard"
          />
          <span class="cc-portions-hint">改份数后成本与售价按份数实时缩放</span>
        </div>

        <!-- summary KPIs (cost fields behind canViewPrice) -->
        <el-descriptions v-if="costCard" :column="1" border style="margin: 10px 0">
          <el-descriptions-item v-if="canViewPrice" label="食材总成本">
            <span v-if="costCard.totalIngredientCost != null">¥{{ costCard.totalIngredientCost }}</span>
            <span v-else style="color: var(--el-color-warning)">不完整 (部分食材缺单价)</span>
          </el-descriptions-item>
          <el-descriptions-item v-if="canViewPrice" label="售价">
            <span v-if="costCard.sellPrice != null">¥{{ costCard.sellPrice }}</span>
            <span v-else style="color: #909399">未设售价</span>
          </el-descriptions-item>
          <el-descriptions-item label="毛利率">
            <el-tag v-if="costCard.grossMargin != null" :type="costCard.grossMargin >= 0.5 ? 'success' : 'warning'">
              {{ (costCard.grossMargin * 100).toFixed(1) }}%
            </el-tag>
            <span v-else style="color: #909399">暂不可计 (缺成本或售价)</span>
          </el-descriptions-item>
        </el-descriptions>

        <!-- ingredient breakdown -->
        <el-table v-if="costCard && costCard.ingredients" :data="costCard.ingredients" size="small" border>
          <el-table-column label="食材" prop="materialName" min-width="110" show-overflow-tooltip>
            <template #default="{ row }">{{ row.materialName || row.rawMaterialTypeId }}</template>
          </el-table-column>
          <el-table-column label="标准用量" width="100" align="right">
            <template #default="{ row }">{{ row.standardQty }} {{ row.unit }}</template>
          </el-table-column>
          <el-table-column label="净料率" width="80" align="center">
            <template #default="{ row }">{{ row.netYieldRate != null ? (row.netYieldRate * 100).toFixed(0) + '%' : '-' }}</template>
          </el-table-column>
          <el-table-column label="实际用量" width="100" align="right">
            <template #default="{ row }">{{ row.actualQty }} {{ row.unit }}</template>
          </el-table-column>
          <el-table-column v-if="canViewPrice" label="单价" width="90" align="right">
            <template #default="{ row }">
              <span v-if="row.unitPrice != null">¥{{ row.unitPrice }}</span>
              <span v-else style="color: var(--el-color-warning)">缺价</span>
            </template>
          </el-table-column>
          <el-table-column v-if="canViewPrice" label="小计" width="90" align="right">
            <template #default="{ row }">
              <span v-if="row.itemCost != null">¥{{ row.itemCost }}</span>
              <span v-else style="color: var(--el-color-warning)">—</span>
            </template>
          </el-table-column>
        </el-table>

        <!-- 防呆 Rule 5: no recipe → message + nextAction link -->
        <el-empty
          v-if="!costCardLoading && !costCard"
          description="该菜品尚未配置配方 (BOM)，无法生成成本卡"
          :image-size="90"
        >
          <el-button type="primary" @click="handleCreate">去配方管理录入</el-button>
        </el-empty>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { Plus, Search, Refresh, Download } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox, type FormInstance } from 'element-plus';
import { useFactoryId } from '@/composables/useFactoryId';
import { usePermissionStore } from '@/store/modules/permission';
import { getRecipes, getRecipe, getRecipeSummary, createRecipe, updateRecipe, deleteRecipe, getProductTypesActive, getRawMaterialTypes, getDishCostCard } from '@/api/restaurant';
import { emptyCell, exportTableToExcel } from '@/utils/tableFormatters';
import type { RecipeItem } from '@/types/restaurant';
import AnalyticsStrip from '../components/AnalyticsStrip.vue';
import UpstreamMissingHint from '@/components/common/UpstreamMissingHint.vue';
import { useCreateAndReturn } from '@/composables/useCreateAndReturn';

const factoryId = useFactoryId();
const permissionStore = usePermissionStore();
const canWrite = computed(() => permissionStore.canWrite('restaurant'));
const canViewPrice = computed(() => permissionStore.canViewPrice);
const { goCreate } = useCreateAndReturn();

const productTypes = ref<{ id: string; name: string }[]>([]);
const materialTypes = ref<{ id: string; name: string }[]>([]);
const productNameMap = computed(() => Object.fromEntries(productTypes.value.map(p => [p.id, p.name])));
const materialNameMap = computed(() => Object.fromEntries(materialTypes.value.map(m => [m.id, m.name])));

async function loadSelectOptions() {
  try {
    const [ptRes, mtRes] = await Promise.all([
      getProductTypesActive(factoryId.value),
      getRawMaterialTypes(factoryId.value)
    ]);
    if (ptRes.success && ptRes.data) {
      productTypes.value = Array.isArray(ptRes.data) ? ptRes.data : [];
    }
    if (mtRes.success && mtRes.data) {
      const d = mtRes.data as { content?: { id: string; name: string }[] } | { id: string; name: string }[];
      materialTypes.value = Array.isArray(d) ? d : d.content || [];
    }
  } catch (e) {
    console.error('Failed to load select options:', e);
    ElMessage.error('加载选项数据失败');
  }
}

const statsLoaded = ref(false);
const statsData = ref<{ totalRecipes?: number; activeRecipes?: number; dishCount?: number; materialCount?: number }>({});

async function loadStatistics() {
  try {
    const res = await getRecipeSummary(factoryId.value);
    if (res.success && res.data) {
      const byProduct = Array.isArray(res.data.byProduct) ? res.data.byProduct : [];
      statsData.value = {
        totalRecipes: res.data.totalRecipeLines ?? res.data.totalRecipes ?? res.data.total ?? 0,
        activeRecipes: byProduct.length,
        dishCount: res.data.totalProducts ?? res.data.dishCount ?? 0,
        materialCount: res.data.materialCount ?? 0,
      };
      statsLoaded.value = true;
    }
  } catch (e) {
    console.error('Failed to load recipe summary:', e);
    ElMessage.error('加载配方统计失败');
  }
}

const loading = ref(false);
const submitting = ref(false);
const tableData = ref<RecipeItem[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });
const filterActive = ref<boolean | undefined>(undefined);
const filterDish = ref('');
const filterKeyword = ref('');
const dialogVisible = ref(false);
const detailVisible = ref(false);
const detailData = ref<RecipeItem | null>(null);
const formRef = ref<FormInstance>();

const formRules = {
  productTypeId: [{ required: true, message: '请选择菜品', trigger: 'change' }],
  rawMaterialTypeId: [{ required: true, message: '请选择食材', trigger: 'change' }],
  standardQuantity: [{ required: true, message: '请输入标准用量', trigger: 'blur' }],
};

const emptyForm = (): RecipeItem => ({
  id: '',
  productTypeId: '',
  rawMaterialTypeId: '',
  standardQuantity: 0.1,
  unit: 'kg',
  netYieldRate: 1.0,
  isMainIngredient: true,
  isActive: true,
  notes: ''
});
const dialogForm = ref<RecipeItem>(emptyForm());

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await getRecipes(factoryId.value, {
      page: pagination.value.page,
      size: pagination.value.size,
      isActive: filterActive.value,
      productTypeId: filterDish.value || undefined
    });
    if (res.success && res.data) {
      const d = res.data as { content?: unknown[]; totalElements?: number } | unknown[];
      let items = Array.isArray(d) ? d : (d as { content?: unknown[]; totalElements?: number }).content || [];
      // Client-side keyword filter (Recipe stores IDs, not names)
      const kw = filterKeyword.value?.trim().toLowerCase();
      if (kw) {
        items = (items as RecipeItem[]).filter((r) => {
          const matName = materialNameMap.value[r.rawMaterialTypeId] || '';
          const prodName = productNameMap.value[r.productTypeId] || '';
          return matName.toLowerCase().includes(kw) || prodName.toLowerCase().includes(kw) || (r.notes || '').toLowerCase().includes(kw);
        });
      }
      tableData.value = items as RecipeItem[];
      pagination.value.total = kw ? items.length : (Array.isArray(d) ? items.length : ((d as { content?: unknown[]; totalElements?: number }).totalElements ?? items.length));
    } else {
      tableData.value = [];
      pagination.value.total = 0;
    }
  } catch (e) {
    console.error('Load recipes failed:', e);
    ElMessage.error('加载配方数据失败');
    tableData.value = [];
    pagination.value.total = 0;
  } finally {
    loading.value = false;
  }
}

const router = useRouter();
function handleAiAnalyze() {
  router.push({ path: '/smart-bi/query', query: { q: '食材成本最高的菜品 top 10, 以及配方平均食材数' } });
}

function handleSearch() {
  pagination.value.page = 1;
  loadData();
}

function handleRefresh() {
  filterActive.value = undefined;
  filterDish.value = '';
  filterKeyword.value = '';
  pagination.value.page = 1;
  loadData();
}

function handleCreate() {
  dialogForm.value = emptyForm();
  dialogVisible.value = true;
}

function handleEdit(row: RecipeItem) {
  dialogForm.value = { ...row };
  dialogVisible.value = true;
}

async function showDetail(row: RecipeItem) {
  detailData.value = row;
  detailVisible.value = true;
  try {
    const res = await getRecipe(factoryId.value, row.id);
    if (res.success && res.data) detailData.value = res.data;
  } catch { /* keep cached row data */ }
}

async function handleDeactivate(row: RecipeItem) {
  try {
    await ElMessageBox.confirm(
      '确认停用该配方？停用后配方将不参与领料计算和成本核算，可随时重新启用。',
      '提示',
      { type: 'warning', confirmButtonText: '停用', cancelButtonText: '取消' }
    );
    const res = await deleteRecipe(factoryId.value, row.id);
    if (res.success) {
      ElMessage.success('已停用');
      loadData();
    } else {
      ElMessage.error(res.message || '停用失败');
    }
  } catch (e) {
    // Interceptor already shows specific sticky toast for ApiError.
    if (e !== 'cancel') console.error('Deactivate recipe failed:', e);
  }
}

async function handleActivate(row: RecipeItem) {
  try {
    await ElMessageBox.confirm('确认启用该配方？', '提示', {
      type: 'info',
      confirmButtonText: '启用',
      cancelButtonText: '取消',
    });
    // 后端无独立 activate endpoint，复用 PUT update 把 isActive 翻回 true。
    const res = await updateRecipe(factoryId.value, row.id, { ...row, isActive: true });
    if (res.success) {
      ElMessage.success('已启用');
      loadData();
    } else {
      ElMessage.error(res.message || '启用失败');
    }
  } catch (e) {
    if (e !== 'cancel') console.error('Activate recipe failed:', e);
  }
}

async function submitForm() {
  if (formRef.value) {
    try { await formRef.value.validate(); } catch { return; }
  }
  // 前端防御: 必填字段（后端 @NotBlank / @NotNull）
  if (!dialogForm.value.productTypeId) {
    ElMessage.warning('请选择菜品');
    return;
  }
  if (!dialogForm.value.rawMaterialTypeId) {
    ElMessage.warning('请选择食材');
    return;
  }
  if (dialogForm.value.standardQuantity == null || Number(dialogForm.value.standardQuantity) <= 0) {
    ElMessage.warning('请输入大于 0 的标准用量');
    return;
  }
  // netYieldRate 后端要求 0.01-1.00,前端 input-number 已限制,额外防 null
  if (dialogForm.value.netYieldRate == null) {
    dialogForm.value.netYieldRate = 1.0;
  }
  submitting.value = true;
  try {
    if (dialogForm.value.id) {
      const res = await updateRecipe(factoryId.value, dialogForm.value.id, dialogForm.value);
      if (res.success) {
        ElMessage.success('更新成功');
      } else {
        ElMessage.error(res.message || '更新失败');
        return;
      }
    } else {
      // 剔除空串 id,避免干扰后端
      const { id: _discard, ...payload } = dialogForm.value;
      void _discard;
      const res = await createRecipe(factoryId.value, payload);
      if (res.success) {
        ElMessage.success('创建成功');
      } else {
        ElMessage.error(res.message || '创建失败');
        return;
      }
    }
    dialogVisible.value = false;
    loadData();
  } catch (e) {
    // Interceptor already shows specific sticky toast for ApiError.
    console.error('Save recipe failed:', e);
  } finally {
    submitting.value = false;
  }
}

const MAX_EXPORT = 10000;

async function handleExport() {
  let exportData: RecipeItem[] = tableData.value;
  if (pagination.value.total > pagination.value.size) {
    const exportSize = Math.min(pagination.value.total, MAX_EXPORT);
    if (pagination.value.total > MAX_EXPORT) ElMessage.warning(`数据量较大，仅导出前 ${MAX_EXPORT} 条`);
    else ElMessage.info('正在导出全部数据…');
    try {
      const res = await getRecipes(factoryId.value, { page: 1, size: exportSize });
      if (res.success && res.data) {
        const d = res.data as { content?: RecipeItem[] } | RecipeItem[];
        exportData = Array.isArray(d) ? d : (d as { content?: RecipeItem[] }).content || [];
      }
    } catch { /* fall back to current page */ }
  }
  await exportTableToExcel(exportData as unknown as Record<string, unknown>[], [
    { label: '菜品', field: 'productTypeId', formatter: (val) => productNameMap.value[String(val)] || String(val) },
    { label: '食材', field: 'rawMaterialTypeId', formatter: (val) => materialNameMap.value[String(val)] || String(val) },
    { label: '标准用量', field: 'standardQuantity' },
    { label: '单位', field: 'unit' },
    { label: '净料率', field: 'netYieldRate', formatter: (val) => val ? (Number(val) * 100).toFixed(1) + '%' : '-' },
    { label: '主料/辅料', field: 'isMainIngredient', formatter: (val) => val ? '主料' : '辅料' },
    { label: '状态', field: 'isActive', formatter: (val) => val ? '启用' : '停用' },
    { label: '备注', field: 'notes' },
  ], '配方管理');
}

onMounted(() => { loadData(); loadSelectOptions(); loadStatistics(); loadUnmatchedCount(); });

// ====================================================================
// P0-1: Batch import dialog
// ====================================================================
const batchImportDialog = ref(false);
const selectedFile = ref<File | null>(null);
const importing = ref(false);
const importResult = ref<{ success: boolean; message?: string; data?: { dishesCreated: number; ingredientsCreated: number; recipesCreated: number; errors: string[]; errorCount: number } } | null>(null);

function onFileSelect(file: { raw: File }) {
  selectedFile.value = file.raw;
  importResult.value = null;
}

async function downloadTemplate() {
  try {
    const { pythonFetch } = await import('@/api/smartbi/common');
    const res = await pythonFetch('/api/smartbi/restaurant-ops/recipes/import-template') as {
      success: boolean;
      data?: { columns: string[]; sample: Record<string, unknown>[] };
    };
    if (!res.success || !res.data) {
      ElMessage.error('模板获取失败');
      return;
    }
    // Build CSV with BOM for Excel Chinese compatibility
    const cols = res.data.columns;
    const sample = res.data.sample;
    let csv = '﻿' + cols.join(',') + '\n';
    for (const row of sample) {
      csv += cols.map(c => String(row[c] ?? '')).join(',') + '\n';
    }
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = '配方批量导入模板.csv';
    a.click();
    URL.revokeObjectURL(url);
    ElMessage.success('模板已下载');
  } catch (e) {
    ElMessage.error('模板下载失败');
  }
}

async function submitImport() {
  if (!selectedFile.value) return;
  importing.value = true;
  importResult.value = null;
  try {
    const { pythonFetch } = await import('@/api/smartbi/common');
    const formData = new FormData();
    formData.append('file', selectedFile.value);
    const res = await pythonFetch('/api/smartbi/restaurant-ops/recipes/batch-import', {
      method: 'POST',
      body: formData,
      // Force headers: {} to let browser set multipart/form-data boundary
      headers: {},
      timeoutMs: 120000,
    }) as typeof importResult.value;
    importResult.value = res;
    if (res?.success) {
      ElMessage.success(`导入成功: 菜品 +${res.data?.dishesCreated} / 食材 +${res.data?.ingredientsCreated} / 配方 +${res.data?.recipesCreated}`);
      loadData();
      loadStatistics();
    } else {
      ElMessage.error(res?.message || '导入失败');
    }
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    importResult.value = { success: false, message: msg };
    ElMessage.error('导入异常: ' + msg);
  } finally {
    importing.value = false;
  }
}

// ====================================================================
// P0-2: Alias panel (unmatched dishes)
// ====================================================================
const aliasDialog = ref(false);
const unmatchedCount = ref(0);
const unmatchedData = ref<{ totalPosDishes: number; unmatchedCount: number; unmatchedRevenueRatio: number; dishes: Array<{ name: string; revenue: number; qty: number; bills: number; _bindTo?: string }> } | null>(null);
const availableProducts = ref<Array<{ id: string; name: string }>>([]);

async function loadUnmatchedCount() {
  try {
    const { pythonFetch } = await import('@/api/smartbi/common');
    const res = await pythonFetch('/api/smartbi/restaurant-ops/unmatched-dishes') as { success: boolean; data?: { unmatchedCount: number } };
    if (res.success && res.data) unmatchedCount.value = res.data.unmatchedCount;
  } catch { /* silent */ }
}

async function openAliasPanel() {
  aliasDialog.value = true;
  try {
    const { pythonFetch } = await import('@/api/smartbi/common');
    const [unmatched, products] = await Promise.all([
      pythonFetch('/api/smartbi/restaurant-ops/unmatched-dishes') as Promise<{ success: boolean; data?: Record<string, unknown> }>,
      pythonFetch('/api/smartbi/restaurant-ops/product-types') as Promise<{ success: boolean; data?: { products: Array<{ id: string; name: string }> } }>,
    ]);
    if (unmatched.success && unmatched.data) unmatchedData.value = unmatched.data as typeof unmatchedData.value;
    if (products.success && products.data) availableProducts.value = products.data.products;
  } catch (e) {
    ElMessage.error('加载未匹配菜品失败');
  }
}

const selectedUnmatched = ref<Array<{ name: string }>>([]);
function onSelectionChange(rows: Array<{ name: string }>) { selectedUnmatched.value = rows; }

async function markAsNoise() {
  if (selectedUnmatched.value.length === 0) return;
  try {
    const { pythonFetch } = await import('@/api/smartbi/common');
    const names = selectedUnmatched.value.map(r => r.name);
    const res = await pythonFetch('/api/smartbi/restaurant-ops/excluded-dishes', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ pos_names: names }),
    }) as { success: boolean; data?: { markedCount: number }; message?: string };
    if (res.success) {
      ElMessage.success(`已标记 ${res.data?.markedCount ?? 0} 个为非菜品, 覆盖率分母自动重算`);
      // Remove from list
      if (unmatchedData.value) {
        unmatchedData.value.dishes = unmatchedData.value.dishes.filter(d => !names.includes(d.name));
        unmatchedData.value.unmatchedCount -= names.length;
        unmatchedCount.value = Math.max(0, unmatchedCount.value - names.length);
      }
      selectedUnmatched.value = [];
    } else {
      ElMessage.error(res.message || '标记失败');
    }
  } catch (e) { ElMessage.error('标记异常'); }
}

// ====================================================================
// P2-7 Batch: AI 批量录配方 dialog
// ====================================================================
interface BatchDraftResult {
  success: boolean;
  data: {
    requested: number;
    succeeded: number;
    failed: number;
    elapsedSec: number;
    concurrency: number;
    drafts: Array<{
      success: boolean;
      dishName: string;
      ingredients?: DraftIngredient[];
      estimatedCostRatio?: number | null;
      notes?: string;
      message?: string;
    }>;
  };
}

const aiBatchDraftDialog = ref(false);
const batchDraftTopN = ref<number>(30);
const batchDraftHint = ref('');
const batchDraftLoading = ref(false);
const batchDraftStatus = ref('');
const batchDraftResults = ref<BatchDraftResult | null>(null);
const batchSaving = ref(false);
const selectedBatchDrafts = ref<BatchDraftResult['data']['drafts']>([]);

function onBatchDraftSelection(rows: BatchDraftResult['data']['drafts']) {
  selectedBatchDrafts.value = rows;
}

const selectedDraftCount = computed(() => selectedBatchDrafts.value.length || (batchDraftResults.value?.data.drafts.filter(d => d.success).length ?? 0));
const selectedRecipeLineCount = computed(() => {
  const src = selectedBatchDrafts.value.length ? selectedBatchDrafts.value : (batchDraftResults.value?.data.drafts.filter(d => d.success) ?? []);
  return src.reduce((sum, d) => sum + (d.ingredients?.length || 0), 0);
});

async function openAiBatchDraftDialog() {
  aiBatchDraftDialog.value = true;
  batchDraftResults.value = null;
}

async function runBatchDraft() {
  batchDraftLoading.value = true;
  batchDraftStatus.value = '读取 top 未录菜品';
  batchDraftResults.value = null;
  try {
    const { pythonFetch } = await import('@/api/smartbi/common');
    // Step 1: get top N unmatched dishes by revenue
    const unmatched = await pythonFetch('/api/smartbi/restaurant-ops/unmatched-dishes') as {
      success: boolean;
      data?: { dishes: Array<{ name: string; revenue: number }> };
    };
    if (!unmatched.success || !unmatched.data?.dishes?.length) {
      ElMessage.warning('未发现未录配方的菜品');
      return;
    }
    const dishNames = unmatched.data.dishes.slice(0, batchDraftTopN.value).map(d => d.name);
    batchDraftStatus.value = `调 AI 并发生成 ${dishNames.length} 道`;
    // Step 2: batch AI
    const res = await pythonFetch('/api/smartbi/restaurant-ops/recipes/ai-draft-batch', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ dish_names: dishNames, hint: batchDraftHint.value.trim() || undefined, concurrency: 10 }),
      timeoutMs: 300000,
    }) as BatchDraftResult;
    batchDraftResults.value = res;
    if (res.success) {
      ElMessage.success(`AI 生成完成: ${res.data.succeeded}/${res.data.requested} 成功, 用时 ${res.data.elapsedSec} 秒`);
    } else {
      ElMessage.error('批量生成失败');
    }
  } catch (e) {
    ElMessage.error('批量生成异常: ' + (e instanceof Error ? e.message : String(e)));
  } finally {
    batchDraftLoading.value = false;
  }
}

async function batchSaveAll() {
  const toSave = selectedBatchDrafts.value.length > 0
    ? selectedBatchDrafts.value
    : batchDraftResults.value!.data.drafts.filter(d => d.success);
  if (!toSave.length) {
    ElMessage.warning('没有可保存的草稿');
    return;
  }
  batchSaving.value = true;
  try {
    // Build CSV from all selected drafts
    const cols = ['菜品名称', '食材名称', '用量', '单位', '食材单价', '是否主料'];
    let csv = '﻿' + cols.join(',') + '\n';
    for (const d of toSave) {
      for (const ing of d.ingredients || []) {
        const row = {
          菜品名称: d.dishName,
          食材名称: ing.name,
          用量: ing.qty,
          单位: ing.unit || 'kg',
          食材单价: (ing as DraftIngredient & { suggested_unit_price?: number }).suggested_unit_price ?? ing.price ?? 0,
          是否主料: ing.is_main ? '是' : '否',
        };
        csv += cols.map(c => String(row[c as keyof typeof row] ?? '')).join(',') + '\n';
      }
    }
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
    const file = new File([blob], 'ai_batch_draft.csv', { type: 'text/csv' });
    const { pythonFetch } = await import('@/api/smartbi/common');
    const formData = new FormData();
    formData.append('file', file);
    const res = await pythonFetch('/api/smartbi/restaurant-ops/recipes/batch-import', {
      method: 'POST',
      body: formData,
      headers: {},
      timeoutMs: 300000,
    }) as { success: boolean; data?: { dishesCreated: number; ingredientsCreated: number; recipesCreated: number }; message?: string };
    if (res.success) {
      ElMessage.success(`批量保存成功: 菜品 +${res.data?.dishesCreated} / 食材 +${res.data?.ingredientsCreated} / 配方行 +${res.data?.recipesCreated}`);
      aiBatchDraftDialog.value = false;
      batchDraftResults.value = null;
      loadData();
      loadStatistics();
      loadUnmatchedCount();
      // Also trigger ETL so gross-margin page updates
      try {
        pythonFetch('/api/smartbi/restaurant-ops/etl', { method: 'POST' });
        ElMessage.info('后台自动重新计算毛利中...');
      } catch { /* ignore */ }
    } else {
      ElMessage.error(res.message || '批量保存失败');
    }
  } catch (e) {
    ElMessage.error('批量保存异常: ' + (e instanceof Error ? e.message : String(e)));
  } finally {
    batchSaving.value = false;
  }
}

// ====================================================================
// P2-7: AI recipe draft dialog
// ====================================================================
interface DraftIngredient { name: string; qty: number; unit: string; is_main: boolean; price?: number }
interface DraftResult {
  success: boolean;
  message?: string;
  data?: {
    dishName: string;
    ingredients: DraftIngredient[];
    estimatedCostRatio: number | null;
    notes: string;
  };
}

const aiDraftDialog = ref(false);
const draftDishName = ref('');
const draftHint = ref('');
const draftLoading = ref(false);
const savingDraft = ref(false);
const draftResult = ref<DraftResult | null>(null);

async function generateDraft() {
  if (!draftDishName.value || draftDishName.value.trim().length < 2) {
    ElMessage.warning('请输入菜品名称 (至少 2 个字)');
    return;
  }
  draftLoading.value = true;
  draftResult.value = null;
  try {
    const { pythonFetch } = await import('@/api/smartbi/common');
    const res = await pythonFetch('/api/smartbi/restaurant-ops/recipes/ai-draft', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ dish_name: draftDishName.value.trim(), hint: draftHint.value.trim() || undefined }),
      timeoutMs: 60000,
    }) as DraftResult;
    // Init price field for UI
    if (res.success && res.data) {
      res.data.ingredients = res.data.ingredients.map(i => ({ ...i, price: 0 }));
    }
    draftResult.value = res;
    if (!res.success) ElMessage.error(res.message || 'AI 生成失败');
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    draftResult.value = { success: false, message: msg };
    ElMessage.error('AI 调用异常: ' + msg);
  } finally {
    draftLoading.value = false;
  }
}

async function saveDraftRecipe() {
  if (!draftResult.value?.data) return;
  const d = draftResult.value.data;
  if (!d.ingredients.length) {
    ElMessage.warning('至少保留 1 个食材');
    return;
  }
  savingDraft.value = true;
  try {
    // Build CSV-like payload matching batch-import parser
    const rows: Record<string, unknown>[] = d.ingredients.map(i => ({
      菜品名称: d.dishName,
      食材名称: i.name,
      用量: i.qty,
      单位: i.unit || 'kg',
      食材单价: i.price || 0,
      是否主料: i.is_main ? '是' : '否',
    }));
    // Convert to CSV blob
    const cols = ['菜品名称', '食材名称', '用量', '单位', '食材单价', '是否主料'];
    let csv = '﻿' + cols.join(',') + '\n';
    for (const r of rows) csv += cols.map(c => String(r[c] ?? '')).join(',') + '\n';
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
    const file = new File([blob], 'ai_draft.csv', { type: 'text/csv' });

    const { pythonFetch } = await import('@/api/smartbi/common');
    const formData = new FormData();
    formData.append('file', file);
    const res = await pythonFetch('/api/smartbi/restaurant-ops/recipes/batch-import', {
      method: 'POST',
      body: formData,
      headers: {},
      timeoutMs: 60000,
    }) as { success: boolean; data?: { dishesCreated: number; ingredientsCreated: number; recipesCreated: number }; message?: string };
    if (res.success) {
      ElMessage.success(`已保存: 菜品 +${res.data?.dishesCreated} / 食材 +${res.data?.ingredientsCreated} / 配方 +${res.data?.recipesCreated}`);
      aiDraftDialog.value = false;
      draftResult.value = null;
      draftDishName.value = '';
      draftHint.value = '';
      loadData();
      loadStatistics();
    } else {
      ElMessage.error(res.message || '保存失败');
    }
  } catch (e) {
    ElMessage.error('保存异常: ' + (e instanceof Error ? e.message : String(e)));
  } finally {
    savingDraft.value = false;
  }
}

// ====================================================================
// P1-3: Price history drawer (triggered from table row)
// ====================================================================
const priceHistoryDrawer = ref(false);
const priceHistoryData = ref<{ materialName: string; history: Array<{ unitPrice: number; effectiveFrom: string; effectiveTo: string | null; changeReason: string }> } | null>(null);

async function showPriceHistory(materialId: string, materialName: string) {
  priceHistoryDrawer.value = true;
  priceHistoryData.value = null;
  try {
    const { pythonFetch } = await import('@/api/smartbi/common');
    const res = await pythonFetch(`/api/smartbi/restaurant-ops/materials/${materialId}/price-history`) as {
      success: boolean;
      data?: { history: Array<{ unitPrice: number; effectiveFrom: string; effectiveTo: string | null; changeReason: string }> };
      message?: string;
    };
    if (res.success && res.data) {
      priceHistoryData.value = { materialName, history: res.data.history };
      // Render chart if more than 1 entry
      if (res.data.history.length > 1) {
        import('echarts').then((echartsMod) => {
          const echarts = ((echartsMod as unknown as { default?: unknown }).default ?? echartsMod) as typeof import('echarts');
          setTimeout(() => {
            const el = document.getElementById('price-history-chart');
            if (!el) return;
            const chart = echarts.init(el);
            const sorted = [...res.data!.history].reverse();  // oldest first for x axis
            chart.setOption({
              title: { text: '价格变化', left: 'center', textStyle: { fontSize: 12 } },
              tooltip: { trigger: 'axis' },
              xAxis: {
                type: 'category',
                data: sorted.map(h => h.effectiveFrom ? new Date(h.effectiveFrom).toLocaleDateString('zh-CN') : '-'),
              },
              yAxis: { type: 'value', name: '单价(元)' },
              series: [{ type: 'line', step: 'end', data: sorted.map(h => h.unitPrice), itemStyle: { color: '#409eff' } }],
            });
          }, 100);
        });
      }
    } else {
      ElMessage.error(res.message || '价格历史加载失败');
    }
  } catch (e) {
    ElMessage.error('价格历史加载异常');
  }
}

async function bindAlias(posName: string, productTypeId: string) {
  try {
    const { pythonFetch } = await import('@/api/smartbi/common');
    const res = await pythonFetch('/api/smartbi/restaurant-ops/aliases', {
      method: 'POST',
      body: JSON.stringify({ pos_name: posName, product_type_id: productTypeId }),
      headers: { 'Content-Type': 'application/json' },
    }) as { success: boolean; message?: string };
    if (res.success) {
      ElMessage.success(`已绑定: ${posName} → 配方菜品`);
      // Remove from list
      if (unmatchedData.value) {
        unmatchedData.value.dishes = unmatchedData.value.dishes.filter(d => d.name !== posName);
        unmatchedData.value.unmatchedCount -= 1;
        unmatchedCount.value = Math.max(0, unmatchedCount.value - 1);
      }
    } else {
      ElMessage.error(res.message || '绑定失败');
    }
  } catch (e) {
    ElMessage.error('绑定异常');
  }
}

// ====================================================================
// #57: 成本卡 drawer (出菜反推)
// ====================================================================
interface IngredientCostLine {
  rawMaterialTypeId: string;
  materialName: string | null;
  standardQty: number | null;
  actualQty: number | null;
  unit: string | null;
  netYieldRate: number | null;
  unitPrice: number | null;   // null when stripped (no price perm) or unconfigured
  itemCost: number | null;
}
interface DishCostCard {
  productTypeId: string;
  productName: string;
  portions: number;
  totalIngredientCost: number | null;
  sellPrice: number | null;
  grossMargin: number | null;
  hasMissingPrices: boolean;
  recipeLineCount: number | null;
  recipeUpdatedAt: string | null;
  computedAt: string | null;
  ingredients: IngredientCostLine[];
}

const costCardDrawer = ref(false);
const costCardLoading = ref(false);
const costCard = ref<DishCostCard | null>(null);
const costCardPortions = ref(1);
const costCardProductId = ref('');

async function openCostCard(productTypeId: string) {
  costCardProductId.value = productTypeId;
  costCardPortions.value = 1;
  costCardDrawer.value = true;
  await reloadCostCard();
}

async function reloadCostCard() {
  if (!costCardProductId.value) return;
  costCardLoading.value = true;
  costCard.value = null;
  try {
    const res = await getDishCostCard(factoryId.value, costCardProductId.value, costCardPortions.value);
    if (res.success && res.data) {
      costCard.value = res.data as DishCostCard;
    } else {
      // 404 (no recipe) or business error — show empty state (Rule 5 nextAction in template)
      costCard.value = null;
    }
  } catch {
    // Interceptor shows specific sticky toast; drawer falls to empty state
    costCard.value = null;
  } finally {
    costCardLoading.value = false;
  }
}

// Handle full-page reload: factoryId may not be ready at mount time
watch(factoryId, (val) => { if (val) { loadData(); loadStatistics(); } });
</script>

<style scoped lang="scss">
@use '../restaurant-shared.scss';

.cost-card-header {
  .cc-title { font-size: 16px; font-weight: 600; }
  .cc-sub { font-size: 12px; color: #909399; margin-top: 4px; }
}
.cc-portions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 10px 0;
  .cc-portions-hint { font-size: 12px; color: #909399; }
}
</style>
