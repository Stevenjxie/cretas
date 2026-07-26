package com.cretas.aims.service.impl;

import com.cretas.aims.dto.WhitelistDTO;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.Whitelist;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.enums.WhitelistStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.WhitelistRepository;
import com.cretas.aims.service.WhitelistService;
import com.cretas.aims.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 白名单管理服务实现
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Service
public class WhitelistServiceImpl implements WhitelistService {
    private static final Logger log = LoggerFactory.getLogger(WhitelistServiceImpl.class);

    private final WhitelistRepository whitelistRepository;
    private final UserRepository userRepository;

    // Manual constructor (Lombok @RequiredArgsConstructor not working)
    public WhitelistServiceImpl(WhitelistRepository whitelistRepository, UserRepository userRepository) {
        this.whitelistRepository = whitelistRepository;
        this.userRepository = userRepository;
    }

    @Override
    @Transactional
    public WhitelistDTO.BatchResult batchAdd(String factoryId, Long userId, WhitelistDTO.BatchAddRequest request) {
        log.info("批量添加白名单: factoryId={}, count={}", factoryId, request.getEntries().size());
        WhitelistDTO.BatchResult result = WhitelistDTO.BatchResult.builder()
                .successCount(0)
                .failedCount(0)
                .successPhones(new ArrayList<>())
                .failedEntries(new ArrayList<>())
                .build();
        Long currentUserId = userId;//SecurityUtils.getCurrentUserId();
        for (WhitelistDTO.WhitelistEntry entry : request.getEntries()) {
            try {
                // 检查是否已存在
                if (whitelistRepository.existsByFactoryIdAndPhoneNumber(factoryId, entry.getPhoneNumber())) {
                    result.getFailedEntries().add(
                            WhitelistDTO.BatchResult.FailedEntry.builder()
                                    .phoneNumber(entry.getPhoneNumber())
                                    .reason("手机号已存在")
                                    .build()
                    );
                    result.setFailedCount(result.getFailedCount() + 1);
                    continue;
                }
                // 创建白名单记录
                Whitelist whitelist = Whitelist.builder()
                        .factoryId(factoryId)
                        .phoneNumber(entry.getPhoneNumber())
                        .name(entry.getName())
                        .position(entry.getPosition())
                        .department(request.getDepartment())
                        .invitedRoleCode(parseInvitedRole(request.getRole()))
                        .status(WhitelistStatus.ACTIVE)
                        .expiresAt(request.getExpiresAt())
                        .notes(request.getNotes())
                        .addedBy(currentUserId)
                        .build();
                whitelistRepository.save(whitelist);
                result.getSuccessPhones().add(entry.getPhoneNumber());
                result.setSuccessCount(result.getSuccessCount() + 1);
            } catch (Exception e) {
                log.error("添加白名单失败: phone={}, error={}", entry.getPhoneNumber(), e.getMessage());
                result.getFailedEntries().add(
                        WhitelistDTO.BatchResult.FailedEntry.builder()
                                .phoneNumber(entry.getPhoneNumber())
                                .reason("系统错误: " + e.getMessage())
                                .build()
                );
                result.setFailedCount(result.getFailedCount() + 1);
            }
        }
        log.info("批量添加完成: success={}, failed={}", result.getSuccessCount(), result.getFailedCount());
        return result;
    }

