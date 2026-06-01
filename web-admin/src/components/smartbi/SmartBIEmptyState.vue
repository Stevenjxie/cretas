<template>
  <div class="smartbi-empty-state">
    <div class="empty-illustration">
      <svg v-if="type === 'no-data'" width="120" height="120" viewBox="0 0 120 120" fill="none" xmlns="http://www.w3.org/2000/svg">
        <defs>
          <linearGradient id="grad-no-data" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" style="stop-color:#667eea;stop-opacity:1" />
            <stop offset="100%" style="stop-color:#764ba2;stop-opacity:1" />
          </linearGradient>
        </defs>
        <!-- Pie chart with missing slice -->
        <circle cx="60" cy="60" r="35" fill="none" stroke="#e4e7ed" stroke-width="2" stroke-dasharray="4 4" opacity="0.5"/>
        <path d="M 60 25 A 35 35 0 0 1 87.5 75 L 60 60 Z" fill="url(#grad-no-data)" opacity="0.3"/>
        <path d="M 60 25 A 35 35 0 0 0 32.5 75 L 60 60 Z" fill="url(#grad-no-data)" opacity="0.6"/>
        <path d="M 60 60 L 32.5 75 A 35 35 0 0 0 60 95 Z" fill="url(#grad-no-data)" opacity="0.4"/>
        <!-- Missing slice indicator -->
        <path d="M 60 60 L 87.5 75 A 35 35 0 0 0 95 60" stroke="#dcdfe6" stroke-width="2" stroke-dasharray="3 3" fill="none"/>
      </svg>

      <svg v-else-if="type === 'no-upload'" width="120" height="120" viewBox="0 0 120 120" fill="none" xmlns="http://www.w3.org/2000/svg">
        <defs>
          <linearGradient id="grad-upload" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" style="stop-color:#667eea;stop-opacity:1" />
            <stop offset="100%" style="stop-color:#764ba2;stop-opacity:1" />
          </linearGradient>
        </defs>
        <!-- Document -->
        <rect x="35" y="50" width="50" height="60" rx="4" fill="url(#grad-upload)" opacity="0.2"/>
        <rect x="35" y="50" width="50" height="60" rx="4" fill="none" stroke="url(#grad-upload)" stroke-width="2"/>
        <line x1="45" y1="65" x2="75" y2="65" stroke="url(#grad-upload)" stroke-width="2" opacity="0.4"/>
        <line x1="45" y1="75" x2="75" y2="75" stroke="url(#grad-upload)" stroke-width="2" opacity="0.4"/>
        <line x1="45" y1="85" x2="65" y2="85" stroke="url(#grad-upload)" stroke-width="2" opacity="0.4"/>
        <!-- Upload arrow -->
        <path d="M 60 20 L 60 45" stroke="url(#grad-upload)" stroke-width="3" stroke-linecap="round"/>
        <path d="M 50 30 L 60 20 L 70 30" stroke="url(#grad-upload)" stroke-width="3" stroke-linecap="round" fill="none"/>
      </svg>

      <svg v-else-if="type === 'no-charts'" width="120" height="120" viewBox="0 0 120 120" fill="none" xmlns="http://www.w3.org/2000/svg">
        <defs>
          <linearGradient id="grad-charts" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" style="stop-color:#667eea;stop-opacity:1" />
            <stop offset="100%" style="stop-color:#764ba2;stop-opacity:1" />
          </linearGradient>
        </defs>
        <!-- Bar chart growing -->
        <rect x="25" y="70" width="15" height="25" rx="2" fill="url(#grad-charts)" opacity="0.3">
          <animate attributeName="height" values="10;25;10" dur="2s" repeatCount="indefinite"/>
          <animate attributeName="y" values="85;70;85" dur="2s" repeatCount="indefinite"/>
        </rect>
        <rect x="45" y="55" width="15" height="40" rx="2" fill="url(#grad-charts)" opacity="0.5">
          <animate attributeName="height" values="20;40;20" dur="2s" begin="0.3s" repeatCount="indefinite"/>
          <animate attributeName="y" values="75;55;75" dur="2s" begin="0.3s" repeatCount="indefinite"/>
        </rect>
        <rect x="65" y="40" width="15" height="55" rx="2" fill="url(#grad-charts)" opacity="0.7">
          <animate attributeName="height" values="30;55;30" dur="2s" begin="0.6s" repeatCount="indefinite"/>
          <animate attributeName="y" values="65;40;65" dur="2s" begin="0.6s" repeatCount="indefinite"/>
        </rect>
        <rect x="85" y="25" width="15" height="70" rx="2" fill="url(#grad-charts)">
          <animate attributeName="height" values="40;70;40" dur="2s" begin="0.9s" repeatCount="indefinite"/>
          <animate attributeName="y" values="55;25;55" dur="2s" begin="0.9s" repeatCount="indefinite"/>
        </rect>
      </svg>

      <svg v-else-if="type === 'no-analysis'" width="120" height="120" viewBox="0 0 120 120" fill="none" xmlns="http://www.w3.org/2000/svg">
        <defs>
          <linearGradient id="grad-analysis" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" style="stop-color:#667eea;stop-opacity:1" />
            <stop offset="100%" style="stop-color:#764ba2;stop-opacity:1" />
          </linearGradient>
        </defs>
        <!-- Brain/Gear combo -->
        <circle cx="60" cy="60" r="30" fill="url(#grad-analysis)" opacity="0.2"/>
        <path d="M 45 50 Q 50 45, 55 50 T 65 50 Q 70 45, 75 50" stroke="url(#grad-analysis)" stroke-width="2" fill="none"/>
        <path d="M 45 65 Q 50 70, 55 65 T 65 65 Q 70 70, 75 65" stroke="url(#grad-analysis)" stroke-width="2" fill="none"/>
        <circle cx="55" cy="58" r="3" fill="url(#grad-analysis)"/>
        <circle cx="65" cy="58" r="3" fill="url(#grad-analysis)"/>
        <!-- Sparkles -->
        <path d="M 30 35 L 32 40 L 37 42 L 32 44 L 30 49 L 28 44 L 23 42 L 28 40 Z" fill="url(#grad-analysis)" opacity="0.6">
          <animate attributeName="opacity" values="0.3;0.8;0.3" dur="1.5s" repeatCount="indefinite"/>
        </path>
        <path d="M 85 30 L 87 35 L 92 37 L 87 39 L 85 44 L 83 39 L 78 37 L 83 35 Z" fill="url(#grad-analysis)" opacity="0.6">
          <animate attributeName="opacity" values="0.6;1;0.6" dur="1.8s" repeatCount="indefinite"/>
        </path>
        <path d="M 90 75 L 91 78 L 94 79 L 91 80 L 90 83 L 89 80 L 86 79 L 89 78 Z" fill="url(#grad-analysis)" opacity="0.5">
          <animate attributeName="opacity" values="0.3;0.7;0.3" dur="1.2s" repeatCount="indefinite"/>
        </path>
      </svg>

      <svg v-else-if="type === 'error'" width="120" height="120" viewBox="0 0 120 120" fill="none" xmlns="http://www.w3.org/2000/svg">
        <defs>
          <linearGradient id="grad-error" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" style="stop-color:#f56c6c;stop-opacity:1" />
            <stop offset="100%" style="stop-color:#e6a23c;stop-opacity:1" />
          </linearGradient>
        </defs>
        <!-- Warning triangle -->
        <path d="M 60 20 L 95 90 L 25 90 Z" fill="url(#grad-error)" opacity="0.2"/>
        <path d="M 60 20 L 95 90 L 25 90 Z" stroke="url(#grad-error)" stroke-width="3" fill="none"/>
        <!-- Exclamation mark -->
        <line x1="60" y1="45" x2="60" y2="65" stroke="url(#grad-error)" stroke-width="4" stroke-linecap="round"/>
        <circle cx="60" cy="75" r="3" fill="url(#grad-error)"/>
        <!-- Retry arrow -->
        <path d="M 75 50 A 15 15 0 1 1 70 35" stroke="url(#grad-error)" stroke-width="2" fill="none" opacity="0.5"/>
        <path d="M 75 50 L 78 45 L 72 47 Z" fill="url(#grad-error)" opacity="0.5"/>
      </svg>

      <svg v-else-if="type === 'read-only'" width="120" height="120" viewBox="0 0 120 120" fill="none" xmlns="http://www.w3.org/2000/svg">
        <defs>
          <linearGradient id="grad-readonly" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" style="stop-color:#409eff;stop-opacity:1" />
            <stop offset="100%" style="stop-color:#667eea;stop-opacity:1" />
          </linearGradient>
        </defs>
        <!-- Eye/view icon -->
        <ellipse cx="60" cy="60" rx="30" ry="20" fill="url(#grad-readonly)" opacity="0.2"/>
        <ellipse cx="60" cy="60" rx="30" ry="20" fill="none" stroke="url(#grad-readonly)" stroke-width="2.5"/>
        <circle cx="60" cy="60" r="10" fill="url(#grad-readonly)" opacity="0.4"/>
        <circle cx="60" cy="60" r="5" fill="url(#grad-readonly)"/>
        <!-- Lock indicator -->
        <rect x="78" y="72" width="18" height="14" rx="3" fill="url(#grad-readonly)" opacity="0.7"/>
        <path d="M 82 72 V 67 A 5 5 0 0 1 92 67 V 72" fill="none" stroke="url(#grad-readonly)" stroke-width="2" opacity="0.7"/>
        <circle cx="87" cy="79" r="2" fill="white"/>
      </svg>

      <svg v-else-if="type === 'loading-failed'" width="120" height="120" viewBox="0 0 120 120" fill="none" xmlns="http://www.w3.org/2000/svg">
        <defs>
          <linearGradient id="grad-failed" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" style="stop-color:#909399;stop-opacity:1" />
            <stop offset="100%" style="stop-color:#c0c4cc;stop-opacity:1" />
          </linearGradient>
        </defs>
        <!-- Broken chain -->
        <ellipse cx="45" cy="50" rx="12" ry="15" fill="none" stroke="url(#grad-failed)" stroke-width="3"/>
        <ellipse cx="75" cy="70" rx="12" ry="15" fill="none" stroke="url(#grad-failed)" stroke-width="3"/>
        <!-- Break indicator -->
        <line x1="52" y1="55" x2="60" y2="60" stroke="url(#grad-failed)" stroke-width="3" stroke-linecap="round" opacity="0.3"/>
        <line x1="60" y1="60" x2="68" y2="65" stroke="url(#grad-failed)" stroke-width="3" stroke-linecap="round" opacity="0.3"/>
        <path d="M 58 58 L 62 62 M 62 58 L 58 62" stroke="#f56c6c" stroke-width="2" stroke-linecap="round"/>
      </svg>
    </div>

    <div class="empty-content">
      <h3 class="empty-title">{{ computedTitle }}</h3>
      <p class="empty-description">{{ computedDescription }}</p>

      <el-button
        v-if="showAction"
        type="primary"
        :icon="computedActionIcon"
        @click="handleAction"
        class="empty-action"
      >
        {{ computedActionText }}
      </el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { Upload, RefreshRight, Connection, Search, DocumentAdd } from '@element-plus/icons-vue';

