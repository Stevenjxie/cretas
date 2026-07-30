package com.cretas.aims.entity.enums;

/**
 * Sprint4-H Q-PROCESS-1: 工序质检不良处理状态.
 *
 * <p>闭环流程:
 * <ul>
 *   <li>{@link #OPEN}: 检测出不良, 待处理 (默认初始状态)</li>
 *   <li>{@link #IN_PROGRESS}: 已分派处理人, 处理中 (设定 handlingAction 后)</li>
 *   <li>{@link #CLOSED}: 处理完成, 验证通过 (recordCloseFlow 后)</li>
 * </ul>
 */
public enum DefectStatus {
    OPEN("待处理", "检测出不良, 等待分派"),
    IN_PROGRESS("处理中", "已分派, 正在执行 handlingAction"),
    CLOSED("已闭环", "处理完成, 验证通过");

    private final String displayName;
    private final String description;

    DefectStatus(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }
}