    @Override
    public PageResponse<WhitelistDTO> getWhitelist(String factoryId, WhitelistDTO.QueryRequest queryRequest, Pageable pageable) {
        log.debug("获取白名单列表: factoryId={}, query={}", factoryId, queryRequest);
        Page<Whitelist> page;
        // 根据查询条件选择不同的查询方法
        if (StringUtils.hasText(queryRequest.getKeyword())) {
            page = whitelistRepository.search(factoryId,
                com.cretas.aims.util.SqlLikeEscaper.escape(queryRequest.getKeyword()), pageable);
        } else if (StringUtils.hasText(queryRequest.getStatus())) {
            WhitelistStatus status = WhitelistStatus.fromCode(queryRequest.getStatus());
            page = whitelistRepository.findByFactoryIdAndStatus(factoryId, status, pageable);
        } else if (StringUtils.hasText(queryRequest.getDepartment())) {
            page = whitelistRepository.findByFactoryIdAndDepartment(factoryId, queryRequest.getDepartment(), pageable);
        } else {
            page = whitelistRepository.findByFactoryId(factoryId, pageable);
        }
        List<WhitelistDTO> dtos = page.getContent().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
        return PageResponse.of(dtos, page.getNumber(), page.getSize(), page.getTotalElements());
    }

    @Override
    public WhitelistDTO getWhitelistById(String factoryId, Integer id) {
        log.debug("获取白名单详情: factoryId={}, id={}", factoryId, id);
        Whitelist whitelist = whitelistRepository.findById(id)
                .filter(w -> w.getFactoryId().equals(factoryId))
                .orElseThrow(() -> new ResourceNotFoundException("白名单记录不存在: " + id));
        return convertToDTO(whitelist);
    }

    @Override
    @Transactional
    public WhitelistDTO updateWhitelist(String factoryId, Integer id, WhitelistDTO.UpdateRequest request) {
        log.info("更新白名单: factoryId={}, id={}", factoryId, id);
        Whitelist whitelist = whitelistRepository.findById(id)
                .filter(w -> w.getFactoryId().equals(factoryId))
                .orElseThrow(() -> new ResourceNotFoundException("白名单记录不存在: " + id));

        // 更新字段
        if (StringUtils.hasText(request.getName())) {
            whitelist.setName(request.getName());
        }
        if (StringUtils.hasText(request.getDepartment())) {
            whitelist.setDepartment(request.getDepartment());
        }
        if (StringUtils.hasText(request.getPosition())) {
            whitelist.setPosition(request.getPosition());
        }
        if (StringUtils.hasText(request.getRole())) {
            if (userRepository.findByFactoryIdAndPhone(factoryId, whitelist.getPhoneNumber()).isPresent()) {
                throw new BusinessException(409, "该手机号已完成开户，不能再修改邀请角色")
                        .withHint("请到用户管理中调整账号角色");
            }
            whitelist.setInvitedRoleCode(parseInvitedRole(request.getRole()));
        }
        if (StringUtils.hasText(request.getStatus())) {
            whitelist.setStatus(WhitelistStatus.fromCode(request.getStatus()));
        }
        if (request.getExpiresAt() != null) {
            whitelist.setExpiresAt(request.getExpiresAt());
        }
        if (StringUtils.hasText(request.getNotes())) {
            whitelist.setNotes(request.getNotes());
        }
        Whitelist updated = whitelistRepository.save(whitelist);
        return convertToDTO(updated);
    }

    @Override
    @Transactional
    public void deleteWhitelist(String factoryId, Integer id) {
        log.info("删除白名单: factoryId={}, id={}", factoryId, id);
        Whitelist whitelist = whitelistRepository.findById(id)
                .filter(w -> w.getFactoryId().equals(factoryId))
                .orElseThrow(() -> new ResourceNotFoundException("白名单记录不存在: " + id));
        if (userRepository.findByFactoryIdAndPhone(factoryId, whitelist.getPhoneNumber()).isPresent()) {
            throw new BusinessException(409, "该手机号已完成开户，不能删除邀请记录")
                    .withHint("如需停用账号，请到用户管理中操作");
        }
        // 软删除
        whitelist.setStatus(WhitelistStatus.DELETED);
        whitelistRepository.save(whitelist);
    }

