<!-- OnboardingStep1Template.vue -->
<template>
  <div class="step-templates">
    <h3>选择行业模板</h3>
    <p class="step-desc">选择最接近你工厂行业的模板，AI 将自动配置模块和默认值。</p>
    <div class="template-grid">
      <div
        v-for="tpl in templates" :key="tpl.templateCode"
        class="template-card" :class="{ selected: selected === tpl.templateCode }"
        @click="$emit('update:selected', tpl.templateCode)"
      >
        <div class="tpl-icon">{{ tpl.icon }}</div>
        <div class="tpl-name">{{ tpl.templateName }}</div>
        <div class="tpl-industry">{{ tpl.industryType }}</div>
        <div class="tpl-desc">{{ tpl.description }}</div>
      </div>
      <div class="template-card" :class="{ selected: selected === 'BLANK' }" @click="$emit('update:selected', 'BLANK')">
        <div class="tpl-icon"></div>
        <div class="tpl-name">空白配置</div>
        <div class="tpl-industry">自定义</div>
        <div class="tpl-desc">从零开始，手动配置所有模块</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { getTemplates } from '@/api/canvasApi'

const props = defineProps<{ factoryId: string; selected: string | null }>()
defineEmits<{ 'update:selected': [code: string] }>()

const templates = ref<any[]>([])
const icons: Record<string, string> = { FOOD_PROCESSING: '', BAKERY: '', RESTAURANT: '', AQUACULTURE: '' }

onMounted(async () => {
  try {
    // Prior code hardcoded 'F001' with "factory-agnostic" comment, but backend
    // RBAC rejects cross-factory reads → 403 for every non-F001 user. Use the
    // caller's factoryId (which backend *can* authorize). If templates are truly
    // meant to be global, that's a backend API design change, not a frontend hack.
    const res = await getTemplates(props.factoryId)
    templates.value = (res.data || []).map((t: any) => ({ ...t, icon: icons[t.templateCode] || '' }))
  } catch { /* use empty */ }
})
</script>

<style scoped>
.step-templates h3 { margin-bottom: 4px; }
.step-desc { color: var(--el-text-color-secondary); font-size: 13px; margin-bottom: 20px; }
.template-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 16px; }
.template-card {
  border: 2px solid var(--el-border-color); border-radius: 10px; padding: 16px;
  cursor: pointer; transition: all 0.2s; text-align: center;
}
.template-card:hover { border-color: var(--el-color-primary-light-3); }
.template-card.selected { border-color: var(--el-color-primary); background: var(--el-color-primary-light-9); }
.tpl-icon { font-size: 32px; margin-bottom: 8px; }
.tpl-name { font-weight: bold; font-size: 15px; }
.tpl-industry { color: var(--el-color-primary); font-size: 12px; margin: 4px 0; }
.tpl-desc { font-size: 12px; color: var(--el-text-color-secondary); }
</style>
