package com.cretas.aims.service.workreport;

import com.cretas.aims.entity.workreport.EmployeeProcessSegment;
import com.cretas.aims.entity.workreport.EmployeeProcessSegment.CheckoutReason;

import java.util.List;

/**
 * P1-1 员工工序片段 Service — 欠退扫码 business logic.
 *
 * <p>业务流转:
 * <ul>
 *   <li>员工扫码进场 → startSegment (开启新 ACTIVE segment)</li>
 *   <li>员工正常下班 → checkOut with END_OF_SHIFT</li>
 *   <li>员工中途欠退 → checkOut with EARLY_LEAVE</li>
 *   <li>员工切换工种 → switchWorkType (关闭当前 CLOSED_SWITCH + 开新 ACTIVE)</li>
 * </ul>
 */
public interface EmployeeProcessSegmentService {

    /**
     * 开启新工序片段 (员工扫码进场时调用).
     *
     * @param factoryId    工厂ID (factoryId 隔离)
     * @param employeeId   员工ID
     * @param workTypeId   工种ID
     * @param processId    工序ID (可选)
     * @param batchId      批次ID (可选)
     * @return 新开启的 segment
     */
    EmployeeProcessSegment startSegment(String factoryId, Long employeeId,
                                         Integer workTypeId, String processId, String batchId);

    /**
     * 结束员工当前 ACTIVE 片段 (下班 / 欠退 / 事故).
     *
     * @param factoryId 工厂ID
     * @param employeeId 员工ID
     * @param reason 结束原因
     * @param notes 备注 (可选)
     * @return 被关闭的 segment (null 若该员工当前无 ACTIVE)
     */
    EmployeeProcessSegment checkOut(String factoryId, Long employeeId,
                                     CheckoutReason reason, String notes);

    /**
     * 切换工种 (自动关闭当前 + 开新).
     *
     * @return 新 ACTIVE segment
     */
    EmployeeProcessSegment switchWorkType(String factoryId, Long employeeId,
                                           Integer newWorkTypeId, String newProcessId);

    /**
     * 查工厂内所有当前 ACTIVE 的员工片段 (for 每日扫描 / 主管仪表板).
     */
    List<EmployeeProcessSegment> listActive(String factoryId);

    /**
     * 单元C (F006 REQ-14): 汇总某员工在某工序上所有已结束片段的总工时 (整分钟).
     *
     * <p>员工一个工序段内可能多次扫码上班/下班, 客户要"所有上班下班时间"汇总.
     * 只统计已结束 (endAt 已填) 的片段; 无片段时返 0.
     *
     * @param factoryId  工厂ID (factoryId 隔离)
     * @param employeeId 员工ID
     * @param processId  工序ID
     * @return 总工时 (整分钟, 四舍五入), 无片段返 0
     */
    int getTotalMinutes(String factoryId, Long employeeId, String processId);
}
