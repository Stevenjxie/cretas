<!-- ReviewDialog.vue — approve/reject confirmation -->
<template>
  <el-dialog :title="mode === 'approve' ? '审核通过' : '驳回配置'" model-value width="450px" @close="$emit('cancel')">
    <el-form label-width="80px">
      <el-form-item :label="mode === 'approve' ? '备注' : '驳回原因'" :required="mode === 'reject'">
        <el-input v-model="notes" type="textarea" :rows="3" :placeholder="mode === 'approve' ? '可选备注' : '请填写驳回原因'" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="$emit('cancel')">取消</el-button>
      <el-button :type="mode === 'approve' ? 'success' : 'danger'" :disabled="mode === 'reject' && !notes.trim()" @click="$emit('confirm', notes)">
 {{ mode === 'approve' ? ' 确认通过' : ' 确认驳回' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
defineProps<{ mode: 'approve' | 'reject' }>()
defineEmits<{ confirm: [notes: string]; cancel: [] }>()
const notes = ref('')
</script>
