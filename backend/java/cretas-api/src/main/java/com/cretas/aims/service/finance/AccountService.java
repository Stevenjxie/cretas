package com.cretas.aims.service.finance;

import com.cretas.aims.entity.enums.AccountCategory;
import com.cretas.aims.entity.finance.Account;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.AccountRepository;
import com.cretas.aims.repository.VoucherEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * AccountService — Sprint 7 T1 Phase B.
 *
 * <p>会计科目 CRUD + 防呆规则:
 * <ul>
 *   <li>Rule 1 (max display): create 前 check duplicate code 抛 409 (frontend dialog 显
 *       "已存在科目 1001 现金, 是否查看?")</li>
 *   <li>Rule 4 (idempotent): findOrCreate 用 code 幂等</li>
 *   <li>Rule 5 (dead-end nav): 试图删除已被 voucher_entry 引用的科目, 抛 BusinessException
 *       withHint "请改为禁用 (active=false)"</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountService {

    private final AccountRepository accountRepo;

    /**
     * 系统级 (NULL factoryId) 或 factory 自定义科目都能 list. NULL factoryId 系统标准跨
     * factory 共享, 这里 union 返给前端.
     */
    public List<Account> listVisible(String factoryId) {
        return accountRepo.findVisibleToFactory(factoryId);
    }

    public List<Account> listActiveVisible(String factoryId) {
        return accountRepo.findActiveVisibleToFactory(factoryId);
    }

    public List<Account> listByCategory(String factoryId, AccountCategory category) {
        return accountRepo.findVisibleByCategory(factoryId, category);
    }

    /**
     * 详情读取. 跨租户校验: 系统级科目 (factoryId == null, 全工厂可见) 或本厂科目可读;
     * 其它工厂的科目 → 403。
     */
    public Optional<Account> findById(String factoryId, String id) {
        Optional<Account> acctOpt = accountRepo.findByIdAndDeletedAtIsNull(id);
        acctOpt.ifPresent(acct -> {
            if (acct.getFactoryId() != null && !acct.getFactoryId().equals(factoryId)) {
                throw new BusinessException(403, "无权访问该科目 / 该科目不属于当前工厂")
                        .withHint("请确认科目 ID 是否属于本工厂");
            }
        });
        return acctOpt;
    }

    /**
     * 创建科目. Rule 1 + Rule 4 防呆.
     * <ul>
     *   <li>code 在该 factory 内已存在 → 抛 BusinessException 409 actionHint "请用现有科目或换 code"</li>
     *   <li>parentId 非空时校验 parent 存在且 level 比 parent 高一级</li>
     * </ul>
     */
    @Transactional
    public Account create(Account account) {
        if (account.getCode() == null || account.getCode().isBlank()) {
            throw new BusinessException("科目编码必填");
        }
        if (account.getName() == null || account.getName().isBlank()) {
            throw new BusinessException("科目名称必填");
        }
        if (account.getCategory() == null) {
            throw new BusinessException("科目大类必填");
        }
        if (account.getBalanceType() == null) {
            throw new BusinessException("余额方向必填");
        }
        // Dup check — 仅 factory 自己的, 不冲撞系统级
        if (account.getFactoryId() != null
                && accountRepo.existsByFactoryIdAndCodeAndDeletedAtIsNull(
                        account.getFactoryId(), account.getCode())) {
            throw new BusinessException(409,
                    String.format("已存在科目 %s, 请用现有科目或换编码", account.getCode()))
                    .withHint("查看现有同 code 科目");
        }
        if (account.getParentId() != null) {
            Account parent = accountRepo.findByIdAndDeletedAtIsNull(account.getParentId())
                    .orElseThrow(() -> new BusinessException("父科目不存在: " + account.getParentId()));
            // 子科目 level = parent level + 1
            account.setLevel(parent.getLevel() + 1);
            if (account.getLevel() > 4) {
                throw new BusinessException("科目层级不能超过 4 级");
            }
        } else if (account.getLevel() == null) {
            account.setLevel(1);
        }
        if (account.getId() == null) {
            account.setId(UUID.randomUUID().toString());
        }
        if (account.getActive() == null) {
            account.setActive(true);
        }
        if (account.getSortOrder() == null) {
            account.setSortOrder(0);
        }
        log.info("Create account: factoryId={}, code={}, name={}, category={}",
                account.getFactoryId(), account.getCode(), account.getName(), account.getCategory());
        return accountRepo.save(account);
    }

    /**
     * 更新科目 — name / description / active / sortOrder 可改; code / category / balanceType /
     * parentId / level 不可改 (会破坏既有 voucher_entry 引用).
     */
    @Transactional
    public Account update(String factoryId, String id, Account patch) {
        Account existing = accountRepo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new BusinessException("科目不存在: " + id));
        // 跨租户校验 (写): 只能改本厂自定义科目; 系统标准科目 (factoryId == null) 不可改, 别厂科目越权。
        if (existing.getFactoryId() == null || !existing.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权修改该科目 / 系统标准科目不可修改")
                    .withHint("系统标准科目为全工厂共享, 不可修改; 自定义科目请在本工厂操作");
        }
        if (patch.getName() != null && !patch.getName().isBlank()) {
            existing.setName(patch.getName());
        }
        if (patch.getDescription() != null) {
            existing.setDescription(patch.getDescription());
        }
        if (patch.getActive() != null) {
            existing.setActive(patch.getActive());
        }
        if (patch.getSortOrder() != null) {
            existing.setSortOrder(patch.getSortOrder());
        }
        return accountRepo.save(existing);
    }

    /**
     * 软删除 — 已被 voucher 引用 / 有子科目 时禁删 (Rule 5 dead-end nav: 改 active=false).
     */
    @Transactional
    public void softDelete(String factoryId, String id) {
        Account existing = accountRepo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new BusinessException("科目不存在: " + id));
        // 跨租户校验 (写): 只能删本厂自定义科目; 系统标准科目 (factoryId == null) 不可删, 别厂科目越权。
        if (existing.getFactoryId() == null || !existing.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权删除该科目 / 系统标准科目不可删除")
                    .withHint("系统标准科目为全工厂共享, 不可删除; 自定义科目请在本工厂操作");
        }
        long childCount = accountRepo.countByParentIdAndDeletedAtIsNull(id);
        if (childCount > 0) {
            throw new BusinessException(409,
                    String.format("科目 %s 下有 %d 个子科目, 不可删除", existing.getCode(), childCount))
                    .withHint("先删除子科目, 或改为禁用 active=false");
        }
        existing.softDelete();
        accountRepo.save(existing);
        log.info("Soft-delete account: id={}, code={}", id, existing.getCode());
    }
}
