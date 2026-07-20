/**
 * 附件通用组件 — C-ATT-1.
 *
 * 详情页用法:
 *   <script setup>
 *   import AttachmentList from '@/components/attachment/AttachmentList.vue';
 *   import AttachmentUploadButton from '@/components/attachment/AttachmentUploadButton.vue';
 *   const refreshKey = ref(0);
 *   </script>
 *   <AttachmentList :entity-type="'PURCHASE_ORDER'" :entity-id="order.id" :refresh-key="refreshKey" />
 *   <AttachmentUploadButton :entity-type="'PURCHASE_ORDER'" :entity-id="order.id" @uploaded="refreshKey++" />
 */
export { default as AttachmentList } from './AttachmentList.vue';
export { default as AttachmentUploadButton } from './AttachmentUploadButton.vue';
export { default as AttachmentDropZone } from './AttachmentDropZone.vue';
