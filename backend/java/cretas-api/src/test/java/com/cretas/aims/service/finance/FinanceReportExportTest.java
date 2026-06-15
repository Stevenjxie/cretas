package com.cretas.aims.service.finance;

import com.cretas.aims.dto.finance.SubjectAggregateRow;
import com.cretas.aims.dto.finance.report.CashFlowDTO;
import com.cretas.aims.entity.enums.AccountBalanceType;
import com.cretas.aims.entity.enums.AccountCategory;
import com.cretas.aims.entity.finance.Account;
import com.cretas.aims.entity.finance.AccountingPeriod;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.repository.AccountRepository;
import com.cretas.aims.repository.VoucherEntryRepository;
import com.cretas.aims.repository.VoucherRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Excel 导出测试 — 资产负债表 + 现金流量表.
 *
 * <p>覆盖:
 * <ul>
 *   <li>列标题结构正确 (column headers)</li>
 *   <li>金额 BigDecimal 按 HALF_UP scale=2 (¥70000.00 格式)</li>
 *   <li>空数据 case — xlsx 仍有 header 行, 没有异常</li>
 *   <li>文件名包含 factoryId + 日期</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class FinanceReportExportTest {

    // BalanceSheetService 依赖
    @Mock
    private VoucherEntryRepository voucherEntryRepo;

    @Mock
    private AccountRepository accountRepo;

    @Mock
    private AccountingPeriodService accountingPeriodService;

    @InjectMocks
    private BalanceSheetService balanceSheetService;

    // CashFlowService 依赖
    @Mock
    private VoucherRepository voucherRepo;

    // CashFlowService uses accountRepo and voucherEntryRepo too (shared Mocks above)
    private CashFlowService cashFlowService;

    private static final String FACTORY = "F006";

    @BeforeEach
    void setUp() {
        // Manually construct CashFlowService — @InjectMocks only supports one @InjectMocks
        cashFlowService = new CashFlowService(voucherRepo, voucherEntryRepo, accountRepo);
    }

    // =========================================================================
    // 资产负债表导出测试
    // =========================================================================

    @Test
    void exportBalanceSheet_columnHeaders_correct() throws Exception {
        stubBalanceSheetWithOneAsset();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        balanceSheetService.exportBalanceSheet(FACTORY, 2026, 5, out);

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = wb.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            assertNotNull(headerRow, "header row must exist");
            assertEquals("科目编码", headerRow.getCell(0).getStringCellValue());
            assertEquals("科目名称", headerRow.getCell(1).getStringCellValue());
            assertEquals("期末余额(元)", headerRow.getCell(2).getStringCellValue());
            assertEquals("类别", headerRow.getCell(3).getStringCellValue());
        }
    }

    @Test
    void exportBalanceSheet_amountScale2_halfUp() throws Exception {
        // Amount = 70000.005 (rounds to 70000.01 with HALF_UP, 70000.00 with HALF_EVEN)
        // Using exact value to ensure scale=2 is present
        Account cashAccount = buildAccount("1001", "库存现金", AccountCategory.ASSET);
        when(voucherEntryRepo.aggregateBySubject(eq(FACTORY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(row("1001", "库存现金", "70000.005", "0.000")));
        when(accountRepo.findVisibleToFactory(FACTORY)).thenReturn(List.of(cashAccount));
        when(accountingPeriodService.getStatus(FACTORY, 2026, 5))
                .thenReturn(AccountingPeriod.Status.OPEN);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        balanceSheetService.exportBalanceSheet(FACTORY, 2026, 5, out);

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = wb.getSheetAt(0);
            // Row 1 (index 1) = first data row = the asset line
            Row dataRow = sheet.getRow(1);
            assertNotNull(dataRow, "data row must exist");
            String amountCell = dataRow.getCell(2).getStringCellValue();
            // HALF_UP: 70000.005 → 70000.01; not 70000.00 (HALF_EVEN would give 70000.00)
            assertEquals("70000.01", amountCell, "HALF_UP rounding expected");
        }
    }

    @Test
    void exportBalanceSheet_emptyData_headersOnlyNoException() throws Exception {
        when(voucherEntryRepo.aggregateBySubject(eq(FACTORY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());
        when(accountRepo.findVisibleToFactory(FACTORY)).thenReturn(Collections.emptyList());
        when(accountingPeriodService.getStatus(FACTORY, 2026, 5))
                .thenReturn(AccountingPeriod.Status.OPEN);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Must not throw
        String fileName = balanceSheetService.exportBalanceSheet(FACTORY, 2026, 5, out);

        assertTrue(out.size() > 0, "xlsx bytes must be non-empty");

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = wb.getSheetAt(0);
            // Header must exist
            assertNotNull(sheet.getRow(0), "header row must always be present");
            assertEquals("科目编码", sheet.getRow(0).getCell(0).getStringCellValue());
        }

        // File name must contain factoryId and year/month
        assertTrue(fileName.contains("F006"), "fileName must contain factoryId");
        assertTrue(fileName.contains("202605"), "fileName must contain year+month");
        assertTrue(fileName.endsWith(".xlsx"), "fileName must end with .xlsx");
    }

    @Test
    void exportBalanceSheet_fileName_containsFactoryAndPeriod() throws Exception {
        // month=12 — need separate stub for this month value
        Account cashAccount = buildAccount("1001", "库存现金", AccountCategory.ASSET);
        when(voucherEntryRepo.aggregateBySubject(eq(FACTORY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(row("1001", "库存现金", "70000.00", "0.00")));
        when(accountRepo.findVisibleToFactory(FACTORY)).thenReturn(List.of(cashAccount));
        when(accountingPeriodService.getStatus(FACTORY, 2026, 12))
                .thenReturn(AccountingPeriod.Status.CLOSED);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String fileName = balanceSheetService.exportBalanceSheet(FACTORY, 2026, 12, out);

        assertTrue(fileName.startsWith("资产负债表_"), "fileName must start with report type");
        assertTrue(fileName.contains("F006"), "must contain factoryId");
        assertTrue(fileName.contains("202612"), "must contain year+month");
        assertTrue(fileName.endsWith(".xlsx"));
    }

    // =========================================================================
    // 现金流量表导出测试
    // =========================================================================

    @Test
    void exportCashFlow_columnHeaders_correct() throws Exception {
        stubEmptyCashFlow();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        cashFlowService.exportCashFlow(FACTORY, 2026, 1, 2026, 5, out);

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = wb.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            assertNotNull(headerRow, "header row must exist");
            assertEquals("活动类别", headerRow.getCell(0).getStringCellValue());
            assertEquals("科目编码", headerRow.getCell(1).getStringCellValue());
            assertEquals("科目名称", headerRow.getCell(2).getStringCellValue());
            assertEquals("流入(元)", headerRow.getCell(3).getStringCellValue());
            assertEquals("流出(元)", headerRow.getCell(4).getStringCellValue());
            assertEquals("净额(元)", headerRow.getCell(5).getStringCellValue());
        }
    }

    @Test
    void exportCashFlow_emptyData_headersOnlyNoException() throws Exception {
        stubEmptyCashFlow();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Must not throw
        String fileName = cashFlowService.exportCashFlow(FACTORY, 2026, 1, 2026, 5, out);

        assertTrue(out.size() > 0, "xlsx bytes must be non-empty");

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = wb.getSheetAt(0);
            assertNotNull(sheet.getRow(0), "header row must always be present");
            assertEquals("活动类别", sheet.getRow(0).getCell(0).getStringCellValue());
        }

        assertTrue(fileName.contains("F006"));
        assertTrue(fileName.endsWith(".xlsx"));
    }

    @Test
    void exportCashFlow_amountScale2_halfUp() throws Exception {
        // Voucher with cash entry: debit 1000.005 → HALF_UP → 1000.01
        // We build a Voucher with a cash entry (1001) + counterpart entry (5001)
        VoucherEntry cashEntry = new VoucherEntry();
        cashEntry.setSubjectCode("1001");
        cashEntry.setDebit(new BigDecimal("1000.005"));
        cashEntry.setCredit(BigDecimal.ZERO);

        VoucherEntry counterEntry = new VoucherEntry();
        counterEntry.setSubjectCode("5001");
        counterEntry.setSubjectName("主营业务收入");
        counterEntry.setDebit(BigDecimal.ZERO);
        counterEntry.setCredit(new BigDecimal("1000.005"));

        Voucher voucher = new Voucher();
        voucher.setEntries(List.of(cashEntry, counterEntry));

        when(voucherRepo.findByFactoryIdAndDateRange(eq(FACTORY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(voucher));
        when(accountRepo.findVisibleToFactory(FACTORY)).thenReturn(Collections.emptyList());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        cashFlowService.exportCashFlow(FACTORY, 2026, 1, 2026, 5, out);

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = wb.getSheetAt(0);
            // Find the operating activity row for 5001
            boolean found = false;
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row r = sheet.getRow(i);
                if (r == null) continue;
                String code = r.getCell(1) != null ? r.getCell(1).getStringCellValue() : "";
                if ("5001".equals(code)) {
                    // inflow cell (col 3) should be "1000.01" (HALF_UP)
                    String inflow = r.getCell(3).getStringCellValue();
                    assertEquals("1000.01", inflow, "HALF_UP rounding: 1000.005 → 1000.01");
                    found = true;
                    break;
                }
            }
            assertTrue(found, "5001 activity row must be present in xlsx");
        }
    }

    @Test
    void exportCashFlow_fileName_containsFactoryAndRange() throws Exception {
        stubEmptyCashFlow();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String fileName = cashFlowService.exportCashFlow(FACTORY, 2026, 1, 2026, 12, out);

        assertTrue(fileName.startsWith("现金流量表_"), "must start with report type");
        assertTrue(fileName.contains("F006"));
        assertTrue(fileName.contains("202601"));
        assertTrue(fileName.contains("202612"));
        assertTrue(fileName.endsWith(".xlsx"));
    }

    // =========================================================================
    // 辅助方法
    // =========================================================================

    private void stubBalanceSheetWithOneAsset() {
        Account cashAccount = buildAccount("1001", "库存现金", AccountCategory.ASSET);
        when(voucherEntryRepo.aggregateBySubject(eq(FACTORY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(row("1001", "库存现金", "70000.00", "0.00")));
        when(accountRepo.findVisibleToFactory(FACTORY)).thenReturn(List.of(cashAccount));
        when(accountingPeriodService.getStatus(FACTORY, 2026, 5))
                .thenReturn(AccountingPeriod.Status.CLOSED);
    }

    private void stubEmptyCashFlow() {
        when(voucherRepo.findByFactoryIdAndDateRange(eq(FACTORY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());
        when(accountRepo.findVisibleToFactory(FACTORY)).thenReturn(Collections.emptyList());
    }

    private Account buildAccount(String code, String name, AccountCategory category) {
        return Account.builder()
                .id("acc-" + code)
                .code(code)
                .name(name)
                .category(category)
                .balanceType(category == AccountCategory.ASSET
                        ? AccountBalanceType.DEBIT_NORMAL
                        : AccountBalanceType.CREDIT_NORMAL)
                .build();
    }

    private SubjectAggregateRow row(String code, String name, String debit, String credit) {
        return SubjectAggregateRow.builder()
                .subjectCode(code)
                .subjectName(name)
                .totalDebit(new BigDecimal(debit))
                .totalCredit(new BigDecimal(credit))
                .entryCount(1L)
                .build();
    }
}
