package com.cretas.aims.ai.tool;

/** 业态兼容判断 — 单一事实源。镜像 IntentRecognitionPipelineServiceImpl 历史 v32.1 过滤逻辑。 */
public final class BusinessTypeScope {
    private BusinessTypeScope() {}

    /**
     * 意图业态是否对当前工厂业态可见(可路由/可学习)。
     * 餐饮工厂: 放行 {null, COMMON, RESTAURANT}; 其它工厂: 排除 RESTAURANT 专属。
     */
    public static boolean isCompatible(String intentBusinessType, String factoryBiz) {
        String bt = intentBusinessType;
        if ("RESTAURANT".equals(factoryBiz)) {
            return bt == null || "COMMON".equals(bt) || "RESTAURANT".equals(bt);
        }
        return !"RESTAURANT".equals(bt);
    }
}
