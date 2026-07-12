package com.cretas.aims.logistics.dto.importjob;

import lombok.Data;

import java.util.List;

/**
 * {@code POST /order-import/manual} 请求体 — 前端表单收集的结构化订单行 (无文件)，
 * 复用与 xlsx/csv 上传完全相同的校验+批次/订单创建+提交时地理编码流程 (见
 * {@code LogisticsOrderImportService#previewManual}).
 *
 * <p>{@code businessDate} 应用到全部行 (一次手动录入 = 一天订单，与"一次上传=一天排线"
 * 语义一致)；留空时与 xlsx 一行『业务日期』留空的默认行为一致 —— 校验阶段逐行报错，
 * 不静默猜测日期。
 */
@Data
public class ManualOrderCreateRequest {
    /** "YYYY-MM-DD"，可空（留空时校验阶段对每行报"业务日期必填字段为空"，与 xlsx 路径一致）。 */
    private String businessDate;
    private List<ManualOrderRow> rows;
}
