<script setup lang="ts">
// Sprint 4 W2 S-REPORTS-PRESETS + 防呆 R3/R5:
// - 14 预置卡片 (R3 不让用户瞎搜索, 直接选)
// - 9 disabled 卡片点击给 next action (R5 dead-end 改导航)
import { useRouter } from 'vue-router';
import { ElMessageBox } from 'element-plus';

const router = useRouter();

interface Card {
  id: string;
  title: string;
  desc: string;
  icon: string;
  active: boolean;
  route?: string;
}

const cards: Card[] = [
 { id: 'daily', title: '销售额日报', desc: '当日订单数/销售额/收款', icon: '', active: true, route: '/sales/reports/daily' },
 { id: 'monthly', title: '销售额月报', desc: '当月销售额 + 按日明细', icon: '', active: true, route: '/sales/reports/monthly' },
 { id: 'yearly', title: '销售额年报', desc: '当年销售额 + 按月明细', icon: '', active: true, route: '/sales/reports/yearly' },
 { id: 'customer-rank', title: '客户销售排行 top 20', desc: '按销售额客户排名', icon: '', active: true, route: '/sales/reports/customer-rank' },
 { id: 'product-rank', title: '产品销售排行 top 20', desc: '按销售额产品排名', icon: '', active: true, route: '/sales/reports/product-rank' },
 { id: 'salesperson-performance', title: '业绩 by 业务员', desc: '业务员销售业绩对比', icon: '', active: false },
  { id: 'aging', title: '应收账龄', desc: '30/60/90/120/180+ 天分布', icon: '⏰', active: false },
 { id: 'collection-rate', title: '客户回款率', desc: '回款金额/应收金额', icon: '', active: false },
  { id: 'return-rate', title: '退货率', desc: '退货金额/销售金额', icon: '↩️', active: false },
 { id: 'gross-margin', title: '销售毛利率 by 产品', desc: '产品级毛利率排名', icon: '', active: false },
 { id: 'purchase-frequency', title: '客户购买频次', desc: '客户回购周期分析', icon: '', active: false },
 { id: 'growth-rate', title: '月度增长率', desc: 'MoM/YoY 增长指标', icon: '', active: false },
 { id: 'regional-comparison', title: '区域销售对比', desc: '区域销售分布', icon: '️', active: false },
  { id: 'customer-tier', title: '客户分级销售贡献', desc: 'A/B/C/D 分级贡献率', icon: '⭐', active: false },
];

async function open(card: Card): Promise<void> {
  if (card.active && card.route) {
    router.push(card.route);
    return;
  }
  // R5 dead-end 改导航: 9 个 Sprint 5 报表点击不应该哑火,
  // 给用户当前可用替代 + 一键跳转.
  try {
    await ElMessageBox.confirm(
      `${card.title} 留 Sprint 5 实现。当前 5 个可用报表: 销售额日报/月报/年报 + 客户/产品销售排行 top 20。是否查看销售报表概览?`,
      '功能待上线',
      { confirmButtonText: '查看可用报表', cancelButtonText: '关闭', type: 'info' },
    );
    // 已经在 dashboard, 不动 router; 此分支保留为 hook 给 future 子模块
  } catch {
    /* 用户关闭 */
  }
}
</script>

<template>
  <div class="sales-reports-dashboard">
    <div class="header">
      <h2>销售报表</h2>
      <p class="subtitle">14 预置销售报表 — 5 可用, 9 待 Sprint 5</p>
    </div>

    <div class="card-grid">
      <el-card
        v-for="card in cards"
        :key="card.id"
        :class="['report-card', { disabled: !card.active }]"
        shadow="hover"
        @click="open(card)"
      >
        <div class="card-content">
          <div class="icon">{{ card.icon }}</div>
          <div class="title">{{ card.title }}</div>
          <div class="desc">{{ card.desc }}</div>
          <el-tag v-if="!card.active" size="small" type="info">Sprint 5</el-tag>
          <el-tag v-else size="small" type="success">可用</el-tag>
        </div>
      </el-card>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.sales-reports-dashboard {
  padding: 16px;
  .header {
    margin-bottom: 24px;
    h2 { margin: 0 0 6px 0; }
    .subtitle { color: #909399; margin: 0; font-size: 13px; }
  }
  .card-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
    gap: 16px;
  }
  .report-card {
    cursor: pointer;
    transition: transform 0.2s;
    &:hover:not(.disabled) {
      transform: translateY(-4px);
    }
    &.disabled {
      opacity: 0.6;
      cursor: not-allowed;
    }
  }
  .card-content {
    display: flex;
    flex-direction: column;
    gap: 8px;
    .icon {
      font-size: 32px;
    }
    .title {
      font-size: 15px;
      font-weight: 600;
      color: var(--color-text-primary, #1A2332);
    }
    .desc {
      font-size: 12px;
      color: #909399;
      line-height: 1.4;
      flex: 1;
    }
  }
}
</style>
