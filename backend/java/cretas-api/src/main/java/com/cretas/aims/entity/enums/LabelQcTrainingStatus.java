package com.cretas.aims.entity.enums;

/**
 * 人工审核数据进入本地训练集前的独立决策状态。
 *
 * <p>人工审核完成只进入 PENDING；任何模型训练或导出都必须显式 APPROVED。
 */
public enum LabelQcTrainingStatus {
    PENDING,
    APPROVED,
    REJECTED
}
