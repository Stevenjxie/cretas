package com.cretas.aims.service.supplier;

import com.cretas.aims.dto.supplier.SupplierAddressDTO;
import com.cretas.aims.dto.supplier.SupplierBankAccountDTO;
import com.cretas.aims.dto.supplier.SupplierContactDTO;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.SupplierAddress;
import com.cretas.aims.entity.SupplierBankAccount;
import com.cretas.aims.entity.SupplierContact;
import com.cretas.aims.entity.enums.SupplierAddressType;
import com.cretas.aims.entity.enums.SupplierContactType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.repository.SupplierAddressRepository;
import com.cretas.aims.repository.SupplierBankAccountRepository;
import com.cretas.aims.repository.SupplierContactRepository;
import com.cretas.aims.repository.SupplierRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static com.cretas.aims.service.supplier.SupplierProfileValidator.trimToNull;

/**
 * {@link SupplierProfileService} 实现。
 *
 * <p>每个写方法的收尾都是 {@code syncPrimaryMirror*}, 这是本类存在的理由 ——
 * 见接口 javadoc 的「核心不变式」。
 */
@Service
public class SupplierProfileServiceImpl implements SupplierProfileService {

    private static final Logger log = LoggerFactory.getLogger(SupplierProfileServiceImpl.class);

    /** 单个供应商下的条目上限 —— 防呆: 挡住误操作/脚本刷爆, 也让下拉不至于长到不可用。 */
    private static final int MAX_ITEMS_PER_SUPPLIER = 20;

    private final SupplierRepository supplierRepository;
    private final SupplierContactRepository contactRepository;
    private final SupplierAddressRepository addressRepository;
    private final SupplierBankAccountRepository bankAccountRepository;

    public SupplierProfileServiceImpl(SupplierRepository supplierRepository,
                                      SupplierContactRepository contactRepository,
                                      SupplierAddressRepository addressRepository,
                                      SupplierBankAccountRepository bankAccountRepository) {
        this.supplierRepository = supplierRepository;
        this.contactRepository = contactRepository;
        this.addressRepository = addressRepository;
        this.bankAccountRepository = bankAccountRepository;
    }

