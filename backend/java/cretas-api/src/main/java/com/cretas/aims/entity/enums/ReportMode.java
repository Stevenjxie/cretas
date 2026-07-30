package com.cretas.aims.entity.enums;

/**
 * 生产报工模式 (P0-15 六扇门)
 * MODE_1: 按工序简单报 — 只录数量+耗时 (默认)
 * MODE_2: 按批次 — 需关联生产批次
 * MODE_3: 按人头 — 需拆到每个工人
 */
public enum ReportMode {
    MODE_1,
    MODE_2,
    MODE_3
}
