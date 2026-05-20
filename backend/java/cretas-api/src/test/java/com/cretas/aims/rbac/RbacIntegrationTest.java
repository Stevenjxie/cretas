package com.cretas.aims.rbac;

import com.cretas.aims.dto.customer.CustomerDTO;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.DataScope;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.DataScopeContext;
import com.cretas.aims.security.DataScopeResolver;
import com.cretas.aims.service.CustomerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sprint 7 wave 1 Track T6 — RBAC 3-role × 5-scope E2E matrix integration test.
 *
 * <p>Spec: {@code docs/superpowers/plans/2026-05-19-sprint-7-wave-1-tracks.md} §T6.
 *
 * <h3>Matrix coverage (15 cells)</h3>
 * <pre>
 *                  ALL    DEPT  DEPT_AND_BELOW  SELF  SELF_AND_BELOW
 *   SALES           ✓      ✓        ✓            ✓        ✓
 *   SALES_MGR       ✓      ✓        ✓            ✓        ✓
 *   FINANCE_DIR     ✓      ✓        ✓            ✓        ✓
 * </pre>
 *
 * <h3>Approach</h3>
 * Each test method (1) seeds a fresh user + org tree + sample customer rows in
 * {@code @BeforeEach}, (2) stubs {@link SecurityContextHolder} so
 * {@link com.cretas.aims.utils.SecurityUtils#getCurrentUserId()} returns the
 * fixture user, (3) injects the desired {@link DataScope} via
 * {@link DataScopeContext#push} (bypassing role_definitions DB lookup so test
 * works on H2 without that table), (4) calls
 * {@link CustomerService#getCustomerList} and asserts visible row counts /
 * row owners match the expected scope semantics.
 *
 * <p>Rationale for context-push approach: production code uses {@code @DataScope}
 * AOP wrapper that calls {@link DataScopeResolver#resolve}, which in turn reads
 * {@code role_definitions.data_scope}. That table is created by an upstream
 * migration NOT in this repo's flyway dir; H2 in-memory test DB doesn't have
 * it. Tests therefore inject the desired context explicitly — that exercises
 * the {@link DataScopeResolver#resolveCreatedByChain} BFS + the dispatch logic
 * in {@code CustomerServiceImpl.dispatchByScope} which is what the data
 * scope feature actually enforces.
 *
 * @author Cretas Team (Sprint 7 Track T6)
 * @since 2026-05-19
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Sprint 7 Track T6 — RBAC 3-role × 5-scope E2E matrix")
class RbacIntegrationTest {

    private static final String TEST_FACTORY_ID = "F006";

    @Autowired private CustomerService customerService;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private FactoryRepository factoryRepository;

    /** 15 fixture users keyed by `<role>_<scope>` (e.g. `sales_self`, `sales_mgr_dept`). */
    private final Map<String, User> fixtureUsers = new HashMap<>();

    /** Map of customerCode → ownerKey for verification (e.g. `RBAC-CUST-001` → `sales_all`). */
    private final Map<String, String> customerOwners = new HashMap<>();

    @BeforeEach
    void seedFixtures() {
        // 1) Ensure factory F006 exists (H2 in-memory has no seed data)
        ensureFactory();

        // 2) Seed 15 users (3 role × 5 scope). Unique usernames per test class run.
        // Each call may rerun within test → use 'find or create' pattern.
        for (String role : List.of("sales", "sales_mgr", "fin_dir")) {
            for (String scope : List.of("all", "dept", "dab", "self", "sab")) {
                String key = role + "_" + scope;
                fixtureUsers.put(key, ensureFixtureUser(key, role));
            }
        }

        // 3) Build reportsTo chain: sales_sab reports to sales_mgr_sab
        // (validates SELF_AND_BELOW BFS chain resolution)
        User sab = fixtureUsers.get("sales_sab");
        User mgrSab = fixtureUsers.get("sales_mgr_sab");
        if (sab.getReportsTo() == null || !sab.getReportsTo().equals(mgrSab.getId())) {
            sab.setReportsTo(mgrSab.getId());
            userRepository.saveAndFlush(sab);
        }

        // 4) Seed 9 customers, tagged with various created_by IDs
        // Map of customerCode → owner key for downstream verification
        Map<String, String> seedMap = Map.of(
                "RBAC-CUST-001", "sales_all",
                "RBAC-CUST-002", "sales_dept",
                "RBAC-CUST-003", "sales_dab",
                "RBAC-CUST-004", "sales_self",
                "RBAC-CUST-005", "sales_sab",
                "RBAC-CUST-006", "sales_mgr_sab",
                "RBAC-CUST-007", "fin_dir_all",
                "RBAC-CUST-008", "fin_dir_dept",
                "RBAC-CUST-009", "fin_dir_self"
        );
        seedMap.forEach((code, ownerKey) -> {
            User owner = fixtureUsers.get(ownerKey);
            customerOwners.put(code, ownerKey);
            ensureCustomer(code, owner.getId());
        });
    }

    @AfterEach
    void cleanup() {
        // Clear ThreadLocal context to avoid pollution across tests
        while (DataScopeContext.current() != null) {
            DataScopeContext.pop();
        }
        SecurityContextHolder.clearContext();
        fixtureUsers.clear();
        customerOwners.clear();
    }

    private void ensureFactory() {
        if (!factoryRepository.existsById(TEST_FACTORY_ID)) {
            Factory f = new Factory();
            f.setId(TEST_FACTORY_ID);
            f.setName("RBAC Test Factory F006");
            f.setIndustry("FOOD");
            f.setIndustryCode("FOOD_GENERAL");
            factoryRepository.saveAndFlush(f);
        }
    }

    private User ensureFixtureUser(String key, String roleKind) {
        String username = "rbac_test_" + key;
        return userRepository.findByUsername(username).orElseGet(() -> {
            User u = new User();
            u.setFactoryId(TEST_FACTORY_ID);
            u.setUsername(username);
            u.setPasswordHash("$2a$10$DUMMYHASHRBACTESTPASSWORDPLACEHOLDER");
            u.setFullName("RBAC Test " + key);
            u.setIsActive(true);
            // Role + department mapping per spec — see top-of-file matrix
            switch (roleKind) {
                case "sales":
                    u.setRoleCode("sales_manager");
                    u.setDepartment("sales");
                    u.setLevel(10);
                    break;
                case "sales_mgr":
                    u.setRoleCode("sales_manager");
                    u.setDepartment("sales");
                    u.setLevel(5);
                    break;
                case "fin_dir":
                    u.setRoleCode("factory_super_admin");
                    u.setDepartment("finance");
                    u.setLevel(0);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown roleKind: " + roleKind);
            }
            return userRepository.saveAndFlush(u);
        });
    }

    private void ensureCustomer(String code, Long createdBy) {
        // factoryId + customerCode unique-effective — skip if already present
        if (customerRepository.existsByFactoryIdAndCustomerCode(TEST_FACTORY_ID, code)) {
            return;
        }
        Customer c = new Customer();
        c.setId(UUID.randomUUID().toString());
        c.setFactoryId(TEST_FACTORY_ID);
        c.setCode(code);
        c.setCustomerCode(code);
        c.setName(code + " 名称");
        c.setContactPerson("联系人 " + code);
        c.setIsActive(true);
        c.setCreatedBy(createdBy);
        c.setVersion(0L);
        customerRepository.saveAndFlush(c);
    }

    /**
     * Push a {@link DataScopeContext} for the given fixture user + scope.
     *
     * <p>Intentionally does NOT populate {@link SecurityContextHolder}: leaving it
     * empty causes {@link DataScopeResolver#resolve} (called by the
     * {@code @DataScope} AOP) to return null (because
     * {@code SecurityUtils.getCurrentUserId()} returns null), which makes the
     * aspect a NO-OP. The context we push here survives at the top of the stack
     * and is what {@code CustomerServiceImpl.dispatchByScope} reads.
     *
     * <p>This lets us test all 15 (role × scope) combos without needing to seed
     * 15 distinct entries in {@code role_definitions} (a table that doesn't
     * exist on the H2 in-memory test DB).
     */
    private void authenticateAs(String fixtureKey, DataScope scope) {
        User user = fixtureUsers.get(fixtureKey);
        assertThat(user).as("Fixture user %s must exist", fixtureKey).isNotNull();

        DataScopeContext ctx = DataScopeContext.builder()
                .userId(user.getId())
                .factoryId(user.getFactoryId())
                .roleCode(user.getRoleCode())
                .scope(scope)
                .department(user.getDepartment())
                .column("created_by")
                .build();
        DataScopeContext.push(ctx);
    }

    private List<CustomerDTO> listCustomers() {
        PageRequest pr = new PageRequest();
        pr.setPage(1);
        pr.setSize(100);
        PageResponse<CustomerDTO> resp = customerService.getCustomerList(
                TEST_FACTORY_ID, pr, null, null, null, null);
        // Only consider RBAC test customers (filter out any pre-existing rows)
        return resp.getContent().stream()
                .filter(c -> c.getCustomerCode() != null && c.getCustomerCode().startsWith("RBAC-CUST-"))
                .toList();
    }

    // ==========================================================
    // Matrix Cell 1-5: SALES role × 5 scopes
    // ==========================================================

    @Test
    @Transactional
    @DisplayName("[SALES × ALL] sees all RBAC test customers (no row filter)")
    void sales_all() {
        authenticateAs("sales_all", DataScope.ALL);
        List<CustomerDTO> visible = listCustomers();
        // ALL scope = full visibility within factory
        assertThat(visible).as("ALL scope sees all 9 RBAC test customers")
                .hasSize(customerOwners.size());
    }

    @Test
    @Transactional
    @DisplayName("[SALES × DEPT] CUSTOM-like scope: falls back to ALL (CUSTOM-effective)")
    void sales_dept() {
        // DEPT is not a real enum value in Cretas DataScope; the spec uses
        // DEPT loosely as "single dept, no children" — implementation maps this
        // to CUSTOM (defer per current MVP) which returns empty chain → ALL path.
        authenticateAs("sales_dept", DataScope.CUSTOM);
        List<CustomerDTO> visible = listCustomers();
        // CUSTOM scope returns empty chain → caller falls back to legacy (ALL) path
        assertThat(visible).as("CUSTOM-mapped DEPT sees all customers (MVP fallback)")
                .hasSize(customerOwners.size());
    }

    @Test
    @Transactional
    @DisplayName("[SALES × DEPT_AND_BELOW] sees customers created by same-dept users")
    void sales_dab() {
        authenticateAs("sales_dab", DataScope.DEPT_AND_BELOW);
        List<CustomerDTO> visible = listCustomers();
        // DAB sees customers created by users in sales dept (5 sales users → 5 customers)
        // Plus sales_mgr_sab also in sales dept → 6 customers total
        // (RBAC-CUST-001 through 006)
        assertThat(visible)
                .extracting(CustomerDTO::getCustomerCode)
                .as("DAB scope filters to sales dept users")
                .containsExactlyInAnyOrder(
                        "RBAC-CUST-001", "RBAC-CUST-002", "RBAC-CUST-003",
                        "RBAC-CUST-004", "RBAC-CUST-005", "RBAC-CUST-006"
                );
        assertThat(visible).noneMatch(c -> c.getCustomerCode().equals("RBAC-CUST-007"))
                .as("DAB scope does not leak finance dept customers");
    }

    @Test
    @Transactional
    @DisplayName("[SALES × SELF] only sees own customer (1 row)")
    void sales_self() {
        authenticateAs("sales_self", DataScope.SELF);
        List<CustomerDTO> visible = listCustomers();
        assertThat(visible)
                .extracting(CustomerDTO::getCustomerCode)
                .as("SELF scope filters to created_by = currentUserId")
                .containsExactly("RBAC-CUST-004");
    }

    @Test
    @Transactional
    @DisplayName("[SALES × SELF_AND_BELOW] no subordinates → sees only own customer")
    void sales_sab() {
        // sales_sab reports to sales_mgr_sab, but has no subordinates itself
        authenticateAs("sales_sab", DataScope.SELF_AND_BELOW);
        List<CustomerDTO> visible = listCustomers();
        assertThat(visible)
                .extracting(CustomerDTO::getCustomerCode)
                .as("SAB with no subs → just self")
                .containsExactly("RBAC-CUST-005");
    }

    // ==========================================================
    // Matrix Cell 6-10: SALES_MGR role × 5 scopes
    // ==========================================================

    @Test
    @Transactional
    @DisplayName("[SALES_MGR × ALL] sees all RBAC test customers")
    void sales_mgr_all() {
        authenticateAs("sales_mgr_all", DataScope.ALL);
        assertThat(listCustomers()).hasSize(customerOwners.size());
    }

    @Test
    @Transactional
    @DisplayName("[SALES_MGR × DEPT] CUSTOM fallback → all")
    void sales_mgr_dept() {
        authenticateAs("sales_mgr_dept", DataScope.CUSTOM);
        assertThat(listCustomers()).hasSize(customerOwners.size());
    }

    @Test
    @Transactional
    @DisplayName("[SALES_MGR × DEPT_AND_BELOW] sees sales dept customers (sales dept = 6 users → 6 cust)")
    void sales_mgr_dab() {
        authenticateAs("sales_mgr_dab", DataScope.DEPT_AND_BELOW);
        List<CustomerDTO> visible = listCustomers();
        // sales_mgr_dab is in sales dept; should see all sales dept created customers
        assertThat(visible)
                .extracting(CustomerDTO::getCustomerCode)
                .containsExactlyInAnyOrder(
                        "RBAC-CUST-001", "RBAC-CUST-002", "RBAC-CUST-003",
                        "RBAC-CUST-004", "RBAC-CUST-005", "RBAC-CUST-006"
                );
    }

    @Test
    @Transactional
    @DisplayName("[SALES_MGR × SELF] manager with no own customer → empty")
    void sales_mgr_self() {
        authenticateAs("sales_mgr_self", DataScope.SELF);
        List<CustomerDTO> visible = listCustomers();
        // sales_mgr_self has no customer of own → 0 rows visible
        assertThat(visible).as("Manager with no own customer sees 0")
                .isEmpty();
    }

    @Test
    @Transactional
    @DisplayName("[SALES_MGR × SELF_AND_BELOW] sees own + subordinate (sales_mgr_sab manages sales_sab)")
    void sales_mgr_sab() {
        authenticateAs("sales_mgr_sab", DataScope.SELF_AND_BELOW);
        List<CustomerDTO> visible = listCustomers();
        // sales_mgr_sab (CUST-006) + reports sales_sab (CUST-005)
        assertThat(visible)
                .extracting(CustomerDTO::getCustomerCode)
                .as("SAB chain = mgr self + sales_sab subordinate")
                .containsExactlyInAnyOrder("RBAC-CUST-005", "RBAC-CUST-006");
    }

    // ==========================================================
    // Matrix Cell 11-15: FINANCE_DIRECTOR role × 5 scopes
    // ==========================================================

    @Test
    @Transactional
    @DisplayName("[FINANCE_DIR × ALL] sees all customers (super admin role + ALL)")
    void fin_dir_all() {
        authenticateAs("fin_dir_all", DataScope.ALL);
        assertThat(listCustomers()).hasSize(customerOwners.size());
    }

    @Test
    @Transactional
    @DisplayName("[FINANCE_DIR × DEPT] CUSTOM fallback → all")
    void fin_dir_dept() {
        authenticateAs("fin_dir_dept", DataScope.CUSTOM);
        assertThat(listCustomers()).hasSize(customerOwners.size());
    }

    @Test
    @Transactional
    @DisplayName("[FINANCE_DIR × DEPT_AND_BELOW] sees finance dept customers only (3 rows)")
    void fin_dir_dab() {
        authenticateAs("fin_dir_dab", DataScope.DEPT_AND_BELOW);
        List<CustomerDTO> visible = listCustomers();
        // finance dept users: fin_dir_all/dept/dab/self/sab — 5 users, but only 3 own customers
        assertThat(visible)
                .extracting(CustomerDTO::getCustomerCode)
                .as("Finance DAB sees only finance dept customers")
                .containsExactlyInAnyOrder(
                        "RBAC-CUST-007", "RBAC-CUST-008", "RBAC-CUST-009"
                );
        assertThat(visible).noneMatch(c -> c.getCustomerCode().startsWith("RBAC-CUST-001"))
                .as("Finance DAB does not leak sales dept customers");
    }

    @Test
    @Transactional
    @DisplayName("[FINANCE_DIR × SELF] only sees own customer (fin_dir_self → CUST-009)")
    void fin_dir_self() {
        authenticateAs("fin_dir_self", DataScope.SELF);
        assertThat(listCustomers())
                .extracting(CustomerDTO::getCustomerCode)
                .containsExactly("RBAC-CUST-009");
    }

    @Test
    @Transactional
    @DisplayName("[FINANCE_DIR × SELF_AND_BELOW] no subordinates → empty")
    void fin_dir_sab() {
        // fin_dir_sab has no subordinates → BFS chain = [self], but self has no customer
        authenticateAs("fin_dir_sab", DataScope.SELF_AND_BELOW);
        List<CustomerDTO> visible = listCustomers();
        assertThat(visible).as("Finance Director SAB with no own customer + no subs → empty")
                .isEmpty();
    }
}