    @Override
    @Transactional
    public Integer batchDelete(String factoryId, List<Integer> ids) {
        log.info("批量删除白名单: factoryId={}, ids={}", factoryId, ids);
        List<Whitelist> factoryInvitations = whitelistRepository.findAllById(ids).stream()
                .filter(whitelist -> factoryId.equals(whitelist.getFactoryId()))
                .toList();
        boolean containsRegisteredAccount = factoryInvitations.stream()
                .anyMatch(whitelist -> userRepository.findByFactoryIdAndPhone(
                        factoryId, whitelist.getPhoneNumber()).isPresent());
        if (containsRegisteredAccount) {
            throw new BusinessException(409, "批量选择中包含已完成开户的手机号，不能删除邀请记录")
                    .withHint("请取消选择已开户记录；如需停用账号，请到用户管理中操作");
        }
        return whitelistRepository.batchDelete(ids, factoryId);
    }

    @Override
    public WhitelistDTO.WhitelistStats getStats(String factoryId) {
        log.debug("获取白名单统计: factoryId={}", factoryId);
        WhitelistDTO.WhitelistStats stats = WhitelistDTO.WhitelistStats.builder()
                .lastUpdated(LocalDateTime.now())
                .build();

        // 按状态统计
        List<Object[]> statusCounts = whitelistRepository.countByStatus(factoryId);
        long totalCount = 0;
        for (Object[] row : statusCounts) {
            String status = row[0].toString();
            Long count = (Long) row[1];
            totalCount += count;
            switch (WhitelistStatus.fromCode(status)) {
                case ACTIVE:
                    stats.setActiveCount(count);
                    break;
                case DISABLED:
                    stats.setDisabledCount(count);
                    break;
                case EXPIRED:
                    stats.setExpiredCount(count);
                    break;
                case LIMIT_REACHED:
                    stats.setLimitReachedCount(count);
                    break;
                default:
                    break;
            }
        }
        stats.setTotalCount(totalCount);

        // 按部门统计
        List<Object[]> deptCounts = whitelistRepository.countByDepartment(factoryId);
        Map<String, Long> countByDepartment = new HashMap<>();
        for (Object[] row : deptCounts) {
            String dept = row[0] != null ? row[0].toString() : "未分配";
            Long count = (Long) row[1];
            countByDepartment.put(dept, count);
        }
        stats.setCountByDepartment(countByDepartment);

        // 获取今日新增
        stats.setTodayAddedCount(whitelistRepository.countTodayAdded(factoryId));

        // 获取即将过期（7天内）
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime warningDate = now.plusDays(7);
        List<Whitelist> expiringSoon = whitelistRepository.findExpiringSoon(factoryId, now, warningDate);
        stats.setExpiringSoonCount((long) expiringSoon.size());
        stats.setExpiringSoonUsers(
                expiringSoon.stream()
                        .limit(10)
                        .map(this::convertToDTO)
                        .collect(Collectors.toList())
        );

        // 不再追踪使用次数和使用时间，设置默认值
        stats.setActiveUsersCount(whitelistRepository.countRegisteredAccounts(factoryId));
        stats.setMostActiveUsers(new ArrayList<>());
        stats.setRecentlyUsedUsers(new ArrayList<>());
        stats.setTotalUsageCount(0L);
        stats.setAverageUsage(0.0);

        return stats;
    }

    @Override
    @Transactional
    public Integer updateExpiredWhitelist() {
        log.info("更新过期的白名单状态");
        return whitelistRepository.updateExpiredStatus(LocalDateTime.now());
    }

    @Override
    @Transactional
    public Integer updateLimitReachedWhitelist() {
        log.info("更新达到使用上限的白名单状态（已废弃，无使用限制功能）");
        return 0; // 不再有使用限制功能
    }