    // ───────────────────────────────── 读 ─────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<SupplierContactDTO> listContacts(String factoryId, String supplierId) {
        requireSupplier(factoryId, supplierId);
        return loadContacts(factoryId, supplierId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SupplierAddressDTO> listAddresses(String factoryId, String supplierId) {
        requireSupplier(factoryId, supplierId);
        return loadAddresses(factoryId, supplierId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SupplierBankAccountDTO> listBankAccounts(String factoryId, String supplierId) {
        requireSupplier(factoryId, supplierId);
        return loadBankAccounts(factoryId, supplierId);
    }

    // ──────────────────────────────── 联系人 ────────────────────────────────

    @Override
    @Transactional
    public List<SupplierContactDTO> saveContact(String factoryId, String supplierId,
                                                SupplierContactDTO dto) {
        Supplier supplier = requireSupplier(factoryId, supplierId);

        String name = trimToNull(dto.getName());
        if (name == null) {
            throw new BusinessException(400, "联系人姓名不能为空")
                    .withHint("请填写联系人姓名").withHintTarget("name");
        }
        String phone = trimToNull(dto.getPhone());

        SupplierContact entity;
        boolean creating = trimToNull(dto.getId()) == null;
        if (creating) {
            if (contactRepository.countBySupplier(factoryId, supplierId) >= MAX_ITEMS_PER_SUPPLIER) {
                throw tooMany("联系人");
            }
            entity = new SupplierContact();
            entity.setFactoryId(factoryId);
            entity.setSupplierId(supplierId);
        } else {
            entity = contactRepository.findByIdAndFactoryId(dto.getId(), factoryId)
                    .orElseThrow(() -> new EntityNotFoundException("SupplierContact", dto.getId()));
            requireSameSupplier(entity.getSupplierId(), supplierId, "联系人");
            checkVersion(dto.getVersion(), entity.getVersion(), SupplierContact.class, entity.getId());
        }

        entity.setName(name);
        entity.setContactType(dto.getContactType() != null ? dto.getContactType() : SupplierContactType.OTHER);
        entity.setPhone(phone);
        entity.setEmail(trimToNull(dto.getEmail()));
        entity.setPosition(trimToNull(dto.getPosition()));
        entity.setNotes(trimToNull(dto.getNotes()));
        entity.setSortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0);

        // 第一条自动成为主联系人 —— 否则镜像列会空, 而 SupplierProfileValidator
        // 要求 contact_person 非空, 供应商会变成 profileComplete = false。
        boolean wantPrimary = Boolean.TRUE.equals(dto.getIsPrimary())
                || contactRepository.countBySupplier(factoryId, supplierId) == 0;
        entity.setIsPrimary(wantPrimary ? Boolean.TRUE : Boolean.TRUE.equals(entity.getIsPrimary()));

        if (Boolean.TRUE.equals(entity.getIsPrimary())) {
            // 部分唯一索引不可延迟检查 —— 必须先把旧主清掉再落新主。
            demoteOtherPrimaryContacts(factoryId, supplierId, entity.getId());
        }
        contactRepository.save(entity);

        syncPrimaryContactMirror(supplier, factoryId, supplierId);
        return loadContacts(factoryId, supplierId);
    }

    @Override
    @Transactional
    public List<SupplierContactDTO> deleteContact(String factoryId, String supplierId, String contactId) {
        Supplier supplier = requireSupplier(factoryId, supplierId);
        SupplierContact entity = contactRepository.findByIdAndFactoryId(contactId, factoryId)
                .orElseThrow(() -> new EntityNotFoundException("SupplierContact", contactId));
        requireSameSupplier(entity.getSupplierId(), supplierId, "联系人");

        if (contactRepository.countBySupplier(factoryId, supplierId) <= 1) {
            throw new BusinessException(409, "至少要保留一个联系人，不能删除最后一条")
                    .withHint("请先新增另一个联系人，再删除这条")
                    .withHintTarget("contacts");
        }

        boolean wasPrimary = Boolean.TRUE.equals(entity.getIsPrimary());
        contactRepository.delete(entity);

        if (wasPrimary) {
            // 主联系人被删 → 顺位提升下一条, 否则镜像列会被清空。
            contactRepository.findBySupplier(factoryId, supplierId).stream().findFirst()
                    .ifPresent(next -> {
                        next.setIsPrimary(true);
                        contactRepository.save(next);
                    });
        }
        syncPrimaryContactMirror(supplier, factoryId, supplierId);
        return loadContacts(factoryId, supplierId);
    }

    private void demoteOtherPrimaryContacts(String factoryId, String supplierId, String keepId) {
        List<SupplierContact> demoted = contactRepository.findBySupplier(factoryId, supplierId).stream()
                .filter(c -> Boolean.TRUE.equals(c.getIsPrimary()))
                .filter(c -> keepId == null || !keepId.equals(c.getId()))
                .peek(c -> c.setIsPrimary(false))
                .collect(Collectors.toList());
        if (!demoted.isEmpty()) {
            contactRepository.saveAll(demoted);
            contactRepository.flush();
        }
    }

    /**
     * 把主联系人回写到 {@code suppliers.contact_person / phone / email}。
     * 采购单 PDF / 准入摘要 / 导出 / AI Tool 全部读这三列。
     */
    private void syncPrimaryContactMirror(Supplier supplier, String factoryId, String supplierId) {
        contactRepository.findPrimary(factoryId, supplierId).ifPresent(primary -> {
            supplier.setContactPerson(primary.getName());
            supplier.setPhone(primary.getPhone());
            supplier.setEmail(primary.getEmail());
            supplierRepository.save(supplier);
            log.info("同步主联系人到供应商主档: factoryId={}, supplierId={}, contact={}",
                    factoryId, supplierId, primary.getName());
        });
    }

    // ───────────────────────────────── 地址 ─────────────────────────────────

    @Override
    @Transactional
    public List<SupplierAddressDTO> saveAddress(String factoryId, String supplierId,
                                                SupplierAddressDTO dto) {
        Supplier supplier = requireSupplier(factoryId, supplierId);

        String address = trimToNull(dto.getAddress());
        if (address == null) {
            throw new BusinessException(400, "地址不能为空")
                    .withHint("请填写地址").withHintTarget("address");
        }

        SupplierAddress entity;
        boolean creating = trimToNull(dto.getId()) == null;
        if (creating) {
            if (addressRepository.countBySupplier(factoryId, supplierId) >= MAX_ITEMS_PER_SUPPLIER) {
                throw tooMany("地址");
            }
            entity = new SupplierAddress();
            entity.setFactoryId(factoryId);
            entity.setSupplierId(supplierId);
        } else {
            entity = addressRepository.findByIdAndFactoryId(dto.getId(), factoryId)
                    .orElseThrow(() -> new EntityNotFoundException("SupplierAddress", dto.getId()));
            requireSameSupplier(entity.getSupplierId(), supplierId, "地址");
            checkVersion(dto.getVersion(), entity.getVersion(), SupplierAddress.class, entity.getId());
        }

        entity.setLabel(trimToNull(dto.getLabel()));
        entity.setAddressType(dto.getAddressType() != null ? dto.getAddressType() : SupplierAddressType.BUSINESS);
        entity.setAddress(address);
        entity.setContactName(trimToNull(dto.getContactName()));
        entity.setContactPhone(trimToNull(dto.getContactPhone()));
        entity.setNotes(trimToNull(dto.getNotes()));
        entity.setSortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0);

        boolean wantPrimary = Boolean.TRUE.equals(dto.getIsPrimary())
                || addressRepository.countBySupplier(factoryId, supplierId) == 0;
        entity.setIsPrimary(wantPrimary ? Boolean.TRUE : Boolean.TRUE.equals(entity.getIsPrimary()));

        if (Boolean.TRUE.equals(entity.getIsPrimary())) {
            demoteOtherPrimaryAddresses(factoryId, supplierId, entity.getId());
        }
        addressRepository.save(entity);

        syncPrimaryAddressMirror(supplier, factoryId, supplierId);
        return loadAddresses(factoryId, supplierId);
    }

    @Override
    @Transactional
    public List<SupplierAddressDTO> deleteAddress(String factoryId, String supplierId, String addressId) {
        Supplier supplier = requireSupplier(factoryId, supplierId);
        SupplierAddress entity = addressRepository.findByIdAndFactoryId(addressId, factoryId)
                .orElseThrow(() -> new EntityNotFoundException("SupplierAddress", addressId));
        requireSameSupplier(entity.getSupplierId(), supplierId, "地址");

        if (addressRepository.countBySupplier(factoryId, supplierId) <= 1) {
            throw new BusinessException(409, "至少要保留一个地址，不能删除最后一条")
                    .withHint("请先新增另一个地址，再删除这条")
                    .withHintTarget("addresses");
        }

        boolean wasPrimary = Boolean.TRUE.equals(entity.getIsPrimary());
        addressRepository.delete(entity);

        if (wasPrimary) {
            addressRepository.findBySupplier(factoryId, supplierId).stream().findFirst()
                    .ifPresent(next -> {
                        next.setIsPrimary(true);
                        addressRepository.save(next);
                    });
        }
        syncPrimaryAddressMirror(supplier, factoryId, supplierId);
        return loadAddresses(factoryId, supplierId);
    }

    private void demoteOtherPrimaryAddresses(String factoryId, String supplierId, String keepId) {
        List<SupplierAddress> demoted = addressRepository.findBySupplier(factoryId, supplierId).stream()
                .filter(a -> Boolean.TRUE.equals(a.getIsPrimary()))
                .filter(a -> keepId == null || !keepId.equals(a.getId()))
                .peek(a -> a.setIsPrimary(false))
                .collect(Collectors.toList());
        if (!demoted.isEmpty()) {
            addressRepository.saveAll(demoted);
            addressRepository.flush();
        }
    }

    /**
     * 把主地址回写到 {@code suppliers.address}。
     * ⚠️ TraceabilityServiceImpl 会从这一列切「省/市」当溯源产地。
     */
    private void syncPrimaryAddressMirror(Supplier supplier, String factoryId, String supplierId) {
        addressRepository.findPrimary(factoryId, supplierId).ifPresent(primary -> {
            supplier.setAddress(primary.getAddress());
            supplierRepository.save(supplier);
            log.info("同步主地址到供应商主档: factoryId={}, supplierId={}", factoryId, supplierId);
        });
    }

    // ─────────────────────────────── 银行账户 ───────────────────────────────

    @Override
    @Transactional
    public List<SupplierBankAccountDTO> saveBankAccount(String factoryId, String supplierId,
                                                        SupplierBankAccountDTO dto) {
        Supplier supplier = requireSupplier(factoryId, supplierId);

        String accountNumber = trimToNull(dto.getAccountNumber());
        String bankName = trimToNull(dto.getBankName());
        if (accountNumber == null) {
            throw new BusinessException(400, "银行账号不能为空")
                    .withHint("请填写银行账号").withHintTarget("accountNumber");
        }
        if (bankName == null) {
            throw new BusinessException(400, "开户行不能为空")
                    .withHint("请填写开户行，出纳付款时要用").withHintTarget("bankName");
        }

        SupplierBankAccount entity;
        boolean creating = trimToNull(dto.getId()) == null;
        if (creating) {
            if (bankAccountRepository.countBySupplier(factoryId, supplierId) >= MAX_ITEMS_PER_SUPPLIER) {
                throw tooMany("银行账户");
            }
            entity = new SupplierBankAccount();
            entity.setFactoryId(factoryId);
            entity.setSupplierId(supplierId);
        } else {
            entity = bankAccountRepository.findByIdAndFactoryId(dto.getId(), factoryId)
                    .orElseThrow(() -> new EntityNotFoundException("SupplierBankAccount", dto.getId()));
            requireSameSupplier(entity.getSupplierId(), supplierId, "银行账户");
            checkVersion(dto.getVersion(), entity.getVersion(), SupplierBankAccount.class, entity.getId());
        }

        // 防呆: 同一供应商下账号不重复 (DB 侧还有 uq_supplier_bank_accounts_number 兜底,
        // 但那是 500; 这里给可读的 409)。
        String currentId = entity.getId();
        boolean duplicate = bankAccountRepository
                .findByAccountNumber(factoryId, supplierId, accountNumber).stream()
                .anyMatch(b -> currentId == null || !currentId.equals(b.getId()));
        if (duplicate) {
            throw new BusinessException(409, "该供应商下已存在账号 " + accountNumber + " 的银行账户")
                    .withHint("请直接编辑已有的那条，或改用其他账号")
                    .withHintTarget("accountNumber");
        }

        entity.setAccountName(trimToNull(dto.getAccountName()) != null
                ? trimToNull(dto.getAccountName()) : supplier.getName());
        entity.setBankName(bankName);
        entity.setBranchName(trimToNull(dto.getBranchName()));
        entity.setAccountNumber(accountNumber);
        entity.setCurrency(trimToNull(dto.getCurrency()) != null
                ? trimToNull(dto.getCurrency()).toUpperCase(Locale.ROOT) : "CNY");
        entity.setNotes(trimToNull(dto.getNotes()));
        entity.setSortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0);

        boolean wantPrimary = Boolean.TRUE.equals(dto.getIsPrimary())
                || bankAccountRepository.countBySupplier(factoryId, supplierId) == 0;
        entity.setIsPrimary(wantPrimary ? Boolean.TRUE : Boolean.TRUE.equals(entity.getIsPrimary()));

        if (Boolean.TRUE.equals(entity.getIsPrimary())) {
            demoteOtherPrimaryBankAccounts(factoryId, supplierId, entity.getId());
        }
        bankAccountRepository.save(entity);

        syncPrimaryBankMirror(supplier, factoryId, supplierId);
        return loadBankAccounts(factoryId, supplierId);
    }

