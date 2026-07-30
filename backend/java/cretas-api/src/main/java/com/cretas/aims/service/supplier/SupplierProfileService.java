package com.cretas.aims.service.supplier;

import com.cretas.aims.dto.supplier.SupplierAddressDTO;
import com.cretas.aims.dto.supplier.SupplierBankAccountDTO;
import com.cretas.aims.dto.supplier.SupplierContactDTO;

import java.util.List;

/**
 * 供应商多联系人 / 多地址 / 多银行账户。
 *
 * <p><b>核心不变式（改这个 service 前必读）</b>: 每类集合里 {@code isPrimary = true}
 * 的那一条, 必须与 {@code suppliers} 上对应的单值列保持一致 ——
 * <ul>
 *   <li>主联系人 → {@code contact_person} / {@code phone} / {@code email}</li>
 *   <li>主地址 → {@code address}</li>
 *   <li>主账户 → {@code bank_name} / {@code bank_account}</li>
 * </ul>
 *
 * <p>因为那些单值列是采购单 PDF、出纳付款单（{@code PaymentRequestServiceImpl}
 * 把供应商主数据当收款账户**权威来源**）、溯源产地、导入导出、准入摘要、AI Tool
 * 等几十个既有读点的数据源。镜像不同步 = 出纳打错款 / PDF 印错联系人。
 * 任何写入路径都必须走本 service, 不要直接 save 子表实体。
 */
public interface SupplierProfileService {

    List<SupplierContactDTO> listContacts(String factoryId, String supplierId);

    List<SupplierAddressDTO> listAddresses(String factoryId, String supplierId);

    List<SupplierBankAccountDTO> listBankAccounts(String factoryId, String supplierId);

    /** id 为空即新建, 否则按 id 更新。返回保存后的完整列表（含主标记重算结果）。 */
    List<SupplierContactDTO> saveContact(String factoryId, String supplierId, SupplierContactDTO dto);

    List<SupplierAddressDTO> saveAddress(String factoryId, String supplierId, SupplierAddressDTO dto);

    List<SupplierBankAccountDTO> saveBankAccount(String factoryId, String supplierId,
                                                 SupplierBankAccountDTO dto);

    List<SupplierContactDTO> deleteContact(String factoryId, String supplierId, String contactId);

    List<SupplierAddressDTO> deleteAddress(String factoryId, String supplierId, String addressId);

    List<SupplierBankAccountDTO> deleteBankAccount(String factoryId, String supplierId,
                                                   String bankAccountId);
}
