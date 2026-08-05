package com.cretas.aims.ai.tool;

/**
 * 工作台岗位。
 *
 * <p>这三个岗位**不是新发明的**——它们已经存在于 workdesk 工具的注释里
 * （{@code Sprint 8 P4a 仓管员 / P4b 采购员 / P4c 质量主管 Workdesk}），且来自真实
 * 客户对话（例：{@code StockAlertWorkdeskTool} 注释记录的「F006 张权 采购员小赵场景:
 * 下周采购什么? 系统直接告诉我」）。本枚举做的是把散文里的归属提取成可查询的
 * 字段，不是重新划分岗位。
 *
 * <p>⚠️ 与权限正交：{@code ToolExecutor#hasPermission} 回答「我能不能调」（安全），
 * 本枚举回答「这是不是我的活」（发现性）。一个采购员有权限调几百个工具，
 * 但他的活只有几件。两者不得互相替代。
 */
public enum WorkdeskRole {

    WAREHOUSE_KEEPER("仓管员"),
    PURCHASER("采购员"),
    QUALITY_SUPERVISOR("质量主管");

    private final String displayName;

    WorkdeskRole(String displayName) {
        this.displayName = displayName;
    }

    /** 中文显示名，与代码注释里的岗位标记逐字一致。 */
    public String displayName() {
        return displayName;
    }

    /**
     * 按显示名反查。
     *
     * <p>认不出时**抛异常而不是返回 null**：调用方若拿到 null 往往会退化成
     * 「查不到 → 返回空列表」，而空列表与「这个岗位确实没有工具」不可区分，
     * 用户打错一个字就会被告知「你没有可干的事」（禁止降级处理）。
     */
    public static WorkdeskRole fromDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("岗位名不能为空");
        }
        String trimmed = displayName.trim();
        for (WorkdeskRole role : values()) {
            if (role.displayName.equals(trimmed)) {
                return role;
            }
        }
        throw new IllegalArgumentException("未知岗位: " + displayName);
    }
}
