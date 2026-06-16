<script setup lang="ts">
/**
 * 人事管理 Dashboard
 * 适用角色: hr_admin
 * 特点: 员工统计、考勤概览、部门信息
 */
import { ref, onMounted, computed } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';
import { ElMessage } from 'element-plus';
import { User, Clock, Files, UserFilled, Avatar } from '@element-plus/icons-vue';

const router = useRouter();
const authStore = useAuthStore();

const loading = ref(false);
const factoryId = computed(() => authStore.factoryId);

// 人事统计数据
// R76: todayAttendance/attendanceRate 用 null 表示"考勤数据未接入"。
// 后端 `/timeclock/admin/statistics?startDate=&endDate=` 已存在 (TimeClockController:288),
// 后续可在此 wire — 当前先显示 "-" 而不是假数据 (CLAUDE.md "禁止降级处理 - 不返回假数据,明确显示错误")。
const hrStats = ref<{
  totalEmployees: number;
  activeEmployees: number;
  todayAttendance: number | null;
  attendanceRate: number | null;
  departments: number;
  pendingLeaves: number;
}>({
  totalEmployees: 0,
  activeEmployees: 0,
  todayAttendance: null,
  attendanceRate: null,
  departments: 0,
  pendingLeaves: 0
});

// 统计卡片
// 出勤数据接入前显示 "-" + "数据待接入" 标签 (避免假数据误导客户)
const statCards = computed<Array<{
  title: string;
  value: number | string;
  unit: string;
  icon: unknown;
  color: string;
  route: string;
  pending?: boolean;
}>>(() => [
  {
    title: '在职员工',
    value: hrStats.value.activeEmployees,
    unit: '人',
    icon: User,
    color: '#409eff',
    route: '/hr/employees'
  },
  {
    title: '今日出勤',
    value: hrStats.value.todayAttendance ?? '-',
    unit: hrStats.value.todayAttendance !== null ? '人' : '',
    icon: Clock,
    color: '#67c23a',
    route: '/hr/attendance',
    pending: hrStats.value.todayAttendance === null
  },
  {
    title: '出勤率',
    value: hrStats.value.attendanceRate ?? '-',
    unit: hrStats.value.attendanceRate !== null ? '%' : '',
    icon: Files,
    color: '#e6a23c',
    route: '/hr/attendance',
    pending: hrStats.value.attendanceRate === null
  },
  {
    title: '部门数量',
    value: hrStats.value.departments,
    unit: '个',
    icon: UserFilled,
    color: '#909399',
    route: '/hr/departments'
  }
]);

// 快捷操作
const quickActions = [
  { title: '员工档案', icon: User, route: '/hr/employees', color: '#409eff' },
  { title: '考勤统计', icon: Clock, route: '/hr/attendance', color: '#67c23a' },
  { title: '入职白名单', icon: Files, route: '/hr/whitelist', color: '#e6a23c' },
  { title: '部门管理', icon: UserFilled, route: '/hr/departments', color: '#909399' }
];

onMounted(async () => {
  await loadHRData();
});

async function loadHRData() {
  if (!factoryId.value) return;

  loading.value = true;
  try {
    const [employeesRes, departmentsRes] = await Promise.allSettled([
      get<{ content: Record<string, unknown>[]; totalElements: number }>(`/${factoryId.value}/users?page=1&size=1`),
      get<{ content: Record<string, unknown>[]; totalElements: number }>(`/${factoryId.value}/departments?page=1&size=1`)
    ]);

    const failed: string[] = [];

    if (employeesRes.status === 'fulfilled' && employeesRes.value.success) {
      hrStats.value.totalEmployees = employeesRes.value.data?.totalElements ?? 0;
      hrStats.value.activeEmployees = employeesRes.value.data?.totalElements ?? 0;
    } else {
      failed.push('员工列表');
      console.error('[DashboardHR] employees API failed',
        employeesRes.status === 'rejected' ? employeesRes.reason : employeesRes.value);
    }

    if (departmentsRes.status === 'fulfilled' && departmentsRes.value.success) {
      hrStats.value.departments = departmentsRes.value.data?.totalElements ?? 0;
    } else {
      failed.push('部门列表');
      console.error('[DashboardHR] departments API failed',
        departmentsRes.status === 'rejected' ? departmentsRes.reason : departmentsRes.value);
    }

    if (failed.length > 0) {
      ElMessage.warning(`部分数据加载失败: ${failed.join('、')}`);
    }

    // 考勤数据保持 null 状态 — 后端 /timeclock/admin/statistics 已存在但本页未 wire,
    // UI 显示 "-" + "数据待接入" 标签, 不再用 activeEmployees * 0.92 编造假数据
  } catch (error) {
    console.error('[DashboardHR] Failed to load HR data:', error);
    ElMessage.error('加载人事 Dashboard 失败');
  } finally {
    loading.value = false;
  }
}

function navigateTo(route: string) {
  router.push(route);
}
</script>

