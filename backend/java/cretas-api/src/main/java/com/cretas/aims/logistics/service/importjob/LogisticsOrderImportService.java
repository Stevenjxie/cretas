package com.cretas.aims.logistics.service.importjob;

import com.cretas.aims.logistics.dto.importjob.DeliveryOrderDto;
import com.cretas.aims.logistics.dto.importjob.OrderBatchDto;
import com.cretas.aims.logistics.dto.importjob.PreviewResultDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;

/**
 * 物流订单导入两段式服务 (handoff §8.2): preview 解析+校验+落库(status=PREVIEWED,
 * 不计入 live 查询) → commit 幂等翻转为 COMMITTED(计入 live 查询)。
 *
 * <p>本模块专用 import service（非复用通用 {@code ImportService} 反射引擎）— 物流订单的
 * 逐行强类型校验（数值/负数/经纬度 XOR/重复行）不适合用通用 validator DSL 安全表达
 * (spec §1: "若通用反射写实体无法安全表达物流聚合，可新增物流专用 import service")。
 */
public interface LogisticsOrderImportService {

    /** GET /order-import/template — 后端唯一模板事实源 (xlsx bytes)。 */
    byte[] downloadTemplate();

    /**
     * POST /order-import/preview — 解析+校验，upsert 幂等批次(status=PREVIEWED)并落库有效行。
     * 重复上传同一文件内容(相同 factory+business_date+source_fingerprint) 复用既有批次，
     * 不静默双写 (spec §2 决策 7)。
     */
    PreviewResultDto preview(String factoryId, MultipartFile file, Long userId);

    /** POST /order-import/{jobId}/commit — 幂等状态翻转 PREVIEWED→COMMITTED。 */
    OrderBatchDto commit(String factoryId, String jobId);

    /** GET /order-batches — 分页, 最新业务日期在前。 */
    Page<OrderBatchDto> listBatches(String factoryId, Pageable pageable);

    /** GET /order-batches/{batchId}。 */
    OrderBatchDto getBatch(String factoryId, String batchId);

    /** GET /orders?batchId=... — 分页。 */
    Page<DeliveryOrderDto> listOrders(String factoryId, String batchId, Pageable pageable);

    /** PUT /orders/{orderId}/location — 经纬度必须同时提供，成功后 locationStatus=RESOLVED。 */
    DeliveryOrderDto updateLocation(String factoryId, String orderId, BigDecimal longitude, BigDecimal latitude);
}