    @Override
    public WhitelistDTO.ValidationResponse validatePhoneNumber(String factoryId, String phoneNumber) {
        log.debug("验证手机号: factoryId={}, phone={}", factoryId, phoneNumber);
        Optional<Whitelist> optional = whitelistRepository.findByFactoryIdAndPhoneNumber(factoryId, phoneNumber);
        if (!optional.isPresent()) {
            return WhitelistDTO.ValidationResponse.builder()
                    .isValid(false)
                    .phone(phoneNumber)
                    .invalidReason("手机号不在白名单中")
                    .build();
        }
        Whitelist whitelist = optional.get();
        if (!whitelist.isValid()) {
            String reason = "白名单无效";
            if (whitelist.getStatus() == WhitelistStatus.DISABLED) {
                reason = "白名单已禁用";
            } else if (whitelist.getStatus() == WhitelistStatus.EXPIRED) {
                reason = "白名单已过期";
            } else if (whitelist.getStatus() == WhitelistStatus.LIMIT_REACHED) {
                reason = "已达使用上限";
            }
            return WhitelistDTO.ValidationResponse.builder()
                    .isValid(false)
                    .phone(phoneNumber)
                    .name(whitelist.getName())
                    .invalidReason(reason)
                    .build();
        }

        // 不再支持权限和使用次数功能
        return WhitelistDTO.ValidationResponse.builder()
                .isValid(true)
                .phone(phoneNumber)
                .name(whitelist.getName())
                .role(whitelist.getInvitedRoleCode() != null ? whitelist.getInvitedRoleCode().name() : null)
                .permissions(null) // 权限字段已删除
                .expiresAt(whitelist.getExpiresAt())
                .remainingUsage(null) // 使用次数功能已删除
                .build();
    }

    @Override
    @Transactional
    public void incrementUsage(String factoryId, String phoneNumber) {
        log.debug("增加使用次数（已废弃）: factoryId={}, phone={}", factoryId, phoneNumber);
        // 使用次数追踪功能已删除，此方法保留为空实现以保持接口兼容性
    }

    @Override
    public PageResponse<WhitelistDTO> searchWhitelist(String factoryId, String keyword, Pageable pageable) {
        log.debug("搜索白名单: factoryId={}, keyword={}", factoryId, keyword);
        Page<Whitelist> page = whitelistRepository.search(factoryId,
            com.cretas.aims.util.SqlLikeEscaper.escape(keyword), pageable);
        List<WhitelistDTO> dtos = page.getContent().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
        return PageResponse.of(dtos, page.getNumber(), page.getSize(), page.getTotalElements());
    }

