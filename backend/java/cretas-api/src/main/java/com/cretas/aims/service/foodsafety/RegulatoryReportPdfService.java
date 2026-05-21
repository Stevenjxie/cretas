package com.cretas.aims.service.foodsafety;

import com.cretas.aims.entity.foodsafety.RecallAction;
import com.cretas.aims.entity.foodsafety.RecallEvent;

import java.util.List;

/**
 * 食品召回监管上报 PDF 生成服务 — Sprint 9 P1.2 Fix 2.
 *
 * <p>食品安全法第 63 条强制要求食品生产企业:
 * <ul>
 *   <li>立即停止生产 + 召回已上市销售食品</li>
 *   <li>通知相关生产经营者 + 消费者</li>
 *   <li>向所在地县级食药监部门报告召回和处理情况</li>
 * </ul>
 *
 * <p>本 service 生成符合监管上报格式的 PDF (国标简化版):
 * <ul>
 *   <li>抬头: 召回上报文件 — 鲁市监食函 [YYYY] X 号 (placeholder 编号)</li>
 *   <li>召回事件: code / 触发原因 / 涉事产品类别 / 触发时间 / 影响范围</li>
 *   <li>追溯证据: HACCP audit + GB 2760 合规复查 (从 actions 列表提取)</li>
 *   <li>召回行动记录: 冻结 / 通知 / 销毁 等 action 表格</li>
 *   <li>损失预估: 库存价值 + 退货 + 罚款 + 品牌损失</li>
 *   <li>落款 + 时间 + 责任人</li>
 * </ul>
 *
 * <p>复用 {@link com.cretas.aims.service.inventory.PurchaseOrderPdfService} 同款 iText 5.5.13
 * + iText-Asian (STSong-Light) 中文字体方案, 0 新增 PDF 库依赖.
 *
 * @author Cretas Team
 * @since 2026-05-21 (Sprint 9 P1.2)
 */
public interface RegulatoryReportPdfService {

    /**
     * 生成召回监管上报 PDF.
     *
     * @param event   召回事件 (必须 hydrated, factoryId 必填)
     * @param actions 关联的召回行动 (按 createdAt asc 排序)
     * @return PDF 字节内容; never null. 调用方决定后续是返给前端 stream 还是上传 OSS.
     * @throws com.cretas.aims.exception.BusinessException 字体加载失败 / 流写入失败
     */
    byte[] generatePdf(RecallEvent event, List<RecallAction> actions);
}
