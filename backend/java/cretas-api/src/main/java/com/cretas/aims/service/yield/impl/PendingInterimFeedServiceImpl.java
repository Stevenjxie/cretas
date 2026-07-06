package com.cretas.aims.service.yield.impl;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.service.wip.WipInventoryService;
import com.cretas.aims.service.yield.PendingInterimFeedService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link PendingInterimFeedService} 实现 — 解析待小结逐工序行的 {@code upstreamSources},
 * 聚合待扣 SFI 投料量 (来源原生单位), 与 {@code InterimSettleServiceImpl} §②A 的 strict 扣减集对齐。
 *
 * <p>只读; 折算失败 (诚实 null) 跳过不减。见接口 Javadoc 的口径论证。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PendingInterimFeedServiceImpl implements PendingInterimFeedService {

    private final ProcessSheetRowRepository rowRepository;
    private final WipInventoryService wipInventoryService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public Map<String, BigDecimal> pendingSemiFeedByBatchNo(String factoryId) {
        Map<String, BigDecimal> out = new HashMap<>();
        List<ProcessSheetRow> rows = rowRepository.findUnsettledStockFeedRows(factoryId);
        for (ProcessSheetRow row : rows) {
            ProcessSheetRowRequest req = parse(row.getRowPayload());
            if (req == null || req.getUpstreamSources() == null) {
                continue;
            }
            for (ProcessSheetRowRequest.UpstreamRef ref : req.getUpstreamSources()) {
                // 只覆盖引用常驻 SFI 的 strict 投料 (与小结 §②A consumeClerkSemiStrict 一致)。
                // 其余 (in-plan 在制 WIP / FG 投料 / 计划自身前序小结半成品的 anchor 容忍扣减) 不减 (见接口 Javadoc)。
                if (!ref.isSemiFinished()) {
                    continue;
                }
                String srcBatchNo = ref.getSourceBatchNumber();
                BigDecimal feed = ref.getFeedQuantityKg();
                if (srcBatchNo == null || feed == null || feed.signum() <= 0) {
                    continue;
                }
                // 折算 kg 投料 → 来源原生单位 (盒装折盒数; kg 源原值), 与 strict 扣减折算同源。
                //   诚实 null (批次缺失 / 盒装缺每盒克重) → 跳过不减: 此时小结 strict 扣减亦 loud-fail 无法扣,
                //   货实际不离库, 不减 = 账面=实物 口径一致 (强减反而造假差异)。
                BigDecimal qtyInSourceUnit =
                        wipInventoryService.resolveSemiFeedQtyInSourceUnit(factoryId, srcBatchNo, feed);
                if (qtyInSourceUnit == null || qtyInSourceUnit.signum() <= 0) {
                    if (qtyInSourceUnit == null) {
                        log.debug("[pending-feed] SFI 折算诚实 null 跳过: factory={}, sfi={}, feedKg={}",
                                factoryId, srcBatchNo, feed);
                    }
                    continue;
                }
                out.merge(srcBatchNo, qtyInSourceUnit, BigDecimal::add);
            }
        }
        return out;
    }

    private ProcessSheetRowRequest parse(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, ProcessSheetRowRequest.class);
        } catch (Exception e) {
            log.warn("[pending-feed] 无法解析 process_sheet_row payload: {}", e.getMessage());
            return null;
        }
    }
}
