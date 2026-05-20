package com.cretas.aims.service;

import com.cretas.aims.entity.WechatRecord;
import com.cretas.aims.entity.enums.WechatDirection;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.WechatRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 微信记录 service (Sprint 6 W2-C-1, 2026-05-19).
 *
 * <p>提供 CRUD + dedup R4 防呆. 业务逻辑薄, 主要做:
 * <ul>
 *   <li>factoryId 校验 (defense-in-depth)</li>
 *   <li>direction enum 白名单校验</li>
 *   <li>5min dedup window (per fool-proof-design.md R4)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WechatRecordService {

    private final WechatRecordRepository repository;

    /** 列表查询 — factoryId + customerId 双 scope (RLS 防御). */
    public Page<WechatRecord> list(String factoryId, String customerId, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 100));
        int safePage = Math.max(0, page);
        return repository.findByFactoryIdAndCustomerIdAndDeletedAtIsNullOrderByRecordTimeDesc(
                factoryId, customerId, PageRequest.of(safePage, safeSize));
    }

    /** 单记录获取 + factoryId 校验. */
    public WechatRecord get(String factoryId, Long id) {
        WechatRecord r = repository.findById(id).orElseThrow(
                () -> new BusinessException(404, "微信记录不存在: " + id));
        if (!factoryId.equals(r.getFactoryId())) {
            throw new BusinessException(403, "无权操作此记录 (factoryId mismatch)");
        }
        return r;
    }

    /** 单 customer 当前 count — 防呆 R2 dialog header 显 "已有 N 条". */
    public long count(String factoryId, String customerId) {
        return repository.countByFactoryIdAndCustomerIdAndDeletedAtIsNull(factoryId, customerId);
    }

    /**
     * 创建 — 含防呆 R4 dedup (5min 内同 content 抛 409).
     *
     * @throws BusinessException 409 if dedup hit (with actionHint pointing to existing record)
     */
    @Transactional
    public WechatRecord create(String factoryId, String customerId, WechatDirection direction,
                                String messageContent, Long createdBy, String createdByName,
                                LocalDateTime recordTime, String contactPerson, String remark) {
        if (factoryId == null || factoryId.isBlank()) {
            throw new BusinessException(400, "factoryId 必填");
        }
        if (customerId == null || customerId.isBlank()) {
            throw new BusinessException(400, "customerId 必填");
        }
        if (direction == null) {
            throw new BusinessException(400, "direction 必填 (INBOUND/OUTBOUND/INTERNAL)");
        }
        if (messageContent == null || messageContent.isBlank()) {
            throw new BusinessException(400, "消息内容不能为空");
        }
        if (createdBy == null) {
            throw new BusinessException(400, "createdBy 必填");
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime effectiveRecordTime = recordTime != null ? recordTime : now;

        // 防呆 R4 dedup
        List<WechatRecord> recent = repository.findRecentByContent(
                factoryId, customerId, messageContent, now.minusMinutes(5));
        if (!recent.isEmpty()) {
            Long existingId = recent.get(0).getId();
            throw new BusinessException(409,
                    "5 分钟内已创建过相同内容的微信记录 (id=" + existingId + "). 请避免重复提交.")
                    .withHint("/sales/customers/" + customerId + "?tab=wechat&highlight=" + existingId);
        }

        WechatRecord r = WechatRecord.builder()
                .factoryId(factoryId)
                .customerId(customerId)
                .direction(direction)
                .messageContent(messageContent)
                .createdBy(createdBy)
                .createdByName(createdByName)
                .recordTime(effectiveRecordTime)
                .contactPerson(contactPerson)
                .remark(remark)
                .build();

        WechatRecord saved = repository.save(r);
        log.info("Created wechat record {} for customer {} (factory={}) direction={}",
                saved.getId(), customerId, factoryId, direction);
        return saved;
    }

    /**
     * 部分更新 — 仅修改 direction / messageContent / contactPerson / remark.
     * 不动 factoryId / customerId / createdBy / recordTime (防止误改归属/时间).
     */
    @Transactional
    public WechatRecord update(String factoryId, Long id, WechatDirection direction,
                                String messageContent, String contactPerson, String remark) {
        WechatRecord existing = get(factoryId, id);
        if (existing.getDeletedAt() != null) {
            throw new BusinessException(410, "记录已删除, 无法修改");
        }

        if (direction != null) existing.setDirection(direction);
        if (messageContent != null && !messageContent.isBlank()) {
            existing.setMessageContent(messageContent);
        }
        if (contactPerson != null) existing.setContactPerson(contactPerson);
        if (remark != null) existing.setRemark(remark);

        WechatRecord saved = repository.save(existing);
        log.info("Updated wechat record {} (factory={})", id, factoryId);
        return saved;
    }

    /** 软删除. */
    @Transactional
    public void delete(String factoryId, Long id) {
        WechatRecord r = get(factoryId, id);
        repository.delete(r);
        log.info("Deleted wechat record {} (factory={})", id, factoryId);
    }
}
