<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post, del } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Refresh, Connection, Delete } from '@element-plus/icons-vue';
import type { TableRow } from '@/types/api';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('system'));

const loading = ref(false);
const connections = ref<TableRow[]>([]);
const dialogVisible = ref(false);
const testLoading = ref<Record<string, boolean>>({});
const syncLoading = ref<Record<string, boolean>>({});

const brandMap: Record<string, { name: string; color: string }> = {
  KERUYUN: { name: '客如云', color: '#ff6600' },
  ERWEIHUO: { name: '二维火', color: '#e63946' },
  YINBAO: { name: '银豹', color: '#2a9d8f' },
  MEITUAN: { name: '美团', color: '#ffd60a' },
  HUALALA: { name: '哗啦啦', color: '#457b9d' },
};

const form = ref({
  brand: 'KERUYUN',
  connectionName: '',
  appKey: '',
  appSecret: '',
  posStoreId: '',
  remark: '',
});

onMounted(() => loadConnections());

async function loadConnections() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await get(`/${factoryId.value}/pos/connections`);
    if (res.success) {
      connections.value = Array.isArray(res.data) ? res.data : [];
    } else {
      ElMessage.error(res.message || '加载失败');
    }
  } catch { /* axios interceptor already displayed error toast */ }
  finally { loading.value = false; }
}

function resetForm() {
  form.value = { brand: 'KERUYUN', connectionName: '', appKey: '', appSecret: '', posStoreId: '', remark: '' };
}

async function handleCreate() {
  if (!form.value.connectionName?.trim()) return ElMessage.warning('请填写连接名称');
  if (!form.value.appKey?.trim()) return ElMessage.warning('请填写 App Key');
  try {
    const res = await post(`/${factoryId.value}/pos/connections`, form.value);
    if (res.success) { ElMessage.success('创建成功'); resetForm(); dialogVisible.value = false; loadConnections(); }
    else { ElMessage.error(res.message || '创建失败'); }
  } catch { /* axios interceptor already displayed error toast */ }
}

async function handleDelete(id: string) {
  try {
    await ElMessageBox.confirm('确认删除此POS连接？', '提示');
    const res = await del(`/${factoryId.value}/pos/connections/${id}`);
    if (res.success) { ElMessage.success('删除成功'); loadConnections(); }
    else { ElMessage.error(res.message || '删除失败'); }
  } catch (e) {
    // Interceptor shows specific toast; dedupe fallback
    if (e !== 'cancel') console.error('[失败]', e);
  }
}

async function handleToggle(id: string, active: boolean) {
  try {
    const res = await post(`/${factoryId.value}/pos/connections/${id}/toggle`, undefined, { params: { active } } as TableRow);
    if (res.success) { ElMessage.success(active ? '已启用' : '已停用'); loadConnections(); }
    else { ElMessage.error(res.message || '操作失败'); }
  } catch { /* axios interceptor already displayed error toast */ }
}

async function handleTest(id: string) {
  testLoading.value[id] = true;
  try {
    const res = await post(`/${factoryId.value}/pos/connections/${id}/test`);
    if (res.success) {
      ElMessage.success('连接测试成功');
    } else {
      ElMessage.error(res.message || '连接测试失败');
    }
  } catch { /* axios interceptor already displayed error toast */ }
  finally { testLoading.value[id] = false; }
}

async function handleSync(id: string) {
  syncLoading.value[id] = true;
  try {
    const res = await post(`/${factoryId.value}/pos/connections/${id}/sync`);
    if (res.success) {
      const count = Array.isArray(res.data) ? res.data.length : 0;
      ElMessage.success(`同步完成，获取 ${count} 条订单`);
    } else {
      ElMessage.error(res.message || '同步失败');
    }
  } catch { /* axios interceptor already displayed error toast */ }
  finally { syncLoading.value[id] = false; }
}
</script>