    @Override
    public List<WhitelistDTO> getExpiringSoon(String factoryId, Integer days) {
        log.debug("获取即将过期的白名单: factoryId={}, days={}", factoryId, days);
        int daysToCheck = days != null ? days : 7;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime warningDate = now.plusDays(daysToCheck);
        List<Whitelist> expiring = whitelistRepository.findExpiringSoon(factoryId, now, warningDate);
        return expiring.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<WhitelistDTO> getMostActiveUsers(String factoryId, Integer limit) {
        log.debug("获取最活跃用户（已废弃）: factoryId={}, limit={}", factoryId, limit);
        // 使用追踪功能已删除，返回空列表
        return new ArrayList<>();
    }

    @Override
    public List<WhitelistDTO> getRecentlyUsed(String factoryId, Integer limit) {
        log.debug("获取最近使用（已废弃）: factoryId={}, limit={}", factoryId, limit);
        // 使用追踪功能已删除，返回空列表
        return new ArrayList<>();
    }

    @Override
    public String exportWhitelist(String factoryId, String status) {
        log.info("导出白名单: factoryId={}, status={}", factoryId, status);
        StringBuilder csv = new StringBuilder();
        csv.append("手机号,姓名,部门,职位,状态,过期时间,添加时间\n");
        Pageable pageable = PageRequest.of(0, 1000, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Whitelist> page;
        if (StringUtils.hasText(status)) {
            WhitelistStatus whitelistStatus = WhitelistStatus.fromCode(status);
            page = whitelistRepository.findByFactoryIdAndStatus(factoryId, whitelistStatus, pageable);
        } else {
            page = whitelistRepository.findByFactoryId(factoryId, pageable);
        }
        for (Whitelist w : page.getContent()) {
            csv.append(String.format("%s,%s,%s,%s,%s,%s,%s\n",
                    w.getPhoneNumber(),
                    w.getName() != null ? w.getName() : "",
                    w.getDepartment() != null ? w.getDepartment() : "",
                    w.getPosition() != null ? w.getPosition() : "",
                    w.getStatus().getDescription(),
                    w.getExpiresAt() != null ? w.getExpiresAt().toString() : "",
                    w.getCreatedAt()
            ));
        }
        return csv.toString();
    }

    @Override
    @Transactional
    public WhitelistDTO.BatchResult importWhitelist(String factoryId, String csvData) {
        log.info("导入白名单: factoryId={}", factoryId);
        WhitelistDTO.BatchResult result = WhitelistDTO.BatchResult.builder()
                .successCount(0)
                .failedCount(0)
                .successPhones(new ArrayList<>())
                .failedEntries(new ArrayList<>())
                .build();

        String[] lines = csvData.split("\n");
        Long currentUserId = SecurityUtils.getCurrentUserId();

        // 跳过标题行
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            String[] fields = line.split(",");
            if (fields.length < 4) {
                result.getFailedEntries().add(
                        WhitelistDTO.BatchResult.FailedEntry.builder()
                                .phoneNumber(line)
                                .reason("格式错误")
                                .build()
                );
                result.setFailedCount(result.getFailedCount() + 1);
                continue;
            }

            String phoneNumber = fields[0].trim();
            String name = fields[1].trim();
            String department = fields[2].trim();
            String position = fields[3].trim();

            try {
                if (whitelistRepository.existsByFactoryIdAndPhoneNumber(factoryId, phoneNumber)) {
                    result.getFailedEntries().add(
                            WhitelistDTO.BatchResult.FailedEntry.builder()
                                    .phoneNumber(phoneNumber)
                                    .reason("手机号已存在")
                                    .build()
                    );
                    result.setFailedCount(result.getFailedCount() + 1);
                    continue;
                }

                Whitelist whitelist = Whitelist.builder()
                        .factoryId(factoryId)
                        .phoneNumber(phoneNumber)
                        .name(name)
                        .department(department)
                        .position(position)
                        .status(WhitelistStatus.ACTIVE)
                        .addedBy(currentUserId)
                        .build();
                whitelistRepository.save(whitelist);
                result.getSuccessPhones().add(phoneNumber);
                result.setSuccessCount(result.getSuccessCount() + 1);
            } catch (Exception e) {
                log.error("导入白名单失败: phone={}, error={}", phoneNumber, e.getMessage());
                result.getFailedEntries().add(
                        WhitelistDTO.BatchResult.FailedEntry.builder()
                                .phoneNumber(phoneNumber)
                                .reason("系统错误: " + e.getMessage())
                                .build()
                );
                result.setFailedCount(result.getFailedCount() + 1);
            }
        }
        log.info("导入完成: success={}, failed={}", result.getSuccessCount(), result.getFailedCount());
        return result;
    }

    @Override
    @Transactional
    public Integer cleanupDeleted(Integer daysOld) {
        log.info("清理已删除记录: daysOld={}", daysOld);
        int days = daysOld != null ? daysOld : 30;
        LocalDateTime beforeDate = LocalDateTime.now().minusDays(days);
        return whitelistRepository.cleanupDeleted(beforeDate);
    }

    @Override
    @Transactional
    public void resetUsageCount(String factoryId, Integer id) {
        log.info("重置使用次数（已废弃）: factoryId={}, id={}", factoryId, id);
        // 使用次数功能已删除，保留空实现以保持接口兼容性
    }

    @Override
    @Transactional
    public WhitelistDTO extendExpiration(String factoryId, Integer id, Integer days) {
        log.info("延长有效期: factoryId={}, id={}, days={}", factoryId, id, days);
        Whitelist whitelist = whitelistRepository.findById(id)
                .filter(w -> w.getFactoryId().equals(factoryId))
                .orElseThrow(() -> new ResourceNotFoundException("白名单记录不存在: " + id));

        LocalDateTime newExpiration;
        if (whitelist.getExpiresAt() == null || whitelist.getExpiresAt().isBefore(LocalDateTime.now())) {
            newExpiration = LocalDateTime.now().plusDays(days);
        } else {
            newExpiration = whitelist.getExpiresAt().plusDays(days);
        }
        whitelist.setExpiresAt(newExpiration);

        // 如果状态是已过期，重置为活跃
        if (whitelist.getStatus() == WhitelistStatus.EXPIRED) {
            whitelist.setStatus(WhitelistStatus.ACTIVE);
        }

        Whitelist updated = whitelistRepository.save(whitelist);
        return convertToDTO(updated);
    }

    /**
     * 转换实体到DTO
     */
    private WhitelistDTO convertToDTO(Whitelist whitelist) {
        WhitelistDTO dto = WhitelistDTO.builder()
                .id(whitelist.getId())
                .factoryId(whitelist.getFactoryId())
                .phoneNumber(whitelist.getPhoneNumber())
                .name(whitelist.getName())
                .department(whitelist.getDepartment())
                .position(whitelist.getPosition())
                .status(whitelist.getStatus().getCode())
                .expiresAt(whitelist.getExpiresAt())
                .lastUsedAt(null) // 已删除
                .usageCount(null) // 已删除
                .maxUsageCount(null) // 已删除
                .role(whitelist.getInvitedRoleCode() != null ? whitelist.getInvitedRoleCode().name() : null)
                .notes(whitelist.getNotes())
                .addedBy(whitelist.getAddedBy())
                .createdAt(whitelist.getCreatedAt())
                .updatedAt(whitelist.getUpdatedAt())
                .build();

        Optional<User> registeredUser = userRepository.findByFactoryIdAndPhone(
                whitelist.getFactoryId(), whitelist.getPhoneNumber());
        dto.setAccountCreated(registeredUser.isPresent());
        dto.setAccountActive(registeredUser.map(User::getIsActive).orElse(false));
        registeredUser.ifPresent(user -> {
            dto.setRegisteredUserId(user.getId());
            dto.setRegisteredUsername(user.getUsername());
        });

        // 权限字段已删除
        dto.setPermissions(null);

        // 计算字段
        dto.setIsValid(whitelist.isValid());
        dto.setIsExpiringSoon(whitelist.isExpiringSoon());

        // 使用次数功能已删除
        dto.setRemainingUsage(null);

        if (whitelist.getExpiresAt() != null) {
            long daysUntilExpiry = java.time.Duration.between(LocalDateTime.now(), whitelist.getExpiresAt()).toDays();
            dto.setDaysUntilExpiry((int) daysUntilExpiry);
        }

        // 获取添加人姓名
        if (whitelist.getAddedBy() != null) {
            userRepository.findById(whitelist.getAddedBy())
                    .ifPresent(user -> dto.setAddedByName(user.getName()));
        }

        return dto;
    }

    /**
     * 解析管理员预设角色。空值保留历史白名单兼容语义；非空值必须是工厂角色，
     * 且禁止借白名单创建平台管理员或未激活伪角色。
     */
    private FactoryUserRole parseInvitedRole(String roleCode) {
        if (!StringUtils.hasText(roleCode)) {
            return null;
        }

        final FactoryUserRole role;
        try {
            role = FactoryUserRole.valueOf(roleCode.trim());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(400, "无效的工厂角色: " + roleCode)
                    .withHint("请从系统角色列表中重新选择");
        }

        if (role == FactoryUserRole.platform_admin || role == FactoryUserRole.unactivated) {
            throw new BusinessException(400, "该角色不能用于工厂白名单邀请")
                    .withHint("请选择有效的工厂账号角色");
        }
        return role;
    }
}
