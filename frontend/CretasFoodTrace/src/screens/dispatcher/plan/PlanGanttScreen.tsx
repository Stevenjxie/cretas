/**
 * 甘特图屏幕
 *
 * 功能：
 * - 以甘特图形式展示生产计划和排程
 * - 时间轴视图 (按小时/天)
 * - 产线纵向分布
 * - 拖拽调整计划时间 (未来版本)
 * - 实时进度显示
 *
 * @version 1.0.0
 * @since 2025-12-29
 */

import React, { useState, useCallback, useEffect, useRef, useMemo } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  TouchableOpacity,
  RefreshControl,
  Dimensions,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { LinearGradient } from 'expo-linear-gradient';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation } from '@react-navigation/native';
import { DISPATCHER_THEME, LineSchedule } from '../../../types/dispatcher';
import { schedulingApiClient, ProductionLine } from '../../../services/api/schedulingApiClient';
import { isAxiosError } from 'axios';

const { width: SCREEN_WIDTH } = Dimensions.get('window');
const HOUR_WIDTH = 60; // 每小时的宽度
const ROW_HEIGHT = 70; // 每行高度
const HEADER_HEIGHT = 50;
const TIMELINE_HOURS = 24; // 显示24小时

// 本地扩展的产线接口 (甘特图专用)
interface GanttProductionLine {
  id: string;
  name: string;
  status: 'running' | 'idle' | 'maintenance' | 'error';
  capacity: number;
  workshopId: string;
  workshopName: string;
}

// 转换API产线数据到甘特图格式
const transformToGanttLine = (line: ProductionLine): GanttProductionLine => {
  let status: 'running' | 'idle' | 'maintenance' | 'error' = 'idle';
  if (line.status === 'active') status = 'running';
  else if (line.status === 'maintenance') status = 'maintenance';
  else if (line.status === 'inactive') status = 'idle';

  return {
    id: line.id,
    name: line.name,
    status,
    capacity: line.capacity,
    workshopId: line.workshopId,
    workshopName: line.workshopName || '',
  };
};

// Fallback data when API fails or returns empty results
const fallbackProductionLines: GanttProductionLine[] = [
  { id: 'L1', name: '切片A线', status: 'running', capacity: 100, workshopId: 'W1', workshopName: '切片车间' },
  { id: 'L2', name: '切片B线', status: 'running', capacity: 80, workshopId: 'W1', workshopName: '切片车间' },
  { id: 'L3', name: '包装A线', status: 'running', capacity: 120, workshopId: 'W2', workshopName: '包装车间' },
  { id: 'L4', name: '包装B线', status: 'idle', capacity: 100, workshopId: 'W2', workshopName: '包装车间' },
  { id: 'L5', name: '速冻线', status: 'maintenance', capacity: 200, workshopId: 'W3', workshopName: '速冻车间' },
];

interface GanttTask {
  id: string;
  lineId: string;
  name: string;
  startHour: number;
  duration: number;
  progress: number;
  status: 'pending' | 'in_progress' | 'completed' | 'delayed';
  color: string;
}

// Fallback tasks when API fails or returns empty results
const fallbackTasks: GanttTask[] = [
  { id: '1', lineId: 'L1', name: '带鱼片 100kg', startHour: 8, duration: 4, progress: 75, status: 'in_progress', color: '#722ed1' },
  { id: '2', lineId: 'L1', name: '黄鱼片 80kg', startHour: 13, duration: 3, progress: 0, status: 'pending', color: '#1890ff' },
  { id: '3', lineId: 'L2', name: '鱿鱼圈 60kg', startHour: 9, duration: 5, progress: 40, status: 'in_progress', color: '#52c41a' },
  { id: '4', lineId: 'L3', name: '虾仁包装 120kg', startHour: 7, duration: 6, progress: 100, status: 'completed', color: '#13c2c2' },
  { id: '5', lineId: 'L3', name: '带鱼片包装', startHour: 14, duration: 4, progress: 20, status: 'delayed', color: '#ff4d4f' },
  { id: '6', lineId: 'L4', name: '黄鱼片包装', startHour: 10, duration: 3, progress: 0, status: 'pending', color: '#fa8c16' },
];

// 颜色列表用于任务显示
const taskColors = ['#722ed1', '#1890ff', '#52c41a', '#13c2c2', '#fa8c16', '#eb2f96'];