interface Props {
  type: 'no-data' | 'no-upload' | 'no-charts' | 'no-analysis' | 'error' | 'loading-failed' | 'read-only';
  title?: string;
  description?: string;
  showAction?: boolean;
  actionText?: string;
  actionIcon?: ReturnType<typeof import('vue')['defineComponent']>;
}

const props = withDefaults(defineProps<Props>(), {
  showAction: true,
});

const emit = defineEmits<{
  action: [];
}>();

const defaultContent = {
  'no-data': {
    title: '暂无数据',
    description: '当前没有可分析的数据，请先上传Excel文件',
    actionText: '上传文件',
    actionIcon: Upload,
  },
  'no-upload': {
    title: '等待上传',
    description: '拖拽Excel文件到上方区域或点击上传按钮开始',
    actionText: '选择文件',
    actionIcon: DocumentAdd,
  },
  'no-charts': {
    title: '暂无图表',
    description: '数据正在分析中，图表即将生成...',
    actionText: '刷新分析',
    actionIcon: RefreshRight,
  },
  'no-analysis': {
    // 终态空状态 (非加载中): LLM 洞察返回空时显示。文案须诚实+给 next-action,
    // 不要用"准备中/正在生成"这类 loading 语气 (会让用户误以为一直在转圈)。
    title: '暂无 AI 洞察',
    description: '当前时间范围内数据不足以生成智能洞察，可调整上方时间范围或上传更多经营数据后重试',
    actionText: '调整时间范围',
    actionIcon: Search,
  },
  'error': {
    title: '加载失败',
    description: '数据加载遇到问题，请检查网络连接后重试',
    actionText: '重新加载',
    actionIcon: RefreshRight,
  },
  'read-only': {
    title: '暂无分析数据',
    description: '请联系管理员上传 Excel 文件后查看分析结果',
    actionText: '查看帮助',
    actionIcon: Search,
  },
  'loading-failed': {
    title: '服务不可用',
    description: 'AI分析服务暂时无法连接，请稍后再试',
    actionText: '检查连接',
    actionIcon: Connection,
  },
};

