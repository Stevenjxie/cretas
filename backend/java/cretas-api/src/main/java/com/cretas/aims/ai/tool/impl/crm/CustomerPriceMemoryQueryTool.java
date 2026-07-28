package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.customer.CustomerDTO;
import com.cretas.aims.entity.CustomerPriceHistory;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.CustomerPriceMemoryService;
import com.cretas.aims.service.CustomerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Sprint 4 W2 S-PRICE-1 — 客户记忆价查询工具.
 *
 * 查询某客户对某产品的最近一次成交单价 + 来源订单 + 日期.
 *
 * 防呆 R2 ✓ result 含客户名 + 产品名 + 来源订单号 (entity context), 不只是 ID.
 * 防呆 R1 一致性 ✓ — AIChat 报价场景, 让用户**预先知**上次成交价, 避免随口报价后被打脸.
 */
@Slf4j
@Component
public class CustomerPriceMemoryQueryTool extends AbstractBusinessTool {

    @Autowired
    private CustomerPriceMemoryService priceMemoryService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private ProductTypeRepository productTypeRepository;

    @Override
    public String getToolName() {
        return "customer_price_memory_query";
    }

    @Override
    public String getDescription() {
        return "查询客户记忆价 — 客户 × 产品的最近一次成交单价 + 订单号 + 日期. " +
                "适用场景: 销售给客户报价前查上次成交价 (避免报价偏差), 财务核对历史价格, " +
                "AI 报价助手生成报价单时的价格依据.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> customerId = new HashMap<>();
        customerId.put("type", "string");
        customerId.put("description", "客户 ID (优先)");
        properties.put("customerId", customerId);

        Map<String, Object> customerName = new HashMap<>();
        customerName.put("type", "string");
        customerName.put("description", "客户名称 (customerId 未提供时按名称精确匹配)");
        properties.put("customerName", customerName);

        Map<String, Object> productTypeId = new HashMap<>();
        productTypeId.put("type", "string");
        productTypeId.put("description", "产品类型 ID (必填). 注意是 productTypeId 不是 materialId.");
        properties.put("productTypeId", productTypeId);

        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("productTypeId"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.singletonList("productTypeId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String customerId = getString(params, "customerId", null);
        String customerName = getString(params, "customerName", null);
        String productTypeId = getString(params, "productTypeId");

        log.info("S-PRICE-1 记忆价查询 - 工厂={}, customerId={}, customerName={}, productTypeId={}",
                factoryId, customerId, customerName, productTypeId);

        // 解析客户 (优先 ID, 否则按 name 精确匹配)
        CustomerDTO customer = null;
        if (customerId != null && !customerId.isBlank()) {
            customer = customerService.getCustomerById(factoryId, customerId);
        } else if (customerName != null && !customerName.isBlank()) {
            List<CustomerDTO> matches = customerService.searchCustomersByName(factoryId, customerName);
            if (matches != null && matches.size() == 1) {
                customer = matches.get(0);
            } else if (matches != null && matches.size() > 1) {
                return buildSimpleResult(
                        String.format("找到 %d 个匹配 \"%s\" 的客户, 请提供 customerId 精确指定",
                                matches.size(), customerName), null);
            }
        }
        if (customer == null) {
            return buildSimpleResult("未找到客户, 请确认 customerId 或 customerName", null);
        }

        // 解析产品 (用于 R2 context — 返回产品名而不只是 ID)
        ProductType product = productTypeRepository.findById(productTypeId).orElse(null);
        String productName = product != null ? product.getName() : productTypeId;

        Optional<CustomerPriceHistory> latest = priceMemoryService.getLatest(
                factoryId, customer.getId(), productTypeId);

        if (latest.isEmpty()) {
            return buildSimpleResult(
                    String.format("客户 %s (%s) 从未成交过产品 %s — 无历史记忆价, 请按 PriceList 标准价或产品默认价报价",
                            customer.getName(), customer.getCustomerCode(), productName),
                    Map.of(
                            "customerId", customer.getId(),
                            "customerName", customer.getName(),
                            "productTypeId", productTypeId,
                            "productName", productName,
                            "hasHistory", false
                    ));
        }

        CustomerPriceHistory row = latest.get();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("customerId", customer.getId());
        data.put("customerName", customer.getName());
        data.put("customerCode", customer.getCustomerCode());
        data.put("productTypeId", productTypeId);
        data.put("productName", productName);
        data.put("unitPrice", row.getUnitPrice());
        data.put("sourceOrderNumber", row.getSourceOrderNumber());
        data.put("sourceSalesOrderId", row.getSourceSalesOrderId());
        data.put("orderDate", row.getOrderDate().toString());
        data.put("hasHistory", true);

        return buildSimpleResult(
                String.format("客户 %s 上次购 %s 单价 ¥%s (订单 %s, %s)",
                        customer.getName(),
                        productName,
                        row.getUnitPrice().toPlainString(),
                        row.getSourceOrderNumber() != null ? row.getSourceOrderNumber() : row.getSourceSalesOrderId(),
                        row.getOrderDate()),
                data);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
