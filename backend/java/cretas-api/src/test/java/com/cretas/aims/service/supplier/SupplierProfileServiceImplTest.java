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
import com.cretas.aims.repository.SupplierAddressRepository;
import com.cretas.aims.repository.SupplierBankAccountRepository;
import com.cretas.aims.repository.SupplierContactRepository;
import com.cretas.aims.repository.SupplierRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link SupplierProfileServiceImpl} 行为测试。
 *
 * <p><b>为什么全部走 public 入口 + mock 边界 + captor 断言落库字段, 而不是反射直调
 * 私有的 {@code syncPrimaryMirror*}</b>: 反射直调只能证明"镜像函数算得对",
 * 证明不了"它真的被调用了"。把 saveContact 里的 sync 调用整行删掉, 反射式测试
 * 照样全绿 —— 而线上出纳就会照着过期的账号打款。所以这里断言的是
 * {@code supplierRepository.save(...)} 捕获到的实体字段, 调用点一没了测试就红。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SupplierProfileServiceImplTest {

    private static final String FACTORY = "F006";
    private static final String SUPPLIER_ID = "sup-1";

    @Mock SupplierRepository supplierRepository;
    @Mock SupplierContactRepository contactRepository;
    @Mock SupplierAddressRepository addressRepository;
    @Mock SupplierBankAccountRepository bankAccountRepository;

    SupplierProfileServiceImpl service;
    Supplier supplier;

    @BeforeEach
    void setUp() {
        service = new SupplierProfileServiceImpl(
                supplierRepository, contactRepository, addressRepository, bankAccountRepository);

        supplier = new Supplier();
        supplier.setId(SUPPLIER_ID);
        supplier.setFactoryId(FACTORY);
        supplier.setName("北京飞熊食品有限公司");
        when(supplierRepository.findByIdAndFactoryId(SUPPLIER_ID, FACTORY))
                .thenReturn(Optional.of(supplier));
    }

    // ───────────────────── 核心不变式: 主记录镜像回主档 ─────────────────────

    @Test
    void savingFirstContactMirrorsItOntoSupplierMasterRecord() {
        when(contactRepository.countBySupplier(FACTORY, SUPPLIER_ID)).thenReturn(0L);
        SupplierContact saved = contact("c1", "王超", "16651196431", "wang@feixiong.cn", true);
        when(contactRepository.findPrimary(FACTORY, SUPPLIER_ID)).thenReturn(Optional.of(saved));
        when(contactRepository.findBySupplier(FACTORY, SUPPLIER_ID)).thenReturn(List.of(saved));

        service.saveContact(FACTORY, SUPPLIER_ID, SupplierContactDTO.builder()
                .name("王超").phone("16651196431").email("wang@feixiong.cn")
                .contactType(SupplierContactType.SALES).build());

        // 落库字段断言 —— 采购单 PDF / 准入摘要 / 导出 / AI Tool 全读这三列
        Supplier persisted = captureSupplierSave();
        assertThat(persisted.getContactPerson()).isEqualTo("王超");
        assertThat(persisted.getPhone()).isEqualTo("16651196431");
        assertThat(persisted.getEmail()).isEqualTo("wang@feixiong.cn");
    }

    @Test
    void firstContactBecomesPrimaryEvenWhenCallerDidNotAskForIt() {
        when(contactRepository.countBySupplier(FACTORY, SUPPLIER_ID)).thenReturn(0L);
        when(contactRepository.findPrimary(FACTORY, SUPPLIER_ID)).thenReturn(Optional.empty());
        when(contactRepository.findBySupplier(FACTORY, SUPPLIER_ID)).thenReturn(List.of());

        service.saveContact(FACTORY, SUPPLIER_ID,
                SupplierContactDTO.builder().name("王超").phone("16651196431").build());

        ArgumentCaptor<SupplierContact> captor = ArgumentCaptor.forClass(SupplierContact.class);
        verify(contactRepository).save(captor.capture());
        // 不自动置主 → 镜像列为空 → SupplierProfileValidator 判 profileComplete=false
        assertThat(captor.getValue().getIsPrimary()).isTrue();
        assertThat(captor.getValue().getFactoryId()).isEqualTo(FACTORY);
        assertThat(captor.getValue().getSupplierId()).isEqualTo(SUPPLIER_ID);
    }

    @Test
    void promotingAnotherContactDemotesTheOldPrimaryBeforeSaving() {
        SupplierContact oldPrimary = contact("c1", "王超", "16651196431", null, true);
        SupplierContact target = contact("c2", "李财务", "13900009999", null, false);
        when(contactRepository.countBySupplier(FACTORY, SUPPLIER_ID)).thenReturn(2L);
        when(contactRepository.findByIdAndFactoryId("c2", FACTORY)).thenReturn(Optional.of(target));
        when(contactRepository.findBySupplier(FACTORY, SUPPLIER_ID))
                .thenReturn(List.of(oldPrimary, target));
        when(contactRepository.findPrimary(FACTORY, SUPPLIER_ID)).thenReturn(Optional.of(target));

        service.saveContact(FACTORY, SUPPLIER_ID, SupplierContactDTO.builder()
                .id("c2").name("李财务").phone("13900009999").isPrimary(true).build());

        // uq_supplier_contacts_primary 是不可延迟的部分唯一索引:
        // 不先降级旧主就 save 新主, PG 直接 23505。
        assertThat(oldPrimary.getIsPrimary()).isFalse();
        verify(contactRepository).saveAll(argThat(list -> {
            List<SupplierContact> l = toList(list);
            return l.size() == 1 && "c1".equals(l.get(0).getId());
        }));
        verify(contactRepository).flush();

        Supplier persisted = captureSupplierSave();
        assertThat(persisted.getContactPerson()).isEqualTo("李财务");
        assertThat(persisted.getPhone()).isEqualTo("13900009999");
    }

    @Test
    void savingPrimaryBankAccountMirrorsOntoSupplierBecauseCashierPaysFromThere() {
        when(bankAccountRepository.countBySupplier(FACTORY, SUPPLIER_ID)).thenReturn(0L);
        when(bankAccountRepository.findByAccountNumber(anyString(), anyString(), anyString()))
                .thenReturn(List.of());
        SupplierBankAccount saved = bank("b1", "中国工商银行北京朝阳支行", "6222021001012345678", true);
        when(bankAccountRepository.findPrimary(FACTORY, SUPPLIER_ID)).thenReturn(Optional.of(saved));
        when(bankAccountRepository.findBySupplier(FACTORY, SUPPLIER_ID)).thenReturn(List.of(saved));

        service.saveBankAccount(FACTORY, SUPPLIER_ID, SupplierBankAccountDTO.builder()
                .bankName("中国工商银行北京朝阳支行")
                .accountNumber("6222021001012345678")
                .build());

        // 🔴 PaymentRequestServiceImpl 优先取供应商主档的 bankName/bankAccount 当收款账户
        Supplier persisted = captureSupplierSave();
        assertThat(persisted.getBankName()).isEqualTo("中国工商银行北京朝阳支行");
        assertThat(persisted.getBankAccount()).isEqualTo("6222021001012345678");
    }

    @Test
    void deletingLastBankAccountClearsMirrorSoCashierStopsSeeingAStaleAccount() {
        supplier.setBankName("中国工商银行北京朝阳支行");
        supplier.setBankAccount("6222021001012345678");
        SupplierBankAccount only = bank("b1", "中国工商银行北京朝阳支行", "6222021001012345678", true);
        when(bankAccountRepository.findByIdAndFactoryId("b1", FACTORY)).thenReturn(Optional.of(only));
        when(bankAccountRepository.findBySupplier(FACTORY, SUPPLIER_ID)).thenReturn(List.of());
        when(bankAccountRepository.findPrimary(FACTORY, SUPPLIER_ID)).thenReturn(Optional.empty());

        service.deleteBankAccount(FACTORY, SUPPLIER_ID, "b1");

        Supplier persisted = captureSupplierSave();
        assertThat(persisted.getBankName()).isNull();
        assertThat(persisted.getBankAccount()).isNull();
    }

    @Test
    void savingPrimaryAddressMirrorsOntoSupplier() {
        when(addressRepository.countBySupplier(FACTORY, SUPPLIER_ID)).thenReturn(0L);
        SupplierAddress saved = address("a1", "江苏省昆山市玉山镇1号", true);
        when(addressRepository.findPrimary(FACTORY, SUPPLIER_ID)).thenReturn(Optional.of(saved));
        when(addressRepository.findBySupplier(FACTORY, SUPPLIER_ID)).thenReturn(List.of(saved));

        service.saveAddress(FACTORY, SUPPLIER_ID, SupplierAddressDTO.builder()
                .address("江苏省昆山市玉山镇1号")
                .addressType(SupplierAddressType.BUSINESS).build());

        // TraceabilityServiceImpl 从这一列切「省/市」当溯源产地
        assertThat(captureSupplierSave().getAddress()).isEqualTo("江苏省昆山市玉山镇1号");
    }

    @Test
    void deletingPrimaryContactPromotesTheNextOneAndRefreshesMirror() {
        SupplierContact primary = contact("c1", "王超", "16651196431", null, true);
        SupplierContact next = contact("c2", "李财务", "13900009999", null, false);
        when(contactRepository.countBySupplier(FACTORY, SUPPLIER_ID)).thenReturn(2L);
        when(contactRepository.findByIdAndFactoryId("c1", FACTORY)).thenReturn(Optional.of(primary));
        when(contactRepository.findBySupplier(FACTORY, SUPPLIER_ID)).thenReturn(List.of(next));
        when(contactRepository.findPrimary(FACTORY, SUPPLIER_ID)).thenReturn(Optional.of(next));

        service.deleteContact(FACTORY, SUPPLIER_ID, "c1");

        assertThat(next.getIsPrimary()).isTrue();
        Supplier persisted = captureSupplierSave();
        assertThat(persisted.getContactPerson()).isEqualTo("李财务");
        assertThat(persisted.getPhone()).isEqualTo("13900009999");
    }

    // ───────────────────────────── 防呆 / 越权 ─────────────────────────────

    @Test
    void refusesToDeleteTheOnlyContactBecauseMasterRecordRequiresOne() {
        SupplierContact only = contact("c1", "王超", "16651196431", null, true);
        when(contactRepository.countBySupplier(FACTORY, SUPPLIER_ID)).thenReturn(1L);
        when(contactRepository.findByIdAndFactoryId("c1", FACTORY)).thenReturn(Optional.of(only));

        assertThatThrownBy(() -> service.deleteContact(FACTORY, SUPPLIER_ID, "c1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("至少要保留一个联系人");
        verify(contactRepository, never()).delete(any());
        verify(supplierRepository, never()).save(any());
    }

    @Test
    void rejectsContactBelongingToAnotherSupplierInSameFactory() {
        SupplierContact foreign = contact("c9", "别家的人", "13800000000", null, false);
        foreign.setSupplierId("sup-2");
        when(contactRepository.findByIdAndFactoryId("c9", FACTORY)).thenReturn(Optional.of(foreign));
        when(contactRepository.countBySupplier(FACTORY, SUPPLIER_ID)).thenReturn(1L);

        assertThatThrownBy(() -> service.saveContact(FACTORY, SUPPLIER_ID,
                SupplierContactDTO.builder().id("c9").name("别家的人").build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于当前供应商");
        verify(contactRepository, never()).save(any());
    }

    @Test
    void rejectsDuplicateBankAccountNumberUnderSameSupplier() {
        when(bankAccountRepository.countBySupplier(FACTORY, SUPPLIER_ID)).thenReturn(1L);
        when(bankAccountRepository.findByAccountNumber(FACTORY, SUPPLIER_ID, "6222021001012345678"))
                .thenReturn(List.of(bank("b1", "工行", "6222021001012345678", true)));

        assertThatThrownBy(() -> service.saveBankAccount(FACTORY, SUPPLIER_ID,
                SupplierBankAccountDTO.builder().bankName("工行")
                        .accountNumber("6222021001012345678").build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已存在账号");
        verify(bankAccountRepository, never()).save(any());
    }

    @Test
    void bankAccountDefaultsAccountNameToSupplierNameAndCurrencyToCny() {
        when(bankAccountRepository.countBySupplier(FACTORY, SUPPLIER_ID)).thenReturn(0L);
        when(bankAccountRepository.findByAccountNumber(anyString(), anyString(), anyString()))
                .thenReturn(List.of());
        when(bankAccountRepository.findPrimary(FACTORY, SUPPLIER_ID)).thenReturn(Optional.empty());
        when(bankAccountRepository.findBySupplier(FACTORY, SUPPLIER_ID)).thenReturn(List.of());

        service.saveBankAccount(FACTORY, SUPPLIER_ID, SupplierBankAccountDTO.builder()
                .bankName("工行").accountNumber("15526886254140").build());

        ArgumentCaptor<SupplierBankAccount> captor = ArgumentCaptor.forClass(SupplierBankAccount.class);
        verify(bankAccountRepository).save(captor.capture());
        assertThat(captor.getValue().getAccountName()).isEqualTo("北京飞熊食品有限公司");
        assertThat(captor.getValue().getCurrency()).isEqualTo("CNY");
    }

    // ──────────────────────────────── helpers ────────────────────────────────

    private Supplier captureSupplierSave() {
        ArgumentCaptor<Supplier> captor = ArgumentCaptor.forClass(Supplier.class);
        verify(supplierRepository, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> toList(Iterable<T> it) {
        return (List<T>) it;
    }

    private static SupplierContact contact(String id, String name, String phone,
                                           String email, boolean primary) {
        SupplierContact c = new SupplierContact();
        c.setId(id);
        c.setFactoryId(FACTORY);
        c.setSupplierId(SUPPLIER_ID);
        c.setName(name);
        c.setPhone(phone);
        c.setEmail(email);
        c.setContactType(SupplierContactType.OTHER);
        c.setIsPrimary(primary);
        c.setSortOrder(0);
        return c;
    }

    private static SupplierAddress address(String id, String value, boolean primary) {
        SupplierAddress a = new SupplierAddress();
        a.setId(id);
        a.setFactoryId(FACTORY);
        a.setSupplierId(SUPPLIER_ID);
        a.setAddress(value);
        a.setAddressType(SupplierAddressType.BUSINESS);
        a.setIsPrimary(primary);
        a.setSortOrder(0);
        return a;
    }

    private static SupplierBankAccount bank(String id, String bankName,
                                            String accountNumber, boolean primary) {
        SupplierBankAccount b = new SupplierBankAccount();
        b.setId(id);
        b.setFactoryId(FACTORY);
        b.setSupplierId(SUPPLIER_ID);
        b.setAccountName("北京飞熊食品有限公司");
        b.setBankName(bankName);
        b.setAccountNumber(accountNumber);
        b.setCurrency("CNY");
        b.setIsPrimary(primary);
        b.setSortOrder(0);
        return b;
    }
}
