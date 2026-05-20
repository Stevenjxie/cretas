package com.cretas.aims.service.impl;

import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.customer.CreateCustomerRequest;
import com.cretas.aims.dto.customer.UpdateCustomerRequest;
import com.cretas.aims.dto.customer.CustomerDTO;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.CustomerSalesUserHistory;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.mapper.CustomerMapper;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.security.DataScopeContext;
import com.cretas.aims.security.DataScopeResolver;
import com.cretas.aims.service.CustomerService;
import com.cretas.aims.annotation.DataScope;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
/**
 * 客户服务实现
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Service
public class CustomerServiceImpl implements CustomerService {
    private static final Logger log = LoggerFactory.getLogger(CustomerServiceImpl.class);

    private final CustomerRepository customerRepository;
    private final CustomerMapper customerMapper;
    private final com.cretas.aims.utils.ExcelUtil excelUtil;

    /** Canvas V2: DB-driven validation rules */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.ValidationRuleEvaluator validationRuleEvaluator;

    /** Sprint 4 W1 S-CUSTOMER-TAB-1: tab 20 业务员变更 history (field injection — constructor manual) */
    @org.springframework.beans.factory.annotation.Autowired
    private com.cretas.aims.repository.CustomerSalesUserHistoryRepository salesUserHistoryRepository;

    /** Sprint 6 W2-B: 数据权限解析器 (optional — 模块未部署时 fallback ALL behavior). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DataScopeResolver dataScopeResolver;

    // Manual constructor (Lombok @RequiredArgsConstructor not working)
    public CustomerServiceImpl(CustomerRepository customerRepository, CustomerMapper customerMapper,
                              com.cretas.aims.utils.ExcelUtil excelUtil) {
        this.customerRepository = customerRepository;
        this.customerMapper = customerMapper;
        this.excelUtil = excelUtil;
    }

    private void runConfiguredValidation(String factoryId, String operation, java.util.Map<String, Object> context) {
        if (validationRuleEvaluator == null) return;
        try {
            validationRuleEvaluator.validate(factoryId, "customer", operation, context);
        } catch (com.cretas.aims.exception.BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Canvas validation non-blocking error: {}", e.getMessage());
        }
    }

    @Override
    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "customerList", key = "#factoryId"),
        @CacheEvict(value = "customerStats", key = "#factoryId")
    })
    public CustomerDTO createCustomer(String factoryId, CreateCustomerRequest request, Long userId) {
        runConfiguredValidation(factoryId, "CREATE", java.util.Map.of(
            "customerName", request.getName() != null ? request.getName() : ""));
        log.info("创建客户: factoryId={}, name={}", factoryId, request.getName());
        // 检查客户名称是否重复
        if (customerRepository.existsByFactoryIdAndName(factoryId, request.getName())) {
            throw new BusinessException(409, "客户名称已存在")
                    .withHint("请使用其他客户名称").withHintTarget("customerName");
        }
        // 创建客户实体
        Customer customer = customerMapper.toEntity(request, factoryId, userId);
        // 生成UUID作为ID
        //customer.setId(java.util.UUID.randomUUID().toString());
        // 确保客户代码唯一
        String baseCode = "CUS";//customer.getCustomerCode();
        int counter = 0;
        while (customerRepository.existsByCustomerCode(customer.getCustomerCode())) {
            counter++;
            customer.setCustomerCode(baseCode + "-" + counter);
        }
        // 保存客户
        customer = customerRepository.save(customer);
        log.info("客户创建成功: id={}, code={}", customer.getId(), customer.getCustomerCode());
        return customerMapper.toDTO(customer);
    }

    @Override
    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "customerList", key = "#factoryId"),
        @CacheEvict(value = "customerStats", key = "#factoryId")
    })
    public CustomerDTO updateCustomer(String factoryId, String customerId, UpdateCustomerRequest request) {
        runConfiguredValidation(factoryId, "UPDATE", java.util.Map.of(
            "customerId", customerId,
            "customerName", request.getName() != null ? request.getName() : ""));
        log.info("更新客户: factoryId={}, customerId={}", factoryId, customerId);
        Customer customer = customerRepository.findByIdAndFactoryId(customerId, factoryId)
                .orElseThrow(() -> new EntityNotFoundException("Customer", customerId));
        // Optimistic lock: compare client-supplied version against DB version.
        // Note: we can't just setVersion() on the managed entity — Hibernate locks its
        // dirty-check against the version observed at findById time, so setVersion()
        // is silently ignored for UPDATE conflict detection. Manual compare instead.
        if (request.getVersion() != null && !request.getVersion().equals(customer.getVersion())) {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(
                Customer.class, customerId);
        }
        // 检查名称是否与其他客户重复
        if (request.getName() != null && !request.getName().equals(customer.getName())) {
            if (customerRepository.existsByFactoryIdAndNameAndIdNot(factoryId, request.getName(), customerId)) {
                throw new BusinessException(409, "客户名称已存在")
                    .withHint("请使用其他客户名称").withHintTarget("customerName");
            }
        }
        // 更新客户信息
        customerMapper.updateEntity(customer, request);
        customer = customerRepository.save(customer);
        log.info("客户更新成功: id={}", customer.getId());
        return customerMapper.toDTO(customer);
    }

    @Override
    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "customerList", key = "#factoryId"),
        @CacheEvict(value = "customerStats", key = "#factoryId")
    })
    public void deleteCustomer(String factoryId, String customerId) {
        log.info("删除客户: factoryId={}, customerId={}", factoryId, customerId);
        Customer customer = customerRepository.findByIdAndFactoryId(customerId, factoryId)
                .orElseThrow(() -> new EntityNotFoundException("Customer", customerId));
        // 检查是否有关联的出货记录
        if (customerRepository.hasRelatedShipments(customerId)) {
            throw new BusinessException(409, "客户有关联的出货记录，无法删除")
                    .withHint("请先归档或转移该客户的出货记录后再删除");
        }
        customerRepository.delete(customer);
        log.info("客户删除成功: id={}", customerId);
    }

    @Override
    public CustomerDTO getCustomerById(String factoryId, String customerId) {
        Customer customer = customerRepository.findByIdAndFactoryId(customerId, factoryId)
                .orElseThrow(() -> new EntityNotFoundException("Customer", customerId));
        return customerMapper.toDTO(customer);
    }

    @Override
    public PageResponse<CustomerDTO> getCustomerList(String factoryId, PageRequest pageRequest) {
        return getCustomerList(factoryId, pageRequest, null);
    }

    @Override
    public PageResponse<CustomerDTO> getCustomerList(String factoryId, PageRequest pageRequest, String keyword) {
        return getCustomerList(factoryId, pageRequest, keyword, null, null, null);
    }

    @Override
    @DataScope("created_by")  // Sprint 6 W2-B — RBAC 第 2 维 (数据权限) sweep
    public PageResponse<CustomerDTO> getCustomerList(String factoryId, PageRequest pageRequest, String keyword,
                                                     com.cretas.aims.entity.enums.CustomerStatus customerStatus,
                                                     com.cretas.aims.entity.enums.CustomerImportance importance,
                                                     com.cretas.aims.entity.enums.CustomerSource source) {
        org.springframework.data.domain.PageRequest pageable = org.springframework.data.domain.PageRequest.of(
                pageRequest.getPage() - 1,
                pageRequest.getSize(),
                Sort.by(Sort.Direction.DESC, "createdAt")
        );
        // Sprint 4 W2 S-CRM-FULL-1: keyword 为空时传 null 给 repository (走 IS NULL 分支).
        String escapedKeyword = (keyword != null && !keyword.trim().isEmpty())
                ? com.cretas.aims.util.SqlLikeEscaper.escape(keyword.trim())
                : null;

        // Sprint 6 W2-B: 应用 DataScope filter — SELF / SELF_AND_BELOW / DEPT_AND_BELOW.
        // ALL scope (默认 / factory_super_admin) 走原 path. DB-side filter, 无 in-memory.
        DataScopeContext dsCtx = DataScopeContext.current();
        Page<Customer> customerPage = dispatchByScope(dsCtx, factoryId, escapedKeyword,
                customerStatus, importance, source, pageable);

        List<CustomerDTO> customerDTOs = customerPage.getContent().stream()
                .map(customerMapper::toDTO)
                .collect(Collectors.toList());
        PageResponse<CustomerDTO> response = new PageResponse<>();
        response.setContent(customerDTOs);
        response.setPage(pageRequest.getPage());
        response.setSize(pageRequest.getSize());
        response.setTotalElements(customerPage.getTotalElements());
        response.setTotalPages(customerPage.getTotalPages());
        response.setFirst(customerPage.isFirst());
        response.setLast(customerPage.isLast());
        return response;
    }

    /**
     * Sprint 6 W2-B — dispatch query based on {@link DataScopeContext} scope:
     * <ul>
     *   <li>SELF                → searchByFiltersPagedAndCreatedBy (single createdBy)</li>
     *   <li>SELF_AND_BELOW /
     *       DEPT_AND_BELOW      → searchByFiltersPagedAndCreatedByIn (chain)</li>
     *   <li>ALL / CUSTOM / null → searchByFiltersPaged (legacy 行为)</li>
     * </ul>
     */
    private Page<Customer> dispatchByScope(DataScopeContext dsCtx,
                                            String factoryId,
                                            String escapedKeyword,
                                            com.cretas.aims.entity.enums.CustomerStatus customerStatus,
                                            com.cretas.aims.entity.enums.CustomerImportance importance,
                                            com.cretas.aims.entity.enums.CustomerSource source,
                                            org.springframework.data.domain.PageRequest pageable) {
        if (dsCtx == null || !dsCtx.isFiltered() || dsCtx.getUserId() == null) {
            return customerRepository.searchByFiltersPaged(
                    factoryId, escapedKeyword, customerStatus, importance, source, pageable);
        }
        com.cretas.aims.entity.enums.DataScope scope = dsCtx.getScope();
        if (scope == com.cretas.aims.entity.enums.DataScope.SELF) {
            log.debug("DataScope SELF for customers: created_by={}", dsCtx.getUserId());
            return customerRepository.searchByFiltersPagedAndCreatedBy(
                    factoryId, escapedKeyword, customerStatus, importance, source,
                    dsCtx.getUserId(), pageable);
        }
        if (scope == com.cretas.aims.entity.enums.DataScope.SELF_AND_BELOW
                || scope == com.cretas.aims.entity.enums.DataScope.DEPT_AND_BELOW) {
            List<Long> chain = dataScopeResolver != null
                    ? dataScopeResolver.resolveCreatedByChain(dsCtx)
                    : List.of(dsCtx.getUserId());
            if (chain == null || chain.isEmpty()) {
                chain = List.of(dsCtx.getUserId());
            }
            log.debug("DataScope {} for customers: chain size={}", scope, chain.size());
            return customerRepository.searchByFiltersPagedAndCreatedByIn(
                    factoryId, escapedKeyword, customerStatus, importance, source,
                    chain, pageable);
        }
        // CUSTOM defer Sprint 7 (whitelist 配置) → 暂 fallback ALL
        return customerRepository.searchByFiltersPaged(
                factoryId, escapedKeyword, customerStatus, importance, source, pageable);
    }

    @Override
    @Cacheable(value = "customerList", key = "#factoryId")
    public List<CustomerDTO> getActiveCustomers(String factoryId) {
        List<Customer> customers = customerRepository.findByFactoryIdAndIsActive(factoryId, true);
        return customers.stream()
                .map(customerMapper::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<CustomerDTO> searchCustomersByName(String factoryId, String keyword) {
        String safeKeyword = com.cretas.aims.util.SqlLikeEscaper.escape(keyword);
        List<Customer> customers = customerRepository.searchByName(factoryId, safeKeyword);
        return customers.stream()
                .map(customerMapper::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<CustomerDTO> getCustomersByType(String factoryId, String type) {
        List<Customer> customers = customerRepository.findByFactoryIdAndType(factoryId, type);
        return customers.stream()
                .map(customerMapper::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<CustomerDTO> getCustomersByIndustry(String factoryId, String industry) {
        List<Customer> customers = customerRepository.findByFactoryIdAndIndustry(factoryId, industry);
        return customers.stream()
                .map(customerMapper::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "customerList", key = "#factoryId"),
        @CacheEvict(value = "customerStats", key = "#factoryId")
    })
    public CustomerDTO toggleCustomerStatus(String factoryId, String customerId, Boolean isActive) {
        log.info("切换客户状态: factoryId={}, customerId={}, isActive={}",
                factoryId, customerId, isActive);
        Customer customer = customerRepository.findByIdAndFactoryId(customerId, factoryId)
                .orElseThrow(() -> new EntityNotFoundException("Customer", customerId));
        customer.setIsActive(isActive);
        customer.setUpdatedAt(LocalDateTime.now());
        customer = customerRepository.save(customer);
        log.info("客户状态更新成功: id={}, isActive={}", customer.getId(), isActive);
        return customerMapper.toDTO(customer);
    }

    @Override
    @Transactional
    @CacheEvict(value = "customerStats", key = "#factoryId")
    public CustomerDTO updateCustomerRating(String factoryId, String customerId,
                                           Integer rating, String notes) {
        log.info("更新客户评级: factoryId={}, customerId={}, rating={}",
                factoryId, customerId, rating);
        if (rating < 1 || rating > 5) {
            throw new BusinessException(400, "评级必须在1-5之间")
                    .withHint("请输入 1 到 5 的整数").withHintTarget("rating");
        }
        Customer customer = customerRepository.findByIdAndFactoryId(customerId, factoryId)
                .orElseThrow(() -> new EntityNotFoundException("Customer", customerId));
        customer.setRating(rating);
        customer.setRatingNotes(notes);
        customer = customerRepository.save(customer);
        log.info("客户评级更新成功: id={}, rating={}", customer.getId(), rating);
        return customerMapper.toDTO(customer);
    }

    @Override
    @Transactional
    @CacheEvict(value = "customerStats", key = "#factoryId")
    public CustomerDTO updateCreditLimit(String factoryId, String customerId, BigDecimal creditLimit) {
        log.info("更新客户信用额度: factoryId={}, customerId={}, creditLimit={}",
                factoryId, customerId, creditLimit);
        if (creditLimit.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(400, "信用额度不能为负数")
                    .withHint("请输入大于等于 0 的金额").withHintTarget("creditLimit");
        }
        Customer customer = customerRepository.findByIdAndFactoryId(customerId, factoryId)
                .orElseThrow(() -> new EntityNotFoundException("Customer", customerId));
        customer.setCreditLimit(creditLimit);
        customer = customerRepository.save(customer);
        log.info("客户信用额度更新成功: id={}, creditLimit={}", customer.getId(), creditLimit);
        return customerMapper.toDTO(customer);
    }

    @Override
    @Transactional
    @CacheEvict(value = "customerStats", key = "#factoryId")
    public CustomerDTO updateCurrentBalance(String factoryId, String customerId, BigDecimal balance) {
        log.info("更新客户当前余额: factoryId={}, customerId={}, balance={}",
                factoryId, customerId, balance);
        Customer customer = customerRepository.findByIdAndFactoryId(customerId, factoryId)
                .orElseThrow(() -> new EntityNotFoundException("Customer", customerId));
        customer.setCurrentBalance(balance);
        customer = customerRepository.save(customer);
        log.info("客户余额更新成功: id={}, balance={}", customer.getId(), balance);
        return customerMapper.toDTO(customer);
    }

    @Override
    public Map<String, Object> getCustomerStatistics(String factoryId, String customerId) {
        Customer customer = customerRepository.findByIdAndFactoryId(customerId, factoryId)
                .orElseThrow(() -> new EntityNotFoundException("Customer", customerId));
        Map<String, Object> statistics = new HashMap<>();
        statistics.put("customerId", customer.getId());
        statistics.put("customerName", customer.getName());
        statistics.put("type", customer.getType());
        statistics.put("industry", customer.getIndustry());
        statistics.put("rating", customer.getRating());
        statistics.put("creditLimit", customer.getCreditLimit());
        statistics.put("currentBalance", customer.getCurrentBalance());

        // P2修复: 处理null值，避免NullPointerException
        BigDecimal creditLimit = customer.getCreditLimit() != null ? customer.getCreditLimit() : BigDecimal.ZERO;
        BigDecimal currentBalance = customer.getCurrentBalance() != null ? customer.getCurrentBalance() : BigDecimal.ZERO;
        statistics.put("creditAvailable", creditLimit.subtract(currentBalance));

        statistics.put("isActive", customer.getIsActive());
        // TODO: 添加订单统计、购买历史统计等信息
        statistics.put("totalOrders", 0);
        statistics.put("totalSales", BigDecimal.ZERO);
        statistics.put("averageOrderValue", BigDecimal.ZERO);
        statistics.put("lastOrderDate", null);
        return statistics;
    }

    @Override
    public List<Map<String, Object>> getCustomerPurchaseHistory(String factoryId, String customerId) {
        // TODO: 从订单表中获取购买历史
        List<Map<String, Object>> history = new ArrayList<>();
        return history;
    }

    @Override
    public boolean checkCustomerCodeExists(String factoryId, String customerCode) {
        return customerRepository.existsByFactoryIdAndCustomerCode(factoryId, customerCode);
    }

    @Override
    public byte[] exportCustomerList(String factoryId, boolean maskPrice) {
        log.info("导出客户列表: factoryId={}, maskPrice={}", factoryId, maskPrice);

        // 查询所有客户
        List<Customer> customers = customerRepository.findByFactoryId(factoryId);

        // 转换为DTO
        List<CustomerDTO> customerDTOs = customers.stream()
                .map(customerMapper::toDTO)
                .collect(Collectors.toList());

        // RBAC defense-in-depth (P0-C sweep, 2026-05-12): warehouse_manager 等无
        // procurement:price:view 权限的角色, 信用额度 / 当前余额 列以 "—" 占位 (mirrors
        // PR #450 PDF strip pattern, parallel to PriceFieldResponseAdvice JSON strip).
        byte[] excelBytes;
        if (maskPrice) {
            List<com.cretas.aims.dto.customer.CustomerMaskedExportDTO> maskedDTOs = customerDTOs.stream()
                    .map(com.cretas.aims.dto.customer.CustomerMaskedExportDTO::fromCustomerDTO)
                    .collect(Collectors.toList());
            excelBytes = excelUtil.exportToExcel(
                    maskedDTOs,
                    com.cretas.aims.dto.customer.CustomerMaskedExportDTO.class,
                    "客户列表"
            );
        } else {
            List<com.cretas.aims.dto.customer.CustomerExportDTO> exportDTOs = customerDTOs.stream()
                    .map(com.cretas.aims.dto.customer.CustomerExportDTO::fromCustomerDTO)
                    .collect(Collectors.toList());
            excelBytes = excelUtil.exportToExcel(
                    exportDTOs,
                    com.cretas.aims.dto.customer.CustomerExportDTO.class,
                    "客户列表"
            );
        }

        log.info("客户列表导出成功: factoryId={}, count={}, maskPrice={}",
                factoryId, customers.size(), maskPrice);
        return excelBytes;
    }

    @Override
    public byte[] generateImportTemplate() {
        log.info("生成客户导入模板");

        // 使用ExcelUtil生成空模板
        byte[] templateBytes = excelUtil.generateTemplate(
                com.cretas.aims.dto.customer.CustomerExportDTO.class,
                "客户导入模板"
        );

        log.info("客户导入模板生成成功");
        return templateBytes;
    }

    @Override
    // 不使用@Transactional，让每个save操作独立进行，避免单行失败导致整体回滚
    public com.cretas.aims.dto.common.ImportResult<CustomerDTO> importCustomersFromExcel(String factoryId, java.io.InputStream inputStream) {
        log.info("开始从Excel批量导入客户: factoryId={}", factoryId);

        // 1. 解析Excel文件
        List<com.cretas.aims.dto.customer.CustomerExportDTO> excelData;
        try {
            excelData = excelUtil.importFromExcel(inputStream, com.cretas.aims.dto.customer.CustomerExportDTO.class);
        } catch (Exception e) {
            log.error("Excel文件解析失败: factoryId={}", factoryId, e);
            throw new RuntimeException("Excel文件格式错误或无法解析: " + e.getMessage());
        }

        com.cretas.aims.dto.common.ImportResult<CustomerDTO> result =
                com.cretas.aims.dto.common.ImportResult.create(excelData.size());

        // 2. 逐行验证并导入
        for (int i = 0; i < excelData.size(); i++) {
            com.cretas.aims.dto.customer.CustomerExportDTO exportDTO = excelData.get(i);
            int rowNumber = i + 2; // Excel行号（从2开始，1是表头）

            try {
                // 2.1 验证必填字段
                if (exportDTO.getName() == null || exportDTO.getName().trim().isEmpty()) {
                    result.addFailure(rowNumber, "客户名称不能为空", toJsonString(exportDTO));
                    continue;
                }

                // 2.2 验证编码唯一性（如果提供了编码）
                if (exportDTO.getCustomerCode() != null && !exportDTO.getCustomerCode().trim().isEmpty()) {
                    if (customerRepository.existsByFactoryIdAndCustomerCode(factoryId, exportDTO.getCustomerCode())) {
                        result.addFailure(rowNumber, "客户编码已存在: " + exportDTO.getCustomerCode(), toJsonString(exportDTO));
                        continue;
                    }
                }

                // 2.3 验证名称唯一性
                if (customerRepository.existsByFactoryIdAndName(factoryId, exportDTO.getName())) {
                    result.addFailure(rowNumber, "客户名称已存在: " + exportDTO.getName(), toJsonString(exportDTO));
                    continue;
                }

                // 2.4 转换为Entity
                Customer customer = convertFromExportDTO(exportDTO, factoryId);

                // 2.5 保存
                Customer saved = customerRepository.save(customer);

                // 2.6 转换为DTO并记录成功
                CustomerDTO dto = customerMapper.toDTO(saved);
                result.addSuccess(dto);

                log.debug("成功导入客户: row={}, name={}", rowNumber, exportDTO.getName());

            } catch (Exception e) {
                log.error("导入客户失败: factoryId={}, row={}, data={}", factoryId, rowNumber, exportDTO, e);
                result.addFailure(rowNumber, "保存失败: " + e.getMessage(), toJsonString(exportDTO));
            }
        }

        log.info("客户批量导入完成: factoryId={}, total={}, success={}, failure={}",
                factoryId, result.getTotalCount(), result.getSuccessCount(), result.getFailureCount());
        return result;
    }

    /**
     * 从ExportDTO转换为Entity
     */
    private Customer convertFromExportDTO(com.cretas.aims.dto.customer.CustomerExportDTO dto, String factoryId) {
        Customer customer = new Customer();
        customer.setId(java.util.UUID.randomUUID().toString());  // 生成UUID作为ID
        customer.setFactoryId(factoryId);
        customer.setCustomerCode(dto.getCustomerCode());
        customer.setCode(dto.getCustomerCode());  // code字段也使用customerCode
        customer.setName(dto.getName());
        customer.setType(dto.getType());  // Customer实体字段名是type
        customer.setIndustry(dto.getIndustry());
        customer.setContactPerson(dto.getContactPerson());
        customer.setPhone(dto.getPhone());  // Customer实体字段名是phone
        customer.setEmail(dto.getEmail());
        customer.setShippingAddress(dto.getShippingAddress());
        customer.setPaymentTerms(dto.getPaymentTerms());
        customer.setCreditLimit(dto.getCreditLimit());
        customer.setCurrentBalance(dto.getCurrentBalance());
        customer.setRating(dto.getRating());
        customer.setIsActive("启用".equals(dto.getStatus()));
        return customer;
    }

    /**
     * JSON序列化辅助方法（简单实现）
     */
    private String toJsonString(Object obj) {
        // 简单的字符串表示，生产环境可使用Jackson
        return obj != null ? obj.toString() : "null";
    }

    @Override
    @Transactional
    public List<CustomerDTO> importCustomers(String factoryId, List<CreateCustomerRequest> requests,
                                            Long userId) {
        log.info("批量导入客户: factoryId={}, count={}", factoryId, requests.size());
        List<CustomerDTO> importedCustomers = new ArrayList<>();
        for (CreateCustomerRequest request : requests) {
            try {
                CustomerDTO customer = createCustomer(factoryId, request, userId);
                importedCustomers.add(customer);
            } catch (Exception e) {
                log.error("导入客户失败: name={}, error={}", request.getName(), e.getMessage());
            }
        }
        log.info("批量导入完成，成功导入 {} 个客户", importedCustomers.size());
        return importedCustomers;
    }

    @Override
    public Map<Integer, Long> getCustomerRatingDistribution(String factoryId) {
        List<Object[]> distribution = customerRepository.getCustomerRatingDistribution(factoryId);
        Map<Integer, Long> result = new HashMap<>();
        for (Object[] row : distribution) {
            Integer rating = (Integer) row[0];
            Long count = (Long) row[1];
            // 修复: 过滤null rating，避免JSON序列化失败
            if (rating != null) {
                result.put(rating, count);
            } else {
                // 将null rating归类为"未评级"（rating=0）
                log.warn("发现未评级的客户，数量: {}", count);
                result.put(0, result.getOrDefault(0, 0L) + count);
            }
        }
        // 确保所有评级都有值（0-5分，0表示未评级）
        for (int i = 0; i <= 5; i++) {
            result.putIfAbsent(i, 0L);
        }
        return result;
    }

    @Override
    public List<CustomerDTO> getCustomersWithOutstandingBalance(String factoryId) {
        List<Customer> customers = customerRepository.findCustomersWithOutstandingBalance(factoryId);
        return customers.stream()
                .map(customerMapper::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<CustomerDTO> getVIPCustomers(String factoryId, Integer limit) {
        org.springframework.data.domain.PageRequest pageable = org.springframework.data.domain.PageRequest.of(0, limit);
        List<Customer> customers = customerRepository.findTopCustomersByCreditLimit(factoryId, pageable);
        return customers.stream()
                .map(customerMapper::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public Map<String, Long> getCustomerTypeDistribution(String factoryId) {
        List<Object[]> distribution = customerRepository.countByType(factoryId);
        Map<String, Long> result = new HashMap<>();
        for (Object[] row : distribution) {
            String type = (String) row[0];
            Long count = (Long) row[1];
            if (type != null) {
                result.put(type, count);
            } else {
                result.put("未分类", count);
            }
        }
        return result;
    }

    @Override
    public Map<String, Long> getCustomerIndustryDistribution(String factoryId) {
        List<Object[]> distribution = customerRepository.countByIndustry(factoryId);
        Map<String, Long> result = new HashMap<>();
        for (Object[] row : distribution) {
            String industry = (String) row[0];
            Long count = (Long) row[1];
            if (industry != null) {
                result.put(industry, count);
            } else {
                result.put("未分类", count);
            }
        }
        return result;
    }

    @Override
    @Cacheable(value = "customerStats", key = "#factoryId")
    public Map<String, Object> getOverallCustomerStatistics(String factoryId) {
        Map<String, Object> statistics = new HashMap<>();
        // 客户总数
        long totalCustomers = customerRepository.countByFactoryId(factoryId);
        statistics.put("totalCustomers", totalCustomers);
        // 活跃客户数
        long activeCustomers = customerRepository.countByFactoryIdAndIsActive(factoryId, true);
        statistics.put("activeCustomers", activeCustomers);
        // 平均评级
        Double averageRating = customerRepository.calculateAverageRating(factoryId);
        statistics.put("averageRating", averageRating != null ? averageRating : 0.0);
        // 总欠款
        BigDecimal totalOutstanding = customerRepository.calculateTotalOutstandingBalance(factoryId);
        statistics.put("totalOutstandingBalance", totalOutstanding != null ? totalOutstanding : BigDecimal.ZERO);
        // 总信用额度
        BigDecimal totalCredit = customerRepository.calculateTotalCreditLimit(factoryId);
        statistics.put("totalCreditLimit", totalCredit != null ? totalCredit : BigDecimal.ZERO);
        // 客户类型分布
        statistics.put("typeDistribution", getCustomerTypeDistribution(factoryId));
        // 客户行业分布
        statistics.put("industryDistribution", getCustomerIndustryDistribution(factoryId));
        // 客户评级分布
        statistics.put("ratingDistribution", getCustomerRatingDistribution(factoryId));
        return statistics;
    }

    /**
     * Sprint 4 W1 S-CUSTOMER-TAB-1 (tab 20): 变更客户当前业务员 + 记录 history.
     * 防呆 R4 idempotent: 5min 内同 (factoryId, customerId, newSalesUserId) 二次调用
     * 抛 BusinessException(409).withHint(跳转 history 详情).
     */
    @Override
    @Transactional
    @CacheEvict(value = {"customer", "customerStats"}, allEntries = true)
    public CustomerDTO updateAssignedSalesUser(String factoryId, String customerId,
                                               Long newSalesUserId, String reason, Long changedBy) {
        Customer customer = customerRepository.findById(customerId)
            .orElseThrow(() -> new EntityNotFoundException("客户不存在: " + customerId));

        if (!factoryId.equals(customer.getFactoryId())) {
            throw new BusinessException(403, "无权操作此客户 (跨工厂访问被拒)");
        }

        Long previous = customer.getAssignedSalesUserId();
        LocalDateTime now = LocalDateTime.now();

        // R4 dedup: 5min 窗口检查
        var recent = salesUserHistoryRepository.findRecentChange(
            factoryId, customerId, newSalesUserId, now.minusMinutes(5));
        if (!recent.isEmpty()) {
            String existingId = recent.get(0).getId();
            throw new BusinessException(409,
                "5 分钟内已变更过此客户的业务员 (history " + existingId + "). 请查看已有变更记录, 避免重复提交.")
                .withHint("/sales/customers/" + customerId + "?tab=salesUserHist&highlight=" + existingId);
        }

        // Update customer + insert history
        customer.setAssignedSalesUserId(newSalesUserId);
        customer.setAssignedSalesUserAssignedAt(now);
        customerRepository.save(customer);

        CustomerSalesUserHistory history = new CustomerSalesUserHistory();
        history.setFactoryId(factoryId);
        history.setCustomerId(customerId);
        history.setPreviousSalesUserId(previous);
        history.setNewSalesUserId(newSalesUserId);
        history.setChangedBy(changedBy);
        history.setChangedAt(now);
        history.setReason(reason);
        salesUserHistoryRepository.save(history);

        log.info("Customer {} 业务员变更: {} → {} (by user {})", customerId, previous, newSalesUserId, changedBy);
        return customerMapper.toDTO(customer);
    }
}