    @Override
    @Transactional
    public List<SupplierBankAccountDTO> deleteBankAccount(String factoryId, String supplierId,
                                                          String bankAccountId) {
        Supplier supplier = requireSupplier(factoryId, supplierId);
        SupplierBankAccount entity = bankAccountRepository.findByIdAndFactoryId(bankAccountId, factoryId)
                .orElseThrow(() -> new EntityNotFoundException("SupplierBankAccount", bankAccountId));
        requireSameSupplier(entity.getSupplierId(), supplierId, "银行账户");

        boolean wasPrimary = Boolean.TRUE.equals(entity.getIsPrimary());
        bankAccountRepository.delete(entity);

        if (wasPrimary) {
            bankAccountRepository.findBySupplier(factoryId, supplierId).stream().findFirst()
                    .ifPresent(next -> {
                        next.setIsPrimary(true);
                        bankAccountRepository.save(next);
                    });
        }
        syncPrimaryBankMirror(supplier, factoryId, supplierId);
        return loadBankAccounts(factoryId, supplierId);
    }

    private void demoteOtherPrimaryBankAccounts(String factoryId, String supplierId, String keepId) {
        List<SupplierBankAccount> demoted = bankAccountRepository.findBySupplier(factoryId, supplierId).stream()
                .filter(b -> Boolean.TRUE.equals(b.getIsPrimary()))
                .filter(b -> keepId == null || !keepId.equals(b.getId()))
                .peek(b -> b.setIsPrimary(false))
                .collect(Collectors.toList());
        if (!demoted.isEmpty()) {
            bankAccountRepository.saveAll(demoted);
            bankAccountRepository.flush();
        }
    }