<template>
  <div class="page-wrapper">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">POS集成管理</span>
            <span class="data-count">{{ connections.length }} 个连接</span>
          </div>
          <div class="header-right">
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="dialogVisible = true">新建连接</el-button>
            <el-button :icon="Refresh" @click="loadConnections">刷新</el-button>
          </div>
        </div>
      </template>

      <div v-if="connections.length === 0 && !loading" class="pos-onboarding">
        <div class="onboarding-hero">
          <div class="hero-title">接入你的 POS 系统</div>
          <div class="hero-desc">
            配置完成后, 系统会自动同步 POS 订单、菜品、门店数据到 SmartBI,
            你就可以在 <b>经营驾驶舱</b>、<b>菜品四象限</b>、<b>门店对比</b> 等看板中看到实时分析。
          </div>
          <el-button v-if="canWrite" type="primary" size="large" :icon="Plus"
            @click="dialogVisible = true" style="margin-top: 16px">
            立即接入 POS
          </el-button>
          <el-alert v-if="!canWrite" type="info" :closable="false" style="margin-top: 16px; max-width: 500px">
            当前账号无 POS 集成权限, 请联系工厂管理员
          </el-alert>
        </div>

        <div class="brand-list">
          <div class="brand-list-title">支持接入以下 POS 品牌:</div>
          <div class="brand-grid">
            <div v-for="(v, k) in brandMap" :key="k" class="brand-card">
              <div class="brand-tag" :style="{ backgroundColor: v.color }">{{ v.name }}</div>
              <div class="brand-hint">{{
                k === 'KERUYUN' ? '适合中大型连锁' :
                k === 'ERWEIHUO' ? '适合快餐/茶饮' :
                k === 'YINBAO' ? '适合小型餐厅' :
                k === 'MEITUAN' ? '美团外卖/点评订单' :
                '大型连锁定制'
              }}</div>
            </div>
          </div>
          <div class="brand-faq">
 <div> 需要在 POS 厂商后台申请 App Key / App Secret 凭证</div>
 <div> 集成后每 15 分钟自动同步, 也可手动触发"同步"按钮</div>
 <div> 如未看到你用的 POS 品牌, 请提工单支持对接</div>
          </div>
        </div>
      </div>

      <div v-else class="connection-grid">
        <el-card v-for="conn in connections" :key="conn.id" class="conn-card" shadow="hover">
          <div class="conn-header">
            <div class="conn-brand">
              <el-tag :color="brandMap[conn.brand]?.color || '#909399'" effect="dark" size="small">
                {{ brandMap[conn.brand]?.name || conn.brand }}
              </el-tag>
              <span class="conn-name">{{ conn.connectionName }}</span>
            </div>
            <el-switch v-if="canWrite" :model-value="conn.isActive" @change="(val: boolean) => handleToggle(conn.id, val)" />
          </div>

          <el-descriptions :column="2" size="small" border style="margin-top: 12px">
            <el-descriptions-item label="App Key">{{ conn.appKey ? '***' + conn.appKey.slice(-4) : '-' }}</el-descriptions-item>
            <el-descriptions-item label="门店ID">{{ conn.posStoreId || '-' }}</el-descriptions-item>
            <el-descriptions-item label="最近同步">{{ conn.lastSyncAt ? conn.lastSyncAt.replace('T', ' ').substring(0, 19) : '从未同步' }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="conn.isActive ? 'success' : 'info'" size="small">
                {{ conn.isActive ? '已启用' : '已停用' }}
              </el-tag>
            </el-descriptions-item>
          </el-descriptions>

          <div v-if="conn.lastError" class="conn-error">
            <el-alert :title="conn.lastError" type="error" :closable="false" show-icon />
          </div>

          <div class="conn-actions" v-if="canWrite">
            <el-button type="primary" size="small" :icon="Connection" :loading="testLoading[conn.id]"
              @click="handleTest(conn.id)">测试连接</el-button>
            <el-button type="warning" size="small" :loading="syncLoading[conn.id]"
              @click="handleSync(conn.id)">手动同步</el-button>
            <el-button type="danger" size="small" :icon="Delete"
              @click="handleDelete(conn.id)">删除</el-button>
          </div>
        </el-card>
      </div>
    </el-card>

    <el-dialog v-model="dialogVisible" title="新建POS连接" width="520px" destroy-on-close>
      <el-form :model="form" label-width="100px">
        <el-form-item label="POS品牌">
          <el-select v-model="form.brand" style="width: 100%">
            <el-option v-for="(v, k) in brandMap" :key="k" :label="v.name" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="连接名称" required><el-input v-model="form.connectionName" placeholder="如：白垩纪-客如云POS" /></el-form-item>
        <el-form-item label="App Key" required><el-input v-model="form.appKey" /></el-form-item>
        <el-form-item label="App Secret"><el-input v-model="form.appSecret" type="password" show-password /></el-form-item>
        <el-form-item label="门店ID"><el-input v-model="form.posStoreId" placeholder="POS系统中的门店标识" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.page-wrapper { height: 100%; width: 100%; display: flex; flex-direction: column; }
.page-card { flex: 1; display: flex; flex-direction: column;
  :deep(.el-card__header) { padding: 16px 20px; border-bottom: 1px solid #ebeef5; }
  :deep(.el-card__body) { flex: 1; padding: 20px; overflow-y: auto; }
}
.card-header { display: flex; justify-content: space-between; align-items: center;
  .header-left { display: flex; align-items: baseline; gap: 12px;
    .page-title { font-size: 16px; font-weight: 600; color: #303133; }
    .data-count { font-size: 13px; color: #909399; }
  }
  .header-right { display: flex; gap: 8px; }
}
.connection-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(480px, 100%), 1fr)); gap: 16px; }
.conn-card {
  .conn-header { display: flex; justify-content: space-between; align-items: center; }
  .conn-brand { display: flex; align-items: center; gap: 8px;
    .conn-name { font-weight: 600; font-size: 15px; }
  }
  .conn-error { margin-top: 12px; }
  .conn-actions { display: flex; gap: 8px; margin-top: 16px; padding-top: 12px; border-top: 1px solid #ebeef5; }
}

// Empty-state onboarding (Apr 24 2026 — P0-C: rich entry replaces el-empty)
.pos-onboarding {
  padding: 32px 20px 40px;
  .onboarding-hero {
    text-align: center;
    padding-bottom: 28px;
    border-bottom: 1px dashed #ebeef5;
    .hero-title { font-size: 20px; font-weight: 600; color: #303133; margin-bottom: 12px; }
    .hero-desc { font-size: 14px; color: #606266; line-height: 1.8; max-width: 680px; margin: 0 auto; b { color: #409eff; } }
  }
  .brand-list {
    margin-top: 24px;
    .brand-list-title { font-size: 14px; font-weight: 600; color: #303133; margin-bottom: 14px; }
    .brand-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 12px; margin-bottom: 20px; }
    .brand-card { border: 1px solid #ebeef5; border-radius: 6px; padding: 14px; background: #fafafa;
      .brand-tag { display: inline-block; color: white; padding: 3px 10px; border-radius: 4px; font-size: 13px; font-weight: 600; margin-bottom: 8px; }
      .brand-hint { font-size: 12px; color: #909399; line-height: 1.5; }
    }
    .brand-faq { font-size: 12px; color: #909399; line-height: 1.9; padding-top: 12px; border-top: 1px solid #f5f5f5; }
  }
}
</style>
