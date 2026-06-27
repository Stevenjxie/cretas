<!--
  ConceptDisambiguationAlert — 概念辨析提示横幅

  UX P1 (张权 2026-04-29 反馈): 用户混淆"产品类型"vs"原料类型",
  把采购入库的物料建到了产品里, 导致采购订单 dropdown 空.
  系统里有多对类似歧义概念 (客户/供应商, 销售/采购订单, 生产计划/生产批次,
  入库/出库/调拨, BOM/转换率, 盘点/调拨...), 每个易混页面都需要在顶部
  明确"这里管什么 / 不管什么 / 走错请去哪", 一次性消灭这类歧义.

  Usage:
  <ConceptDisambiguationAlert
    here="本厂自己生产的成品 (如「叮咚好食光卤猪蹄 200g」)"
    here-name="成品 / SKU"
    other="采购入库的原料/包材 (如「冻猪蹄」「吸塑盒」)"
    other-name="原料 / 物料 (采购入库)"
    other-path="/warehouse/materials"
    :consequence="'建错位置会导致采购订单的「原料」下拉看不到选项'"
  />
-->

<script setup lang="ts">
import { useRouter } from 'vue-router';

defineProps<{
  /** 当前页管的概念是什么 (描述) */
  here: string;
  /** 当前页概念的简短名 (用于强调) */
  hereName: string;
  /** 容易混淆的另一个概念 (描述) */
  other: string;
  /** 另一概念的简短名 (用于跳转链接文案) */
  otherName: string;
  /** 另一概念页面路径 */
  otherPath: string;
  /** 走错位置的具体后果 (可选, 例如 "采购订单 dropdown 看不到选项") */
  consequence?: string;
}>();

const router = useRouter();
</script>

<template>
  <el-alert
    type="info"
    :closable="false"
    show-icon
    style="margin-bottom: 12px"
  >
    <template #title>
      <strong>这里是「{{ hereName }}」管理</strong> — {{ here }}
    </template>
    <template #default>
      <span style="font-size: 13px">
 ️ 如果你想录入的是<strong>{{ other }}</strong>，请到
        <el-link
          type="primary"
          underline="never"
          style="font-weight: 600"
          @click="router.push(otherPath)"
        >{{ otherName }}</el-link>
        创建。<template v-if="consequence">{{ consequence }}。</template>
      </span>
    </template>
  </el-alert>
</template>
