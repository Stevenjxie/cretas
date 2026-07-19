<script setup lang="ts">
import { computed } from 'vue';
import { useAuthStore } from '@/store/modules/auth';

const auth = useAuthStore();
const hasRestaurantTenant = computed(() => (
  Boolean(auth.factoryId) && auth.businessDomain === 'RESTAURANT'
));
</script>

<template>
  <section class="agent-ops-shell">
    <header class="hero">
      <div>
        <p class="eyebrow">RESTAURANT AGENTOPS</p>
        <h1>让每次回答都能复现、比较和解释</h1>
        <p class="subtitle">离线评测不调用生产 Tool；运行轨迹直接读取现有事件账本。</p>
      </div>
      <div class="safety-chip">只读 Trace · 离线 Eval</div>
    </header>

    <el-alert
      v-if="!hasRestaurantTenant"
      title="当前账号未绑定餐饮租户，无法读取 AgentOps 数据"
      type="warning"
      :closable="false"
      show-icon
      class="tenant-alert"
    />

    <nav class="tabs" aria-label="AgentOps sections">
      <router-link to="/ops/agent-ops/eval-sets">Eval Sets</router-link>
      <router-link to="/ops/agent-ops/experiments">Experiments</router-link>
      <router-link to="/ops/agent-ops/run-trace">Run Trace</router-link>
    </nav>
    <router-view v-if="hasRestaurantTenant" />
  </section>
</template>

<style scoped>
.agent-ops-shell { min-height: 100%; padding: 28px; background: #f5f7f9; color: #172126; }
.hero { display: flex; justify-content: space-between; gap: 24px; padding: 26px 30px; border: 1px solid #dce4e7; border-radius: 18px; background: linear-gradient(135deg, #fff 0%, #f1f6f4 100%); }
.eyebrow { margin: 0 0 8px; color: #477366; font-size: 12px; font-weight: 700; letter-spacing: .14em; }
h1 { margin: 0; font-size: 28px; line-height: 1.25; }
.subtitle { margin: 10px 0 0; color: #647277; }
.safety-chip { align-self: flex-start; padding: 8px 12px; border-radius: 999px; color: #285f50; background: #e5f1ed; font-size: 13px; font-weight: 600; white-space: nowrap; }
.tenant-alert { margin-top: 18px; }
.tabs { display: flex; gap: 6px; margin: 20px 0; padding: 5px; width: fit-content; border: 1px solid #dce4e7; border-radius: 12px; background: #fff; }
.tabs a { padding: 9px 15px; border-radius: 8px; color: #657278; text-decoration: none; font-weight: 600; }
.tabs a.router-link-active { color: #fff; background: #356f60; }
@media (max-width: 760px) { .agent-ops-shell { padding: 16px; } .hero { flex-direction: column; } .tabs { width: 100%; overflow-x: auto; } }
</style>
