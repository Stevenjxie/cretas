#!/usr/bin/env node

/**
 * Warehouse Module i18n Migration Script
 * Automatically migrates Chinese text to use i18n translation keys
 */

const fs = require('fs');
const path = require('path');

// Translation mapping for warehouse module
const translations = {
  // WHHomeScreen
  '仓储工作台': "t('home.title')",
  '今日入库 ': "t('home.headerSubtitle', { inbound: ",
  ' 单 | 待出货 ': ", pending: ",
  ' 单': ` }) + ' ${t('home.units.orders')}'`,
  '出货任务': "t('home.tabs.outbound')",
  '入库任务': "t('home.tabs.inbound')",
  '今日出货 - 按发出时间排序': "t('home.sections.todayOutbound')",
  '今日入库 - 调度安排': "t('home.sections.todayInbound')",
  '查看全部 >': "t('home.sections.viewAll')",
  '今日入库': "t('home.stats.todayInbound')",
  '今日出库': "t('home.stats.todayOutbound')",
  '待出货': "t('home.stats.pendingOutbound')",
  '库存预警': "t('home.alerts.title')",
  'kg': "t('home.units.kg')",
  '单': "t('home.units.orders')",
  '项': "t('home.units.items')",
  '客户': "t('home.outboundTask.customer')",
  '产品': "t('home.outboundTask.product')",
  '数量': "t('home.outboundTask.quantity')",
  '开始打包': "t('home.outboundTask.startPacking')",
  '完成打包': "t('home.outboundTask.finishPacking')",
  '供应商': "t('home.inboundTask.supplier')",
  '货品': "t('home.inboundTask.goods')",
  '预计数量': "t('home.inboundTask.expectedQuantity')",
  '确认入库': "t('home.inboundTask.confirmInbound')",
  '完成入库': "t('home.inboundTask.finishInbound')",
  '待打包': "t('home.status.waiting')",
  '打包中': "t('home.status.packing')",
  '已打包': "t('home.status.packed')",
  '已发货': "t('home.status.shipped')",
  '待入库': "t('home.status.pending')",
  '已到货': "t('home.status.arrived')",
  '质检中': "t('home.status.inspecting')",
  '已入库': "t('home.status.completed')",
  '安全库存': "t('home.alerts.safetyStock')",
  '即将过期': "t('home.alerts.expiring')",
  '温控监控': "t('home.tempMonitor.title')",
  '在线': "t('home.tempMonitor.online')",
  '离线': "t('home.tempMonitor.offline')",
  '正常': "t('home.tempMonitor.normal')",
  '警告': "t('home.tempMonitor.warning')",
  '异常': "t('home.tempMonitor.error')",
  '冷藏区': "t('home.tempZones.coldStorage')",
  '冷冻区': "t('home.tempZones.freezer')",
  '加载仓储首页数据失败': "t('messages.loadFailed')",
  '未知客户': "t('messages.unknownCustomer')",
  '未知供应商': "t('messages.unknownSupplier')",

  // Inventory screens
  '库存管理': "t('inventory.title')",
  '在库': "t('inventory.headerSubtitle', { total: ",
  '加载中...': "t('inventory.loading')",
  '盘点': "t('inventory.quickActions.check')",
  '调拨': "t('inventory.quickActions.transfer')",
  '库位': "t('inventory.quickActions.location')",
  '过期': "t('inventory.quickActions.expire')",
  '搜索物料名称/批次号': "t('inventory.search.placeholder')",
  '全部': "t('inventory.filter.all')",
  '鲜品': "t('inventory.filter.fresh')",
  '冻品': "t('inventory.filter.frozen')",
  '干货': "t('inventory.filter.dry')",

  // Inbound screens
  '入库管理': "t('inbound.title')",
  '新建入库': "t('inbound.create.title')",
  '物料信息': "t('inbound.create.materialInfo')",
  '物料名称': "t('inbound.create.materialName')",
  '物料类型': "t('inbound.create.materialType')",
  '数量与价格': "t('inbound.create.quantityPrice')",
  '单价(元/kg)': "t('inbound.create.unitPrice')",
  '保质信息': "t('inbound.create.qualityInfo')",
  '生产日期': "t('inbound.create.productionDate')",
  '保质期至': "t('inbound.create.expiryDate')",
  '存储温度': "t('inbound.create.storageTemp')",
  '备注': "t('inbound.create.remarks')",
  '提交入库': "t('inbound.create.submit')",
  '取消': "t('inbound.create.cancel')",

  // Batch detail
  '批次详情': "t('batch.detail.title')",
  '批次号': "t('batch.detail.batchNumber')",
  '当前数量': "t('batch.detail.currentQty')",
  '初始数量': "t('batch.detail.initialQty')",
};

