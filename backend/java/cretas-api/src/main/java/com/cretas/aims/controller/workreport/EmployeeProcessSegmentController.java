package com.cretas.aims.controller.workreport;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.entity.workreport.EmployeeProcessSegment;
import com.cretas.aims.entity.workreport.EmployeeProcessSegment.CheckoutReason;
import com.cretas.aims.service.workreport.EmployeeProcessSegmentService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * P1-1 员工工序片段 REST Controller — 欠退扫码 UI 集成入口.
 *
 * <p>前端 / RN 扫码流程:
 * <ol>
 *   <li>扫码进场 → POST /start</li>
 *   <li>扫码正常下班 → POST /checkout {reason: END_OF_SHIFT}</li>
 *   <li>扫码欠退 → POST /checkout {reason: EARLY_LEAVE}</li>
 *   <li>扫码换工种 → POST /switch</li>
 *   <li>主管查看 active → GET /active</li>
 * </ol>
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/workreport/segments")
@RequiredArgsConstructor
public class EmployeeProcessSegmentController {

    private final EmployeeProcessSegmentService service;

    @RequirePermission({"work_report:read_write"})
    @PostMapping("/start")
    public ApiResponse<EmployeeProcessSegment> startSegment(
            @PathVariable @NotBlank String factoryId,
            @RequestBody Map<String, Object> body) {
        Long employeeId = Long.valueOf(body.get("employeeId").toString());
        Integer workTypeId = body.get("workTypeId") != null ? Integer.valueOf(body.get("workTypeId").toString()) : null;
        String processId = (String) body.get("processId");
        String batchId = (String) body.get("batchId");
        EmployeeProcessSegment seg = service.startSegment(factoryId, employeeId, workTypeId, processId, batchId);
        return ApiResponse.success("开启工序片段成功", seg);
    }

    @RequirePermission({"work_report:read_write"})
    @PostMapping("/checkout")
    public ApiResponse<EmployeeProcessSegment> checkOut(
            @PathVariable @NotBlank String factoryId,
            @RequestBody Map<String, Object> body) {
        Long employeeId = Long.valueOf(body.get("employeeId").toString());
        CheckoutReason reason = CheckoutReason.valueOf(
                body.getOrDefault("reason", "END_OF_SHIFT").toString());
        String notes = (String) body.get("notes");
        EmployeeProcessSegment seg = service.checkOut(factoryId, employeeId, reason, notes);
        return ApiResponse.success("结束工序片段成功", seg);
    }

    @RequirePermission({"work_report:read_write"})
    @PostMapping("/switch")
    public ApiResponse<EmployeeProcessSegment> switchWorkType(
            @PathVariable @NotBlank String factoryId,
            @RequestBody Map<String, Object> body) {
        Long employeeId = Long.valueOf(body.get("employeeId").toString());
        Integer newWorkTypeId = Integer.valueOf(body.get("newWorkTypeId").toString());
        String newProcessId = (String) body.get("newProcessId");
        EmployeeProcessSegment seg = service.switchWorkType(factoryId, employeeId, newWorkTypeId, newProcessId);
        return ApiResponse.success("切换工种成功", seg);
    }

    @GetMapping("/active")
    public ApiResponse<List<EmployeeProcessSegment>> listActive(
            @PathVariable @NotBlank String factoryId) {
        return ApiResponse.success("查询成功", service.listActive(factoryId));
    }

    /**
     * 单元C (F006 REQ-14): 汇总某员工在某工序上所有已结束片段的总工时 (整分钟).
     *
     * <p>员工一个工序段内可能多次扫码上班/下班, 客户要"所有上班下班时间"汇总.
     */
    @GetMapping("/total-minutes")
    public ApiResponse<Map<String, Object>> getTotalMinutes(
            @PathVariable @NotBlank String factoryId,
            @RequestParam Long employeeId,
            @RequestParam String processId) {
        int totalMinutes = service.getTotalMinutes(factoryId, employeeId, processId);
        return ApiResponse.success("查询成功", Map.of("totalMinutes", totalMinutes));
    }
}
