<!--
  QrCodeDisplay.vue — Sprint 9 P1.2 Fix 3

  通用 QR 码显示组件 (Vue 3 + canvas rendering, 复用 node-qrcode 包 已在 package.json).

  使用场景:
  - 仓管员 PDA 扫码 (Sprint 8 P4.1 WarehouseKeeperWorkdesk: token URL → 真 QR)
  - 采购订单签收扫码 (Sprint 7+ PurchaseOrderPdf 已嵌 QR in PDF)
  - 未来: 任何 token / URL / payload 需扫码识别

  Why canvas (而非 SVG): canvas 在 PDA 摄像头 / 微信扫一扫识别率更稳; SVG 在某些 PDA 老固件
  上扫描失败.

  Why qrcode (vs qrcode.vue / qrcode-vue3): qrcode 是底层 lib, 包大小 ~30KB, 已是 web-admin
  现有依赖 (Sprint 5 引入, 见 SmartBI binding); 包装一层 Vue 组件零新增 dep 风险.
-->
<template>
  <div class="qr-code-display">
    <canvas
      ref="canvasRef"
      class="qr-canvas"
      :style="{ width: size + 'px', height: size + 'px' }" />
    <div v-if="renderError" class="qr-error">
      <el-text type="danger" size="small">{{ renderError }}</el-text>
    </div>
    <div v-else-if="showLabel" class="qr-label">
      <el-text size="small" type="info">{{ labelText || value }}</el-text>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch, nextTick } from 'vue';
import QRCode from 'qrcode';

interface Props {
  /** Payload to encode (URL, token, JSON, plain text) */
  value: string;
  /** Pixel size of the rendered canvas. Default 200. */
  size?: number;
  /** Show the value (or labelText) as caption below the QR. Default true. */
  showLabel?: boolean;
  /** Override caption text (defaults to {value}). */
  labelText?: string;
  /** QR error correction level: 'L' (7%) | 'M' (15%) | 'Q' (25%) | 'H' (30%). Default 'M'. */
  errorCorrectionLevel?: 'L' | 'M' | 'Q' | 'H';
  /** Margin in modules around QR. Default 1. */
  margin?: number;
}

const props = withDefaults(defineProps<Props>(), {
  size: 200,
  showLabel: true,
  labelText: '',
  errorCorrectionLevel: 'M',
  margin: 1,
});

const canvasRef = ref<HTMLCanvasElement | null>(null);
const renderError = ref('');

async function renderQr() {
  renderError.value = '';
  await nextTick();
  if (!canvasRef.value) {
    return;
  }
  if (!props.value || props.value.trim().length === 0) {
    renderError.value = 'QR 内容为空';
    return;
  }
  try {
    await QRCode.toCanvas(canvasRef.value, props.value, {
      width: props.size,
      margin: props.margin,
      errorCorrectionLevel: props.errorCorrectionLevel,
      color: {
        dark: '#000000',
        light: '#ffffff',
      },
    });
  } catch (err: unknown) {
    const msg = err instanceof Error ? err.message : String(err);
    renderError.value = `QR 生成失败: ${msg}`;
    // eslint-disable-next-line no-console
    console.error('[QrCodeDisplay] render failed:', err);
  }
}

onMounted(renderQr);

watch(
  () => [props.value, props.size, props.errorCorrectionLevel, props.margin],
  renderQr,
);
</script>

<style scoped>
.qr-code-display {
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.qr-canvas {
  display: block;
  background: #fff;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
}

.qr-label {
  max-width: 240px;
  text-align: center;
  word-break: break-all;
}

.qr-error {
  padding: 8px;
  border: 1px dashed #f56c6c;
  border-radius: 4px;
  background: #fef0f0;
}
</style>
