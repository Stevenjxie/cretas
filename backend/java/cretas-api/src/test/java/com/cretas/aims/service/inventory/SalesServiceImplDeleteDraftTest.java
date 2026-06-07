package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * T129 — Unit tests for {@code SalesServiceImpl.deleteDraft}.
 *
 * <p>Two mandatory cases per brief:
 * <ol>
 *   <li>DRAFT status → {@code salesOrderRepository.delete(order)} called exactly once.</li>
 *   <li>Non-DRAFT status (e.g. CONFIRMED) → {@link BusinessException} with status 409; delete NOT called.</li>
 * </ol>
 * Additionally:
 * <ol start="3">
 *   <li>Wrong factory → {@link BusinessException} 403; delete NOT called (factoryId isolation via {@code getSalesOrderById}).</li>
 *   <li>Order not found → {@link ResourceNotFoundException}; delete NOT called.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class SalesServiceImplDeleteDraftTest {

    // --- ctor-injected required dependencies ---
    @Mock SalesOrderRepository          salesOrderRepository;
    @Mock SalesOrderItemRepository      salesOrderItemRepository;
    @Mock SalesDeliveryRecordRepository deliveryRecordRepository;
    @Mock FinishedGoodsBatchRepository  finishedGoodsBatchRepository;
    @Mock CustomerRepository            customerRepository;
    @Mock ProductTypeRepository         productTypeRepository;
    @Mock ArApService                   arApService;
    @Mock ApplicationEventPublisher     eventPublisher;

    // --- optional @Autowired(required=false) field dependencies ---
    @Mock ArApTransactionRepository     arApTransactionRepository;
    @Mock WarehouseResolver             warehouseResolver;
    @Mock UserRepository                userRepository;

    SalesServiceImpl salesService;

    private static final String FACTORY_ID  = "F006";
    private static final String ORDER_ID    = "SO-F006-0001";
    private static final Long   USER_ID     = 1L;

    @BeforeEach
    void setUp() {
        salesService = new SalesServiceImpl(
                salesOrderRepository,
                salesOrderItemRepository,
                deliveryRecordRepository,
                finishedGoodsBatchRepository,
                customerRepository,
                productTypeRepository,
                arApService,
                eventPublisher);
        ReflectionTestUtils.setField(salesService, "arApTransactionRepository", arApTransactionRepository);
        ReflectionTestUtils.setField(salesService, "warehouseResolver", warehouseResolver);
        ReflectionTestUtils.setField(salesService, "userRepository", userRepository);
    }

    // ---------------------------------------------------------------------------
    // Builders
    // ---------------------------------------------------------------------------

    private SalesOrder makeOrder(String factoryId, SalesOrderStatus status) {
        SalesOrder o = new SalesOrder();
        ReflectionTestUtils.setField(o, "id", ORDER_ID);
        o.setFactoryId(factoryId);
        o.setOrderNumber("SO-TEST-001");
        o.setStatus(status);
        return o;
    }

    // ---------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("DRAFT 草稿删除 → repository.delete 被调用一次")
    void deleteDraft_draftStatus_callsRepositoryDelete() {
        SalesOrder order = makeOrder(FACTORY_ID, SalesOrderStatus.DRAFT);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        // delete() is void — no stub needed; default behavior is no-op for void methods

        salesService.deleteDraft(FACTORY_ID, ORDER_ID, USER_ID);

        verify(salesOrderRepository, times(1)).delete(order);
    }

    @Test
    @DisplayName("非 DRAFT 状态 (CONFIRMED) → 抛 BusinessException(409)，delete 不被调用")
    void deleteDraft_confirmedStatus_throws409_noDelete() {
        SalesOrder order = makeOrder(FACTORY_ID, SalesOrderStatus.CONFIRMED);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> salesService.deleteDraft(FACTORY_ID, ORDER_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    // 409 per spec — non-DRAFT state constraint
                    org.assertj.core.api.Assertions.assertThat(be.getCode()).isEqualTo(409);
                });

        verify(salesOrderRepository, never()).delete(any());
    }

    @Test
    @DisplayName("非 DRAFT 状态 (FINANCE_APPROVED) → 抛 BusinessException(409)，delete 不被调用")
    void deleteDraft_financeApprovedStatus_throws409_noDelete() {
        SalesOrder order = makeOrder(FACTORY_ID, SalesOrderStatus.FINANCE_APPROVED);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> salesService.deleteDraft(FACTORY_ID, ORDER_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    org.assertj.core.api.Assertions.assertThat(be.getCode()).isEqualTo(409);
                });

        verify(salesOrderRepository, never()).delete(any());
    }

    @Test
    @DisplayName("factoryId 隔离 — 跨工厂访问 → 抛 BusinessException(403)，delete 不被调用")
    void deleteDraft_wrongFactory_throws403_noDelete() {
        SalesOrder order = makeOrder("OTHER_FACTORY", SalesOrderStatus.DRAFT);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> salesService.deleteDraft(FACTORY_ID, ORDER_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    org.assertj.core.api.Assertions.assertThat(be.getCode()).isEqualTo(403);
                });

        verify(salesOrderRepository, never()).delete(any());
    }

    @Test
    @DisplayName("订单不存在 → 抛 ResourceNotFoundException，delete 不被调用")
    void deleteDraft_notFound_throwsResourceNotFound() {
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> salesService.deleteDraft(FACTORY_ID, ORDER_ID, USER_ID))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(salesOrderRepository, never()).delete(any());
    }
}