export default function PlanGanttScreen() {
  const navigation = useNavigation<any>();
  const scrollViewRef = useRef<ScrollView>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [loading, setLoading] = useState(true);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [currentDate, setCurrentDate] = useState<string>(() => {
    const isoDate = new Date().toISOString();
    return isoDate.substring(0, 10); // YYYY-MM-DD format
  });
  const [productionLines, setProductionLines] = useState<GanttProductionLine[]>([]);
  const [tasks, setTasks] = useState<GanttTask[]>([]);
  const [viewMode, setViewMode] = useState<'hour' | 'day'>('hour');

  // P2 Fix: Memoize tasks grouped by line to avoid repeated filtering
  const tasksByLine = useMemo(() => {
    const byLine = new Map<string, GanttTask[]>();
    for (const task of tasks) {
      if (!byLine.has(task.lineId)) {
        byLine.set(task.lineId, []);
      }
      byLine.get(task.lineId)!.push(task);
    }
    return byLine;
  }, [tasks]);

  // P2 Fix: Memoize task statistics for footer
  const taskStats = useMemo(() => ({
    total: tasks.length,
    inProgress: tasks.filter(t => t.status === 'in_progress').length,
    delayed: tasks.filter(t => t.status === 'delayed').length,
    avgProgress: tasks.length > 0
      ? Math.round(tasks.reduce((sum, t) => sum + t.progress, 0) / tasks.length)
      : 0,
  }), [tasks]);

  // 当前时间指示器
  const currentHour = new Date().getHours() + new Date().getMinutes() / 60;

  useEffect(() => {
    // 滚动到当前时间位置
    const scrollPosition = Math.max(0, (currentHour - 2) * HOUR_WIDTH);
    setTimeout(() => {
      scrollViewRef.current?.scrollTo({ x: scrollPosition, animated: true });
    }, 300);
  }, []);

  const loadData = useCallback(async () => {
    try {
      setErrorMessage(null);
      // 获取产线数据
      const linesRes = await schedulingApiClient.getProductionLines();
      if (linesRes.success && linesRes.data && linesRes.data.length > 0) {
        const ganttLines = linesRes.data.map(transformToGanttLine);
        setProductionLines(ganttLines);
      } else {
        // Fallback when API returns no data
        setProductionLines([]);
      }

      // 获取调度计划数据
      const plansRes = await schedulingApiClient.getPlans({
        startDate: currentDate,
        endDate: currentDate,
        page: 1,
        size: 50,
      });
      if (plansRes.success && plansRes.data?.content && plansRes.data.content.length > 0) {
        // Transform plans to Gantt tasks
        const ganttTasks: GanttTask[] = [];
        let colorIndex = 0;

        for (const plan of plansRes.data.content) {
          // Use lineSchedules instead of schedules (correct field name from type)
          if (plan.lineSchedules && plan.lineSchedules.length > 0) {
            for (const schedule of plan.lineSchedules) {
              // Parse start time and calculate duration - use plannedStartTime/plannedEndTime
              const startTime = schedule.plannedStartTime ? new Date(schedule.plannedStartTime) : new Date();
              const endTime = schedule.plannedEndTime ? new Date(schedule.plannedEndTime) : new Date(startTime.getTime() + 4 * 60 * 60 * 1000);
              const startHour = startTime.getHours() + startTime.getMinutes() / 60;
              const durationHours = (endTime.getTime() - startTime.getTime()) / (1000 * 60 * 60);

              // Determine task status - handle both lowercase (from type) and uppercase (from API)
              let taskStatus: GanttTask['status'] = 'pending';
              const scheduleStatus = schedule.status?.toLowerCase();
              if (scheduleStatus === 'completed') taskStatus = 'completed';
              else if (scheduleStatus === 'in_progress' || scheduleStatus === 'started') taskStatus = 'in_progress';
              else if (scheduleStatus === 'delayed') taskStatus = 'delayed';

              // Calculate progress - use actualQuantity instead of completedQuantity
              const progress = schedule.actualQuantity && schedule.plannedQuantity
                ? Math.round((schedule.actualQuantity / schedule.plannedQuantity) * 100)
                : (taskStatus === 'completed' ? 100 : 0);

              const taskId = schedule.id ?? String(plan.id);
              const taskColor = taskColors[colorIndex % taskColors.length] ?? '#722ed1';
              ganttTasks.push({
                id: taskId,
                lineId: schedule.productionLineId || 'L1',
                name: `${schedule.productTypeName ?? plan.productTypeName ?? '生产任务'} ${schedule.plannedQuantity ?? 0}kg`,
                startHour: startHour,
                duration: Math.max(1, durationHours),
                progress,
                status: taskStatus,
                color: taskColor,
              });
              colorIndex++;
            }
          }
        }

        if (ganttTasks.length > 0) {
          setTasks(ganttTasks);
        } else {
          // Fallback when API returns no task data
          setTasks([]);
        }
      } else {
        // Fallback when API returns no data
        setTasks([]);
      }
    } catch (error) {
      console.error('Failed to load Gantt data:', error);
      setErrorMessage('排产数据加载失败，请下拉刷新或稍后重试。');
      if (isAxiosError(error)) {
        if (error.response?.status === 401) {
          console.error('认证过期，请重新登录');
        }
      }
      // Fallback when API call fails
      setProductionLines([]);
      setTasks([]);
    } finally {
      setLoading(false);
    }
  }, [currentDate]);

  const onRefresh = useCallback(async () => {
    setRefreshing(true);
    await loadData();
    setRefreshing(false);
  }, [loadData]);

  // Load data on mount and when date changes
  useEffect(() => {
    loadData();
  }, [loadData]);

  const changeDate = (delta: number) => {
    const date = new Date(currentDate);
    date.setDate(date.getDate() + delta);
    const newDate = date.toISOString().substring(0, 10); // YYYY-MM-DD format
    setCurrentDate(newDate);
    // loadData will be called automatically via useEffect dependency
  };

  const getStatusColor = (status: string) => {
    switch (status) {
      case 'running': return '#52c41a';
      case 'idle': return '#fa8c16';
      case 'maintenance': return '#ff4d4f';
      default: return '#d9d9d9';
    }
  };

  const renderTimeHeader = () => {
    const hours = [];
    for (let i = 0; i < TIMELINE_HOURS; i++) {
      hours.push(
        <View key={i} style={[styles.timeCell, { width: HOUR_WIDTH }]}>
          <Text style={styles.timeCellText}>{i.toString().padStart(2, '0')}:00</Text>
        </View>
      );
    }
    return (
      <View style={styles.timeHeader}>
        {hours}
      </View>
    );
  };

  const renderCurrentTimeLine = () => {
    const position = currentHour * HOUR_WIDTH;
    return (
      <View style={[styles.currentTimeLine, { left: position }]}>
        <View style={styles.currentTimeIndicator} />
        <View style={styles.currentTimeLineBar} />
      </View>
    );
  };

  const renderTask = (task: GanttTask) => {
    const left = task.startHour * HOUR_WIDTH;
    const width = task.duration * HOUR_WIDTH - 4;

    return (
      <TouchableOpacity
        key={task.id}
        style={[
          styles.taskBar,
          {
            left,
            width,
            backgroundColor: task.color + '20',
            borderLeftColor: task.color,
          },
        ]}
        onPress={() => navigation.navigate('PlanDetail', { planId: task.id })}
      >
        <View style={[styles.taskProgress, { width: `${task.progress}%`, backgroundColor: task.color }]} />
        <Text style={[styles.taskName, { color: task.color }]} numberOfLines={1}>
          {task.name}
        </Text>
        <Text style={styles.taskProgressText}>{task.progress}%</Text>
      </TouchableOpacity>
    );
  };

  const renderProductionLine = (line: GanttProductionLine) => {
    // P2 Fix: Use memoized tasksByLine instead of filtering
    const lineTasks = tasksByLine.get(line.id) || [];

    return (
      <View key={line.id} style={styles.ganttRow}>
        {/* 产线名称 (固定) */}
        <View style={styles.lineNameCell}>
          <View style={styles.lineNameContent}>
            <View style={[styles.lineStatusDot, { backgroundColor: getStatusColor(line.status) }]} />
            <View>
              <Text style={styles.lineName}>{line.name}</Text>
              <Text style={styles.lineWorkshop}>{line.workshopName}</Text>
            </View>
          </View>
        </View>

        {/* 任务区域 (可滚动) */}
        <View style={[styles.taskArea, { width: TIMELINE_HOURS * HOUR_WIDTH }]}>
          {/* 网格线 */}
          {Array.from({ length: TIMELINE_HOURS }).map((_, i) => (
            <View key={i} style={[styles.gridLine, { left: i * HOUR_WIDTH }]} />
          ))}
          {/* 任务条 */}
          {lineTasks.map(renderTask)}
        </View>
      </View>
    );
  };

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      {/* Header */}
      <LinearGradient
        colors={[DISPATCHER_THEME.primary, DISPATCHER_THEME.secondary, DISPATCHER_THEME.accent]}
        start={{ x: 0, y: 0 }}
        end={{ x: 1, y: 1 }}
        style={styles.header}
      >
        <TouchableOpacity
          style={styles.backButton}
          onPress={() => navigation.goBack()}
        >
          <MaterialCommunityIcons name="chevron-left" size={28} color="#fff" />
        </TouchableOpacity>

        <Text style={styles.headerTitle}>甘特图视图</Text>

        <TouchableOpacity style={styles.headerAction}>
          <MaterialCommunityIcons name="fullscreen" size={24} color="#fff" />
        </TouchableOpacity>
      </LinearGradient>

      {/* Date Navigation */}
      <View style={styles.dateNav}>
        <TouchableOpacity style={styles.dateNavBtn} onPress={() => changeDate(-1)}>
          <MaterialCommunityIcons name="chevron-left" size={24} color="#666" />
        </TouchableOpacity>
        <TouchableOpacity style={styles.dateDisplay}>
          <MaterialCommunityIcons name="calendar" size={18} color={DISPATCHER_THEME.primary} />
          <Text style={styles.dateText}>{currentDate}</Text>
        </TouchableOpacity>
        <TouchableOpacity style={styles.dateNavBtn} onPress={() => changeDate(1)}>
          <MaterialCommunityIcons name="chevron-right" size={24} color="#666" />
        </TouchableOpacity>

        {/* View Mode Toggle */}
        <View style={styles.viewModeToggle}>
          <TouchableOpacity
            style={[styles.viewModeBtn, viewMode === 'hour' && styles.viewModeBtnActive]}
            onPress={() => setViewMode('hour')}
          >
            <Text style={[styles.viewModeBtnText, viewMode === 'hour' && styles.viewModeBtnTextActive]}>时</Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[styles.viewModeBtn, viewMode === 'day' && styles.viewModeBtnActive]}
            onPress={() => setViewMode('day')}
          >
            <Text style={[styles.viewModeBtnText, viewMode === 'day' && styles.viewModeBtnTextActive]}>日</Text>
          </TouchableOpacity>
        </View>
      </View>

      {/* Legend */}
      <View style={styles.legend}>
        <View style={styles.legendItem}>
          <View style={[styles.legendDot, { backgroundColor: '#52c41a' }]} />
          <Text style={styles.legendText}>进行中</Text>
        </View>
        <View style={styles.legendItem}>
          <View style={[styles.legendDot, { backgroundColor: '#1890ff' }]} />
          <Text style={styles.legendText}>待开始</Text>
        </View>
        <View style={styles.legendItem}>
          <View style={[styles.legendDot, { backgroundColor: '#13c2c2' }]} />
          <Text style={styles.legendText}>已完成</Text>
        </View>
        <View style={styles.legendItem}>
          <View style={[styles.legendDot, { backgroundColor: '#ff4d4f' }]} />
          <Text style={styles.legendText}>延迟</Text>
        </View>
      </View>

      {errorMessage && (
        <View style={styles.emptyState} testID="plan-gantt-error">
          <MaterialCommunityIcons name="alert-circle-outline" size={28} color="#ff4d4f" />
          <Text style={styles.emptyStateTitle}>加载失败</Text>
          <Text style={styles.emptyStateText}>{errorMessage}</Text>
        </View>
      )}

      {!loading && !errorMessage && productionLines.length === 0 && (
        <View style={styles.emptyState} testID="plan-gantt-empty">
          <MaterialCommunityIcons name="calendar-blank-outline" size={28} color={DISPATCHER_THEME.primary} />
          <Text style={styles.emptyStateTitle}>暂无真实排产</Text>
          <Text style={styles.emptyStateText}>当前日期没有产线排程数据，请确认排产计划后刷新。</Text>
        </View>
      )}

      {/* Gantt Chart */}
      <View style={styles.ganttContainer}>
        {/* Fixed Line Names Column */}
        <View style={styles.lineNamesColumn}>
          <View style={[styles.cornerCell, { height: HEADER_HEIGHT }]}>
            <Text style={styles.cornerCellText}>产线</Text>
          </View>
          {productionLines.map(line => (
            <View key={line.id} style={[styles.lineNameCellFixed, { height: ROW_HEIGHT }]}>
              <View style={styles.lineNameContent}>
                <View style={[styles.lineStatusDot, { backgroundColor: getStatusColor(line.status) }]} />
                <View>
                  <Text style={styles.lineName}>{line.name}</Text>
                  <Text style={styles.lineWorkshop}>{line.workshopName}</Text>
                </View>
              </View>
            </View>
          ))}
        </View>

        {/* Scrollable Chart Area */}
        <ScrollView
          ref={scrollViewRef}
          horizontal
          showsHorizontalScrollIndicator={true}
          refreshControl={
            <RefreshControl refreshing={refreshing} onRefresh={onRefresh} />
          }
        >
          <View>
            {/* Time Header */}
            {renderTimeHeader()}

            {/* Task Rows */}
            {productionLines.map(line => {
              // P2 Fix: Use memoized tasksByLine instead of filtering
              const lineTasks = tasksByLine.get(line.id) || [];
              return (
                <View key={line.id} style={[styles.ganttRowContent, { height: ROW_HEIGHT }]}>
                  {/* Grid Lines */}
                  {Array.from({ length: TIMELINE_HOURS }).map((_, i) => (
                    <View key={i} style={[styles.gridLine, { left: i * HOUR_WIDTH }]} />
                  ))}
                  {/* Current Time Line */}
                  {renderCurrentTimeLine()}
                  {/* Tasks */}
                  {lineTasks.map(renderTask)}
                </View>
              );
            })}
          </View>
        </ScrollView>
      </View>

      {/* Summary Footer - P2 Fix: Use memoized taskStats */}
      <View style={styles.footer}>
        <View style={styles.footerItem}>
          <Text style={styles.footerValue}>{taskStats.total}</Text>
          <Text style={styles.footerLabel}>计划任务</Text>
        </View>
        <View style={styles.footerItem}>
          <Text style={[styles.footerValue, { color: '#52c41a' }]}>
            {taskStats.inProgress}
          </Text>
          <Text style={styles.footerLabel}>进行中</Text>
        </View>
        <View style={styles.footerItem}>
          <Text style={[styles.footerValue, { color: '#ff4d4f' }]}>
            {taskStats.delayed}
          </Text>
          <Text style={styles.footerLabel}>延迟</Text>
        </View>
        <View style={styles.footerItem}>
          <Text style={styles.footerValue}>
            {taskStats.avgProgress}%
          </Text>
          <Text style={styles.footerLabel}>整体进度</Text>
        </View>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f5f5',
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 12,
    paddingHorizontal: 16,
  },
  backButton: {
    padding: 4,
  },
  headerTitle: {
    fontSize: 18,
    fontWeight: '600',
    color: '#fff',
  },
  headerAction: {
    padding: 4,
  },
  dateNav: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#fff',
    padding: 12,
    borderBottomWidth: 1,
    borderBottomColor: '#e8e8e8',
  },
  dateNavBtn: {
    padding: 4,
  },
  dateDisplay: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 12,
    paddingVertical: 6,
    backgroundColor: '#f9f5ff',
    borderRadius: 6,
    gap: 6,
    marginHorizontal: 8,
  },
  dateText: {
    fontSize: 14,
    fontWeight: '500',
    color: DISPATCHER_THEME.primary,
  },
  viewModeToggle: {
    flexDirection: 'row',
    marginLeft: 'auto',
    backgroundColor: '#f0f0f0',
    borderRadius: 6,
    padding: 2,
  },
  viewModeBtn: {
    paddingHorizontal: 12,
    paddingVertical: 4,
    borderRadius: 4,
  },
  viewModeBtnActive: {
    backgroundColor: '#fff',
  },
  viewModeBtnText: {
    fontSize: 12,
    color: '#999',
  },
  viewModeBtnTextActive: {
    color: DISPATCHER_THEME.primary,
    fontWeight: '500',
  },
  legend: {
    flexDirection: 'row',
    backgroundColor: '#fff',
    paddingVertical: 8,
    paddingHorizontal: 16,
    gap: 16,
    borderBottomWidth: 1,
    borderBottomColor: '#e8e8e8',
  },
  legendItem: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
  },
  legendDot: {
    width: 10,
    height: 10,
    borderRadius: 5,
  },
  legendText: {
    fontSize: 12,
    color: '#666',
  },
  ganttContainer: {
    flex: 1,
    flexDirection: 'row',
    backgroundColor: '#fff',
  },
  emptyState: {
    alignItems: 'center',
    backgroundColor: '#fff',
    borderBottomColor: '#e8e8e8',
    borderBottomWidth: 1,
    paddingHorizontal: 20,
    paddingVertical: 18,
  },
  emptyStateTitle: {
    color: '#333',
    fontSize: 15,
    fontWeight: '600',
    marginTop: 8,
  },
  emptyStateText: {
    color: '#666',
    fontSize: 13,
    lineHeight: 18,
    marginTop: 4,
    textAlign: 'center',
  },
  lineNamesColumn: {
    width: 100,
    borderRightWidth: 1,
    borderRightColor: '#e8e8e8',
    backgroundColor: '#fafafa',
  },
  cornerCell: {
    justifyContent: 'center',
    alignItems: 'center',
    borderBottomWidth: 1,
    borderBottomColor: '#e8e8e8',
  },
  cornerCellText: {
    fontSize: 12,
    color: '#999',
    fontWeight: '500',
  },
  lineNameCellFixed: {
    justifyContent: 'center',
    paddingHorizontal: 8,
    borderBottomWidth: 1,
    borderBottomColor: '#e8e8e8',
  },
  timeHeader: {
    flexDirection: 'row',
    height: HEADER_HEIGHT,
    borderBottomWidth: 1,
    borderBottomColor: '#e8e8e8',
    backgroundColor: '#fafafa',
  },
  timeCell: {
    justifyContent: 'center',
    alignItems: 'center',
    borderRightWidth: 1,
    borderRightColor: '#f0f0f0',
  },
  timeCellText: {
    fontSize: 11,
    color: '#999',
  },
  ganttRow: {
    flexDirection: 'row',
    height: ROW_HEIGHT,
    borderBottomWidth: 1,
    borderBottomColor: '#e8e8e8',
  },
  ganttRowContent: {
    width: TIMELINE_HOURS * HOUR_WIDTH,
    position: 'relative',
    borderBottomWidth: 1,
    borderBottomColor: '#e8e8e8',
  },
  lineNameCell: {
    width: 100,
    justifyContent: 'center',
    paddingHorizontal: 8,
    backgroundColor: '#fafafa',
    borderRightWidth: 1,
    borderRightColor: '#e8e8e8',
  },
  lineNameContent: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
  },
  lineStatusDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
  },
  lineName: {
    fontSize: 12,
    fontWeight: '500',
    color: '#333',
  },
  lineWorkshop: {
    fontSize: 10,
    color: '#999',
  },
  taskArea: {
    position: 'relative',
    height: ROW_HEIGHT,
  },
  gridLine: {
    position: 'absolute',
    top: 0,
    bottom: 0,
    width: 1,
    backgroundColor: '#f0f0f0',
  },
  currentTimeLine: {
    position: 'absolute',
    top: 0,
    bottom: 0,
    width: 2,
    zIndex: 10,
  },
  currentTimeIndicator: {
    position: 'absolute',
    top: -4,
    left: -4,
    width: 10,
    height: 10,
    borderRadius: 5,
    backgroundColor: '#ff4d4f',
  },
  currentTimeLineBar: {
    position: 'absolute',
    top: 6,
    bottom: 0,
    left: 0,
    width: 2,
    backgroundColor: '#ff4d4f',
  },
  taskBar: {
    position: 'absolute',
    top: 15,
    height: 40,
    borderRadius: 4,
    borderLeftWidth: 3,
    paddingHorizontal: 6,
    paddingVertical: 4,
    justifyContent: 'center',
    overflow: 'hidden',
  },
  taskProgress: {
    position: 'absolute',
    left: 0,
    top: 0,
    bottom: 0,
    opacity: 0.3,
  },
  taskName: {
    fontSize: 11,
    fontWeight: '500',
  },
  taskProgressText: {
    fontSize: 10,
    color: '#999',
  },
  footer: {
    flexDirection: 'row',
    backgroundColor: '#fff',
    paddingVertical: 12,
    paddingHorizontal: 16,
    borderTopWidth: 1,
    borderTopColor: '#e8e8e8',
    justifyContent: 'space-around',
  },
  footerItem: {
    alignItems: 'center',
  },
  footerValue: {
    fontSize: 18,
    fontWeight: '600',
    color: DISPATCHER_THEME.primary,
  },
  footerLabel: {
    fontSize: 11,
    color: '#999',
    marginTop: 2,
  },
});