const computedTitle = computed(() => props.title || defaultContent[props.type].title);
const computedDescription = computed(() => props.description || defaultContent[props.type].description);
const computedActionText = computed(() => props.actionText || defaultContent[props.type].actionText);
const computedActionIcon = computed(() => props.actionIcon || defaultContent[props.type].actionIcon);

const handleAction = () => {
  emit('action');
};
</script>

<style scoped lang="scss">
.smartbi-empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 20px;
  min-height: 400px;
  background: linear-gradient(135deg,
    rgba(102, 126, 234, 0.03) 0%,
    rgba(118, 75, 162, 0.03) 100%);
  border-radius: 8px;
  animation: fadeIn 0.5s ease-in-out;
}

@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.empty-illustration {
  margin-bottom: 24px;
  animation: float 3s ease-in-out infinite;
}

@keyframes float {
  0%, 100% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(-10px);
  }
}

.empty-content {
  text-align: center;
  max-width: 500px;
}

.empty-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--el-text-color-primary, #303133);
  margin: 0 0 12px 0;
  line-height: 1.4;
}

.empty-description {
  font-size: 14px;
  color: var(--el-text-color-secondary, #909399);
  margin: 0 0 24px 0;
  line-height: 1.6;
  max-width: 400px;
  margin-left: auto;
  margin-right: auto;
}

.empty-action {
  margin-top: 8px;
  padding: 12px 32px;
  font-size: 14px;
  transition: all 0.3s ease;

  &:hover {
    transform: translateY(-2px);
    box-shadow: 0 4px 12px rgba(102, 126, 234, 0.3);
  }
}

/* ==================== Dark Mode ==================== */
:global([data-theme="dark"]) .smartbi-empty-state {
  background: linear-gradient(135deg,
    rgba(102, 126, 234, 0.06) 0%,
    rgba(118, 75, 162, 0.06) 100%);
}
</style>
