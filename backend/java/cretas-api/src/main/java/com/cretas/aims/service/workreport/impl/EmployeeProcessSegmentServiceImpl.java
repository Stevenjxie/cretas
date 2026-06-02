package com.cretas.aims.service.workreport.impl;

import com.cretas.aims.entity.workreport.EmployeeProcessSegment;
import com.cretas.aims.entity.workreport.EmployeeProcessSegment.CheckoutReason;
import com.cretas.aims.entity.workreport.EmployeeProcessSegment.SegmentStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.workreport.EmployeeProcessSegmentRepository;
import com.cretas.aims.service.workreport.EmployeeProcessSegmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * P1-1 员工工序片段 Service 实现.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmployeeProcessSegmentServiceImpl implements EmployeeProcessSegmentService {

    private final EmployeeProcessSegmentRepository repository;

    @Override
    @Transactional
    public EmployeeProcessSegment startSegment(String factoryId, Long employeeId,
                                                Integer workTypeId, String processId, String batchId) {
        // 先检查是否已有 ACTIVE segment, 若有先 close (一个员工同时只能有一个 ACTIVE)
        List<EmployeeProcessSegment> existing = repository
                .findByFactoryIdAndEmployeeIdAndStatusAndDeletedAtIsNull(factoryId, employeeId, SegmentStatus.ACTIVE);
        if (!existing.isEmpty()) {
            throw new BusinessException(409, "员工 " + employeeId + " 已有进行中的工序片段")
                    .withHint("请先结束当前工序或使用切换工种").withHintTarget("employeeId");
        }

        EmployeeProcessSegment seg = new EmployeeProcessSegment();
        seg.setFactoryId(factoryId);
        seg.setEmployeeId(employeeId);
        seg.setWorkTypeId(workTypeId);
        seg.setProcessId(processId);
        seg.setBatchId(batchId);
        seg.setStartAt(LocalDateTime.now());
        seg.setStatus(SegmentStatus.ACTIVE);

        EmployeeProcessSegment saved = repository.save(seg);
        log.info("[P1-1] 员工 {} 开启工序片段: id={} workType={} process={}",
                employeeId, saved.getId(), workTypeId, processId);
        return saved;
    }

    @Override
    @Transactional
    public EmployeeProcessSegment checkOut(String factoryId, Long employeeId,
                                            CheckoutReason reason, String notes) {
        List<EmployeeProcessSegment> active = repository
                .findByFactoryIdAndEmployeeIdAndStatusAndDeletedAtIsNull(factoryId, employeeId, SegmentStatus.ACTIVE);
        if (active.isEmpty()) {
            throw new BusinessException(409, "员工 " + employeeId + " 当前无进行中的工序片段")
                    .withHint("请先开启工序片段").withHintTarget("employeeId");
        }
        if (active.size() > 1) {
            log.warn("[P1-1] 员工 {} 有多于 1 个 ACTIVE segment (数据异常, 共 {} 条)", employeeId, active.size());
        }
        EmployeeProcessSegment seg = active.get(0);
        seg.setEndAt(LocalDateTime.now());
        seg.setCheckoutReason(reason);
        seg.setStatus(reason == CheckoutReason.EARLY_LEAVE ? SegmentStatus.CLOSED_EARLY
                : reason == CheckoutReason.WORK_TYPE_SWITCH ? SegmentStatus.CLOSED_SWITCH
                : SegmentStatus.CLOSED_NORMAL);
        if (notes != null) seg.setNotes(notes);
        EmployeeProcessSegment saved = repository.save(seg);
        log.info("[P1-1] 员工 {} 工序片段结束: id={} reason={} status={}",
                employeeId, saved.getId(), reason, saved.getStatus());
        return saved;
    }

    @Override
    @Transactional
    public EmployeeProcessSegment switchWorkType(String factoryId, Long employeeId,
                                                  Integer newWorkTypeId, String newProcessId) {
        // Close current with WORK_TYPE_SWITCH reason
        checkOut(factoryId, employeeId, CheckoutReason.WORK_TYPE_SWITCH, "切换到工种 " + newWorkTypeId);
        // Start new
        return startSegment(factoryId, employeeId, newWorkTypeId, newProcessId, null);
    }

    @Override
    public List<EmployeeProcessSegment> listActive(String factoryId) {
        return repository.findByFactoryIdAndStatusAndDeletedAtIsNull(factoryId, SegmentStatus.ACTIVE);
    }

    @Override
    public int getTotalMinutes(String factoryId, Long employeeId, String processId) {
        Double minutes = repository.sumMinutesByEmployeeAndProcess(factoryId, employeeId, processId);
        if (minutes == null) {
            return 0;
        }
        // 四舍五入到整分钟 (round-half-up)
        return (int) Math.round(minutes);
    }
}
