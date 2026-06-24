package com.cretas.aims.service.finance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CashFlowService.classifyActivity unit tests.
 *
 * <p>现金流量表把每张含现金科目的凭证按其对手科目归入 经营/投资/筹资 三类活动. 历史 bug:
 * 用 22-25 前缀一刀切归 FINANCING, 把流动往来 (应付账款 2202 / 应付票据 / 预收 / 应付职工薪酬 /
 * 应交税费 / 其他应付) 错归筹资; 同时漏了短期借款 (2001, 前缀 20) 应属筹资. 本测试钉死正确分类:
 * 筹资 = 借款/债券/应付利息股利/长期应付 + 权益(4xxx); 经营 = 流动经营往来/收入/成本; 投资 = 14-19.
 */
class CashFlowServiceTest {

    private final CashFlowService service = new CashFlowService(null, null, null);

    @Test
    void tradePayablesAndCurrentItems_areOperating() {
        // 流动经营往来 — 必须 OPERATING (历史 bug 错归 FINANCING)
        assertEquals("OPERATING", service.classifyActivity("2202"), "应付账款");
        assertEquals("OPERATING", service.classifyActivity("2201"), "应付票据");
        assertEquals("OPERATING", service.classifyActivity("2203"), "预收账款");
        assertEquals("OPERATING", service.classifyActivity("2211"), "应付职工薪酬");
        assertEquals("OPERATING", service.classifyActivity("2221"), "应交税费");
        assertEquals("OPERATING", service.classifyActivity("2221.01"), "应交税费-销项 子科目");
        assertEquals("OPERATING", service.classifyActivity("2241"), "其他应付款");
        assertEquals("OPERATING", service.classifyActivity("2401"), "预提费用");
    }

    @Test
    void receivablesRevenueCostCash_areOperating() {
        assertEquals("OPERATING", service.classifyActivity("1122"), "应收账款");
        assertEquals("OPERATING", service.classifyActivity("1002"), "银行存款");
        assertEquals("OPERATING", service.classifyActivity("1405"), "库存商品");
        assertEquals("OPERATING", service.classifyActivity("6001"), "主营业务收入");
        assertEquals("OPERATING", service.classifyActivity("6401"), "主营业务成本");
        assertEquals("OPERATING", service.classifyActivity("6601"), "销售费用");
    }

    @Test
    void borrowingsBondsDividendsLongTermPayable_areFinancing() {
        assertEquals("FINANCING", service.classifyActivity("2001"), "短期借款 (前缀20, 历史漏)");
        assertEquals("FINANCING", service.classifyActivity("2501"), "长期借款");
        assertEquals("FINANCING", service.classifyActivity("2502"), "应付债券");
        assertEquals("FINANCING", service.classifyActivity("2701"), "长期应付款 (前缀27, 历史漏)");
        assertEquals("FINANCING", service.classifyActivity("2231"), "应付利息");
        assertEquals("FINANCING", service.classifyActivity("2232"), "应付股利");
        assertEquals("FINANCING", service.classifyActivity("2501.01"), "长期借款 子科目");
    }

    @Test
    void equityAccounts_areFinancing() {
        assertEquals("FINANCING", service.classifyActivity("4001"), "实收资本");
        assertEquals("FINANCING", service.classifyActivity("4002"), "资本公积");
        assertEquals("FINANCING", service.classifyActivity("4104"), "利润分配");
    }

    @Test
    void fixedAndIntangibleAssets_areInvesting() {
        assertEquals("INVESTING", service.classifyActivity("1601"), "固定资产");
        assertEquals("INVESTING", service.classifyActivity("1701"), "无形资产");
        assertEquals("INVESTING", service.classifyActivity("1501"), "投资性资产");
    }

    @Test
    void nullOrShortCode_defaultsOperating() {
        assertEquals("OPERATING", service.classifyActivity(null));
        assertEquals("OPERATING", service.classifyActivity("1"));
    }
}
