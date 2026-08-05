package com.cretas.aims.service.finding;

import java.util.List;

/**
 * 一条发现规则。每条规则一个 {@code @Component} 实现，由
 * {@link FindingService} 按 {@link #domain()} 收集。
 *
 * <p>⛔ 实现禁止新写口径 SQL。规则必须复用已有的 service 方法，否则会出现
 * 同名指标两套定义（见 ListSummaryServiceImpl.java:43-50 记录的 footer
 * 「808项」vs KPI 卡片接近 0 的事故）。
 */
public interface FindingProvider {

    /** 领域，对齐 ListSummaryService 的 entityType 词汇（inventory / salesOrder / ...）。 */
    String domain();

    /** 规则的人类可读名，会出现在「已检查 XXX，均正常」里。如「低库存」。 */
    String ruleName();

    /** 执行检测。返回空列表表示本规则未发现异常。 */
    List<Finding> detect(String factoryId);
}
