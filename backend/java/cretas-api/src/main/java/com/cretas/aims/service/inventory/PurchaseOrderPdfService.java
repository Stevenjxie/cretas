package com.cretas.aims.service.inventory;

/**
 * 采购订单 PDF (供货单) 导出服务
 *
 * <p>
 * 客户原始诉求 (六扇门 May 7 2026 part 2 transcript):
 * <ul>
 *   <li>"采购订单要有打印功能" — 客户需要把采购订单当作"供货单"打印出来,发给供应商。</li>
 *   <li>"扫一下上面的拳运码" (条码) — 送货员把货送到时,仓管员扫码,自动调出采购订单进入入库流程。</li>
 *   <li>"双方签字拍张照" — 签收后让仓管员拍张照,作为附件存档。</li>
 * </ul>
 *
 * <p>
 * MVP scope (本 PR):
 * <ul>
 *   <li>生成 PDF 带 Code128 条码 (内容 = 采购订单号),供应商打印后送货员带过来。</li>
 *   <li>同时附 QR 码,方便 RN App v2 扫码识别 (扫描端实现作为 follow-up issue)。</li>
 * </ul>
 *
 * @author Cretas Team
 * @since 2026-05-11 (PR P0)
 */
public interface PurchaseOrderPdfService {

    /**
     * 生成采购订单 PDF (供货单) 字节流.
     *
     * <p>RBAC defense-in-depth (PR P0-C, 2026-05-12): {@code maskPrice}=true 时
     * 单价/小计/合计 列以 "—" 占位. 与 {@link com.cretas.aims.security.PriceFieldResponseAdvice}
     * 对 JSON 响应的脱敏行为一致 (PR #423 只处理 JSON, byte[] PDF 走此路径).
     * 仓库管理员 (warehouse_manager) 等无 {@code procurement:price:view} 权限的角色
     * 仍可下载 PDF 做收货 audit, 但看不到价格.
     *
     * @param factoryId 工厂 ID (path)
     * @param orderId   采购订单 ID (path)
     * @param maskPrice {@code true} → 单价/小计/合计 显示 "—" (调用方应基于
     *                  {@code !PermissionService.hasPermission(user, "procurement:price:view")} 决定)
     * @return PDF 字节内容
     * @throws com.cretas.aims.exception.BusinessException 订单不存在 / 跨工厂访问
     */
    default byte[] generatePurchaseOrderPdf(String factoryId, String orderId, boolean maskPrice) {
        return generatePurchaseOrderPdf(factoryId, orderId, maskPrice, false);
    }

    /**
     * 生成采购订单 PDF (供货单) 字节流.
     *
     * <p>{@code externalVersion}=true 时生成供应商对外版本: 完全移除 单价/小计/合计
     * 等价格列, 只保留货品、数量、单位、件数和扫码/签收信息. 该开关独立于 RBAC,
     * 价格授权用户下载对外版时也不能带出价格.
     *
     * @param factoryId        工厂 ID (path)
     * @param orderId          采购订单 ID (path)
     * @param maskPrice        {@code true} → 内部版价格值显示 "—"
     * @param externalVersion  {@code true} → 对外版完全移除价格列和合计
     * @return PDF 字节内容
     */
    byte[] generatePurchaseOrderPdf(String factoryId, String orderId, boolean maskPrice, boolean externalVersion);
}