<template>
  <div class="dashboard-hr" v-loading="loading">
    <!-- 欢迎区 -->
    <div class="welcome-section">
      <div class="welcome-info">
        <h1>欢迎回来，{{ authStore.user?.fullName || authStore.user?.username }}</h1>
        <p>
          <el-tag type="warning" size="small">人事管理员</el-tag>
          <span class="factory-info">工厂: {{ factoryId }}</span>
        </p>
      </div>
    </div>

    <!-- 统计卡片 -->
    <el-row :gutter="20" class="stat-cards">
      <el-col v-for="card in statCards" :key="card.title" :xs="24" :sm="12" :md="6">
        <el-card class="stat-card" shadow="hover" @click="navigateTo(card.route)">
          <div class="stat-content">
            <div class="stat-info">
              <span class="stat-title">
                {{ card.title }}
                <el-tag v-if="card.pending" size="small" type="info" effect="plain" style="margin-left: 6px;">数据待接入</el-tag>
              </span>
              <span class="stat-value" :style="{ color: card.color }">
                {{ card.value }}
                <small v-if="card.unit">{{ card.unit }}</small>
              </span>
            </div>
            <el-icon class="stat-icon" :style="{ backgroundColor: card.color + '20', color: card.color }">
              <component :is="card.icon" />
            </el-icon>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 快捷操作 -->
    <el-card class="quick-actions-card">
      <template #header>
        <span>人事管理</span>
      </template>
      <div class="quick-actions">
        <div
          v-for="action in quickActions"
          :key="action.title"
          class="action-item"
          @click="navigateTo(action.route)"
        >
          <el-icon :size="32" :style="{ color: action.color }">
            <component :is="action.icon" />
          </el-icon>
          <span>{{ action.title }}</span>
        </div>
      </div>
    </el-card>

    <!-- 今日考勤概览 -->
    <el-row :gutter="20">
      <el-col :xs="24" :lg="12">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>今日考勤概览</span>
              <el-button type="primary" link @click="navigateTo('/hr/attendance')">
                查看详情
              </el-button>
            </div>
          </template>
          <div class="attendance-overview">
            <el-empty
              v-if="hrStats.todayAttendance === null"
              description="考勤数据待接入"
              :image-size="60"
            >
              <template #description>
                <div style="color: #909399; font-size: 13px;">
                  考勤数据待接入
                  <div style="font-size: 12px; color: #c0c4cc; margin-top: 4px;">
                    后端 /timeclock/admin/statistics 接口已就绪
                  </div>
                </div>
              </template>
            </el-empty>
            <template v-else>
              <div class="attendance-stat">
                <el-icon :size="40" color="#67c23a"><Avatar /></el-icon>
                <div class="stat-detail">
                  <span class="label">已打卡</span>
                  <span class="value">{{ hrStats.todayAttendance }}</span>
                </div>
              </div>
              <div class="attendance-stat">
                <el-icon :size="40" color="#f56c6c"><Avatar /></el-icon>
                <div class="stat-detail">
                  <span class="label">未打卡</span>
                  <span class="value">{{ hrStats.activeEmployees - (hrStats.todayAttendance ?? 0) }}</span>
                </div>
              </div>
              <div class="attendance-stat">
                <el-icon :size="40" color="#409eff"><Clock /></el-icon>
                <div class="stat-detail">
                  <span class="label">出勤率</span>
                  <span class="value">{{ hrStats.attendanceRate }}%</span>
                </div>
              </div>
            </template>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :lg="12">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>员工分布</span>
              <el-button type="primary" link @click="navigateTo('/hr/employees')">
                查看详情
              </el-button>
            </div>
          </template>
          <div class="employee-distribution">
            <el-empty v-if="hrStats.totalEmployees === 0" description="暂无员工数据" />
            <div v-else class="distribution-info">
              <p>共 <strong>{{ hrStats.totalEmployees }}</strong> 名员工</p>
              <p>分布在 <strong>{{ hrStats.departments }}</strong> 个部门</p>
              <el-button type="primary" @click="navigateTo('/hr/employees')">
                管理员工
              </el-button>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style lang="scss" scoped>
.dashboard-hr {
  min-height: calc(100vh - 144px);
}

.welcome-section {
  margin-bottom: 24px;

  h1 {
    font-size: 24px;
    color: #333;
    margin: 0 0 8px;
  }

  p {
    display: flex;
    align-items: center;
    gap: 12px;
    margin: 0;
    color: #999;
    font-size: 14px;
  }
}

.stat-cards {
  margin-bottom: 24px;

  .stat-card {
    margin-bottom: 20px;
    cursor: pointer;
    transition: transform 0.2s;

    &:hover {
      transform: translateY(-2px);
    }
  }

  .stat-content {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }

  .stat-info {
    display: flex;
    flex-direction: column;
  }

  .stat-title {
    font-size: 14px;
    color: #999;
    margin-bottom: 8px;
  }

  .stat-value {
    font-size: 28px;
    font-weight: 600;

    small {
      font-size: 14px;
      font-weight: 400;
      margin-left: 4px;
    }
  }

  .stat-icon {
    width: 48px;
    height: 48px;
    border-radius: 8px;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 24px;
  }
}

.quick-actions-card {
  margin-bottom: 24px;

  .quick-actions {
    display: flex;
    gap: 24px;
    flex-wrap: wrap;
  }

  .action-item {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 8px;
    padding: 16px 24px;
    border-radius: 8px;
    cursor: pointer;
    transition: background-color 0.2s;

    &:hover {
      background-color: #f5f7fa;
    }

    span {
      font-size: 14px;
      color: #606266;
    }
  }
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.attendance-overview {
  display: flex;
  justify-content: space-around;
  padding: 20px 0;

  .attendance-stat {
    display: flex;
    align-items: center;
    gap: 12px;

    .stat-detail {
      display: flex;
      flex-direction: column;

      .label {
        font-size: 12px;
        color: #909399;
      }

      .value {
        font-size: 24px;
        font-weight: 600;
        color: #303133;
      }
    }
  }
}

.employee-distribution {
  min-height: 150px;
  display: flex;
  align-items: center;
  justify-content: center;

  .distribution-info {
    text-align: center;

    p {
      margin: 0 0 12px;
      font-size: 14px;
      color: #606266;

      strong {
        font-size: 18px;
        color: #409eff;
      }
    }
  }
}

.el-card {
  margin-bottom: 20px;
}
</style>
