package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.service.aivalue.AiValueSummaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 价值汇总的 HTTP 出口（web-admin「AI 工作台」用）。
 *
 * <p>与 AI 工具 {@code system_ai_value_summary} <b>共用同一个
 * {@link AiValueSummaryService}</b>，两边都只是渲染。刻意不在这里重写查询 ——
 * 那会变成第二套口径，也就是 {@code ListSummaryServiceImpl:43-50} 记录的
 * 「footer 808 项 vs KPI 卡片近零」事故的复现条件。
 */
@Slf4j
@Tag(name = "AI 价值汇总", description = "AI 这段时间做了什么、消耗多少 token、触发多少预警")
@RestController
@RequestMapping("/api/mobile/{factoryId}/ai/value-summary")
@RequiredArgsConstructor
public class AiValueSummaryController {

    private final AiValueSummaryService aiValueSummaryService;

    @GetMapping
    @Operation(summary = "AI 价值汇总",
            description = "返回调用次数、token 用量、告警三段计数与可点开的明细。"
                    + "注意：系统未配置 token 单价，响应中的 costInYuan 恒为 null，"
                    + "原因见 costUnavailableReason —— 刻意不编费率折算金额。")
    public ResponseEntity<ApiResponse<AiValueSummaryService.Summary>> summary(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @Parameter(description = "统计最近多少天，默认 30，超出 1..365 会被夹紧")
            @RequestParam(required = false) Integer days) {

        AiValueSummaryService.Summary summary = aiValueSummaryService.summarize(factoryId, days);
        return ResponseEntity.ok(ApiResponse.success(summary));
    }
}
