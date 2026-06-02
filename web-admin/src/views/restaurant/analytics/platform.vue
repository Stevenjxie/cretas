<script setup lang="ts">
import { useRouter } from 'vue-router';
import { DataLine } from '@element-plus/icons-vue';
const router = useRouter();
function goUpload() { router.push('/smart-bi/upload'); }
</script>

<template>
  <div class="restaurant-platform">
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      title="本页需接入大众点评 / 美团平台数据"
      description="当前未接入平台 API，暂无法自动同步平台评分与排名。可手动上传点评导出文件进行分析，或等待平台 API 接入后自动同步。"
      class="platform-banner"
    />

    <el-empty description="平台口碑数据未接入">
      <template #image>
        <el-icon :size="64"><DataLine /></el-icon>
      </template>
      <div class="platform-actions">
        <el-button type="primary" @click="goUpload">上传点评导出文件分析</el-button>
        <p class="platform-hint">
          导出大众点评/美团后台的评分、评论数据为 Excel，上传后由 AI 分析口碑趋势与改进建议。
          平台 API 自动同步为后续规划项。
        </p>
      </div>
    </el-empty>
  </div>
</template>

<style scoped>
.restaurant-platform { padding: 16px; }
.platform-banner { margin-bottom: 24px; }
.platform-actions { text-align: center; margin-top: 12px; }
.platform-hint { color: #909399; font-size: 13px; margin-top: 12px; max-width: 480px; }
</style>