    /**
     * 🔴 把主账户回写到 {@code suppliers.bank_name / bank_account}。
     *
     * <p>{@code PaymentRequestServiceImpl} 出纳付款单**优先**取这两列当收款账户,
     * 付款单自身的值只做兜底。所以这个同步不是"顺手更新展示字段", 而是决定
     * 出纳把钱打到哪张卡。删掉最后一张卡时也要把镜像清空 —— 留着一个已被删除的
     * 账号在主档上, 出纳照样会照着它打款。
     */
    private void syncPrimaryBankMirror(Supplier supplier, String factoryId, String supplierId) {
        bankAccountRepository.findPrimary(factoryId, supplierId).ifPresentOrElse(primary -> {
            supplier.setBankName(primary.getBankName());
            supplier.setBankAccount(primary.getAccountNumber());
            supplierRepository.save(supplier);
            log.info("同步主银行账户到供应商主档: factoryId={}, supplierId={}, bank={}",
                    factoryId, supplierId, primary.getBankName());
        }, () -> {
            supplier.setBankName(null);
            supplier.setBankAccount(null);
            supplierRepository.save(supplier);
            log.info("供应商已无银行账户, 清空主档镜像: factoryId={}, supplierId={}", factoryId, supplierId);
        });
    }

    // ──────────────────────────────── helpers ────────────────────────────────

