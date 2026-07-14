package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.SystemLog;
import com.cretas.aims.repository.SystemLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/mobile/{factoryId}/system-logs")
public class SystemLogController {

    @Autowired
    private SystemLogRepository systemLogRepository;

    @GetMapping
    public ApiResponse<Map<String, Object>> getLogs(
            @PathVariable String factoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String logType,
            @RequestParam(required = false) String logLevel,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {

        LocalDateTime start = startDate != null ? LocalDate.parse(startDate).atStartOfDay() : null;
        LocalDateTime end = endDate != null ? LocalDate.parse(endDate).atTime(LocalTime.MAX) : null;
        String kw = (keyword != null && !keyword.trim().isEmpty()) ? keyword.trim() : null;
        String lt = (logType != null && !logType.trim().isEmpty()) ? logType.trim() : null;
        String ll = (logLevel != null && !logLevel.trim().isEmpty()) ? logLevel.trim() : null;
        String un = (username != null && !username.trim().isEmpty()) ? username.trim() : null;

        Page<SystemLog> result = systemLogRepository.searchLogs(
                factoryId, lt, ll, un, kw, start, end,
                PageRequest.of(page, size));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("content", result.getContent());
        data.put("totalElements", result.getTotalElements());
        data.put("totalPages", result.getTotalPages());
        data.put("number", result.getNumber());

        return ApiResponse.success(data);
    }
}