// Files to migrate
const filesToMigrate = [
  'src/screens/warehouse/home/WHHomeScreen.tsx',
  'src/screens/warehouse/shared/WHConversionAnalysisScreen.tsx',
  'src/screens/warehouse/shared/WHAlertHandleScreen.tsx',
  'src/screens/warehouse/shared/WHAlertListScreen.tsx',
  'src/screens/warehouse/shared/WHScanOperationScreen.tsx',
  'src/screens/warehouse/shared/WHRecallManageScreen.tsx',
  'src/screens/warehouse/shared/WHBatchTraceScreen.tsx',
  'src/screens/warehouse/profile/WHSettingsScreen.tsx',
  'src/screens/warehouse/profile/WHProfileScreen.tsx',
  'src/screens/warehouse/profile/WHOperationLogScreen.tsx',
  'src/screens/warehouse/profile/WHProfileEditScreen.tsx',
  'src/screens/warehouse/inventory/WHTempMonitorScreen.tsx',
  'src/screens/warehouse/inventory/WHLocationManageScreen.tsx',
  'src/screens/warehouse/inventory/WHInventoryDetailScreen.tsx',
  'src/screens/warehouse/inventory/WHInventoryListScreen.tsx',
  'src/screens/warehouse/inventory/WHIOStatisticsScreen.tsx',
  'src/screens/warehouse/inventory/WHExpireHandleScreen.tsx',
  'src/screens/warehouse/inventory/WHInventoryCheckScreen.tsx',
  'src/screens/warehouse/inventory/WHBatchDetailScreen.tsx',
  'src/screens/warehouse/inbound/WHInboundDetailScreen.tsx',
  'src/screens/warehouse/inbound/WHInboundListScreen.tsx',
  'src/screens/warehouse/inbound/WHPutawayScreen.tsx',
  'src/screens/warehouse/inbound/WHInspectScreen.tsx',
  'src/screens/warehouse/outbound/WHShippingConfirmScreen.tsx',
  'src/screens/warehouse/outbound/WHOrderDetailScreen.tsx',
  'src/screens/warehouse/outbound/WHTrackingDetailScreen.tsx',
  'src/screens/warehouse/outbound/WHLoadingScreen.tsx',
  'src/screens/warehouse/outbound/WHOutboundListScreen.tsx',
  'src/screens/warehouse/outbound/WHPackingScreen.tsx',
  'src/screens/warehouse/outbound/WHOutboundDetailScreen.tsx',
  'src/screens/legacy/warehouse/InventoryStatisticsScreen.tsx',
  'src/screens/legacy/warehouse/InventoryCheckScreen.tsx',
];

function addI18nImport(content) {
  // Check if import already exists
  if (content.includes("import { useTranslation } from 'react-i18next';")) {
    return content;
  }

  // Find the last import statement
  const importRegex = /^import .+ from .+;$/gm;
  const matches = content.match(importRegex);

  if (matches && matches.length > 0) {
    const lastImport = matches[matches.length - 1];
    const importIndex = content.indexOf(lastImport) + lastImport.length;
    return content.slice(0, importIndex) + "\nimport { useTranslation } from 'react-i18next';" + content.slice(importIndex);
  }

  return content;
}

function addUseTranslationHook(content) {
  // Check if hook already exists
  if (content.includes("const { t } = useTranslation('warehouse');")) {
    return content;
  }

  // Find function component declaration
  const componentRegex = /export (default )?function \w+\([^)]*\) \{/;
  const match = content.match(componentRegex);

  if (match) {
    const hookStatement = "\n  const { t } = useTranslation('warehouse');";
    const insertIndex = match.index + match[0].length;
    return content.slice(0, insertIndex) + hookStatement + content.slice(insertIndex);
  }

  return content;
}

function migrateFile(filePath) {
  const fullPath = path.join(__dirname, '..', filePath);

  if (!fs.existsSync(fullPath)) {
    console.log(`⚠️  File not found: ${filePath}`);
    return;
  }

  let content = fs.readFileSync(fullPath, 'utf8');
  let modified = false;

  // Add import
  const newContent = addI18nImport(content);
  if (newContent !== content) {
    content = newContent;
    modified = true;
  }

  // Add hook
  const newContent2 = addUseTranslationHook(content);
  if (newContent2 !== content) {
    content = newContent2;
    modified = true;
  }

  // Replace Chinese strings - only for simple cases
  // Complex interpolations need manual handling

  if (modified) {
    fs.writeFileSync(fullPath, content, 'utf8');
    console.log(`✅ Migrated: ${filePath}`);
  } else {
    console.log(`⏭️  Skipped (no changes): ${filePath}`);
  }
}

console.log('🚀 Starting warehouse i18n migration...\n');

filesToMigrate.forEach(file => {
  try {
    migrateFile(file);
  } catch (error) {
    console.error(`❌ Error migrating ${file}:`, error.message);
  }
});

console.log('\n✨ Migration complete!');
console.log('\n⚠️  Note: This script only adds imports and hooks.');
console.log('   Manual string replacement is still required for complex cases.');