    private Supplier requireSupplier(String factoryId, String supplierId) {
        return supplierRepository.findByIdAndFactoryId(supplierId, factoryId)
                .orElseThrow(() -> new EntityNotFoundException("Supplier", supplierId));
    }

    /**
     * 防越权: id 已按 factoryId 查过, 但同工厂内仍可能把 A 供应商的联系人 id
     * 发到 B 供应商的 URL 上, 那样会把 A 的数据搬到 B 名下。
     */
    private void requireSameSupplier(String actualSupplierId, String expectedSupplierId, String label) {
        if (!expectedSupplierId.equals(actualSupplierId)) {
            throw new BusinessException(400, "该" + label + "不属于当前供应商")
                    .withHint("请刷新页面后重试");
        }
    }

    private void checkVersion(Long requested, Long actual, Class<?> type, String id) {
        if (requested != null && !requested.equals(actual)) {
            throw new ObjectOptimisticLockingFailureException(type, id);
        }
    }

    private BusinessException tooMany(String label) {
        return new BusinessException(409,
                "一个供应商最多维护 " + MAX_ITEMS_PER_SUPPLIER + " 条" + label)
                .withHint("请先删除不再使用的" + label);
    }

    private List<SupplierContactDTO> loadContacts(String factoryId, String supplierId) {
        return contactRepository.findBySupplier(factoryId, supplierId).stream()
                .map(SupplierProfileServiceImpl::toDTO).collect(Collectors.toList());
    }

    private List<SupplierAddressDTO> loadAddresses(String factoryId, String supplierId) {
        return addressRepository.findBySupplier(factoryId, supplierId).stream()
                .map(SupplierProfileServiceImpl::toDTO).collect(Collectors.toList());
    }

    private List<SupplierBankAccountDTO> loadBankAccounts(String factoryId, String supplierId) {
        return bankAccountRepository.findBySupplier(factoryId, supplierId).stream()
                .map(SupplierProfileServiceImpl::toDTO).collect(Collectors.toList());
    }

    static SupplierContactDTO toDTO(SupplierContact e) {
        return SupplierContactDTO.builder()
                .id(e.getId())
                .supplierId(e.getSupplierId())
                .name(e.getName())
                .contactType(e.getContactType())
                .phone(e.getPhone())
                .email(e.getEmail())
                .position(e.getPosition())
                .isPrimary(e.getIsPrimary())
                .sortOrder(e.getSortOrder())
                .notes(e.getNotes())
                .version(e.getVersion())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }

    static SupplierAddressDTO toDTO(SupplierAddress e) {
        return SupplierAddressDTO.builder()
                .id(e.getId())
                .supplierId(e.getSupplierId())
                .label(e.getLabel())
                .addressType(e.getAddressType())
                .address(e.getAddress())
                .contactName(e.getContactName())
                .contactPhone(e.getContactPhone())
                .isPrimary(e.getIsPrimary())
                .sortOrder(e.getSortOrder())
                .notes(e.getNotes())
                .version(e.getVersion())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }

    static SupplierBankAccountDTO toDTO(SupplierBankAccount e) {
        return SupplierBankAccountDTO.builder()
                .id(e.getId())
                .supplierId(e.getSupplierId())
                .accountName(e.getAccountName())
                .bankName(e.getBankName())
                .branchName(e.getBranchName())
                .accountNumber(e.getAccountNumber())
                .currency(e.getCurrency())
                .isPrimary(e.getIsPrimary())
                .sortOrder(e.getSortOrder())
                .notes(e.getNotes())
                .version(e.getVersion())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
