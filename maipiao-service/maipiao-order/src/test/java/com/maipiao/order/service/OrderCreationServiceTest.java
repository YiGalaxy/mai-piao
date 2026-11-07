package com.maipiao.order.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maipiao.common.core.dto.ReservationDtos;
import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.R;
import com.maipiao.order.dto.OrderDtos;
import com.maipiao.order.entity.Order;
import com.maipiao.order.entity.OrderReservation;
import com.maipiao.order.entity.OrderStatus;
import com.maipiao.order.feign.SeatClient;
import com.maipiao.order.mapper.OrderMapper;
import com.maipiao.order.mapper.OrderReservationMapper;
import io.seata.core.context.RootContext;
import io.seata.core.model.GlobalStatus;
import io.seata.tm.api.GlobalTransaction;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class OrderCreationServiceTest {
    OrderReservationMapper reservations;
    OrderMapper orders;
    OrderService business;
    SeatClient seats;
    CreationTransactions transactions;
    GlobalTransaction tx;
    OrderCreationService service;
    OrderReservation row;

    @BeforeEach
    void setUp() throws Exception {
        reservations = mock(OrderReservationMapper.class);
        orders = mock(OrderMapper.class);
        business = mock(OrderService.class);
        seats = mock(SeatClient.class);
        transactions = mock(CreationTransactions.class);
        tx = mock(GlobalTransaction.class);
        service =
                new OrderCreationService(
                        reservations, orders, business, seats, transactions, new ObjectMapper());
        row = new OrderReservation();
        row.setToken("token");
        row.setUserId(7L);
        row.setScheduleId(9L);
        row.setSeatIndexes("[1,2]");
        row.setSeatLabels("[\"A1\",\"A2\"]");
        row.setExpiresAt(System.currentTimeMillis() + 60000);
        row.setState("RESERVED");
        when(reservations.find("token")).thenAnswer(i -> row);
        when(seats.verify(eq(9L), eq("token"), anyList())).thenReturn(R.ok(Map.of("held", true)));
        when(transactions.begin())
                .thenAnswer(
                        i -> {
                            RootContext.bind("xid");
                            return tx;
                        });
        when(tx.getXid()).thenReturn("xid");
        when(reservations.claim(anyString(), anyString(), anyString(), anyLong(), anyBoolean()))
                .thenAnswer(
                        i -> {
                            assertNull(RootContext.getXID(), "协调记录必须脱离 Seata 分支");
                            row.setState("PROCESSING");
                            row.setXid(i.getArgument(1));
                            row.setRequestHash(i.getArgument(2));
                            return 1;
                        });
        when(reservations.finish(anyString(), anyString(), anyString()))
                .thenAnswer(
                        i -> {
                            assertNull(RootContext.getXID());
                            row.setState(i.getArgument(2));
                            return 1;
                        });
    }

    @AfterEach
    void cleanContext() {
        RootContext.unbind();
        TransactionSynchronizationManager.clear();
    }

    OrderDtos.CreateOrderRequest request(Long coupon, boolean revise) {
        return new OrderDtos.CreateOrderRequest(
                "token",
                9L,
                List.of(2, 1),
                List.of("fake", "fake"),
                coupon,
                new BigDecimal("9999"),
                revise);
    }

    @Test
    void confirmedRollbackRetainsSeatsAndOriginalDeadline() throws Exception {
        long deadline = row.getExpiresAt();
        when(business.createReserved(any(), eq(7L), any()))
                .thenThrow(new IllegalStateException("database unavailable"));
        when(tx.getLocalStatus()).thenReturn(GlobalStatus.Rollbacked);
        var result = service.create(request(null, false), 7L);
        assertEquals("RETRYABLE", result.state());
        assertTrue(result.heldVerified());
        assertEquals(deadline, result.expiresAt());
        verify(tx).rollback();
        verifyNoMoreInteractionsAfterReleaseCheck();
    }

    void verifyNoMoreInteractionsAfterReleaseCheck() {
        verify(seats, never()).release(anyLong(), anyString(), anyBoolean());
    }

    @Test
    void lostCommitResponseBlocksRetryAndExpiryRelease() throws Exception {
        doThrow(new RuntimeException("response lost")).when(tx).commit();
        when(transactions.status("xid")).thenReturn(GlobalStatus.Finished);
        assertEquals("PROCESSING", service.create(request(null, false), 7L).state());
        row.setExpiresAt(1);
        assertFalse(service.close("token", 9L, 7L, true));
        assertEquals("PROCESSING", service.create(request(null, false), 7L).state());
        verify(transactions, times(1)).begin();
        verify(tx, never()).rollback();
        verify(reservations, never()).close(anyString(), anyLong(), anyBoolean());
        verifyNoMoreInteractionsAfterReleaseCheck();
    }

    @Test
    void normalCommitReturnWithRollbackStatusIsNotSuccess() throws Exception {
        when(tx.getLocalStatus()).thenReturn(GlobalStatus.TimeoutRollbacked);
        assertEquals("RETRYABLE", service.create(request(null, false), 7L).state());
    }

    @Test
    void businessUsesServerLabelsAndIgnoresClientDiscount() throws Exception {
        when(tx.getLocalStatus()).thenReturn(GlobalStatus.Committed);
        when(business.createReserved(any(), eq(7L), any()))
                .thenAnswer(
                        i -> {
                            assertEquals("xid", RootContext.getXID());
                            OrderDtos.CreateOrderRequest normalized = i.getArgument(0);
                            assertEquals(List.of(1, 2), normalized.seatIndexes());
                            assertEquals(List.of("A1", "A2"), normalized.seatLabels());
                            assertNull(normalized.discountAmount());
                            return null;
                        });
        assertEquals("SUCCEEDED", service.create(request(null, false), 7L).state());
    }

    @Test
    void successReplayReturnsCurrentPaidStatusWithoutNewTransaction() throws Exception {
        row.setState("SUCCEEDED");
        Order paid = new Order();
        paid.setOrderNo("token");
        paid.setStatus(OrderStatus.PAID);
        paid.setScheduleId(9L);
        paid.setUserId(7L);
        paid.setPayAmount(new BigDecimal("100.00"));
        when(orders.selectByOrderNo("token")).thenReturn(paid);
        var result = service.create(request(null, false), 7L);
        assertEquals(OrderStatus.PAID, result.orderStatus());
        assertEquals("token", result.orderNo());
        verify(transactions, never()).begin();
        assertFalse(service.close("token", 9L, 7L, true));
        verifyNoMoreInteractionsAfterReleaseCheck();
    }

    @Test
    void wrongOwnerAndChangedSeatsCannotCreate() throws Exception {
        assertThrows(BizException.class, () -> service.create(request(null, false), 8L));
        var changed =
                new OrderDtos.CreateOrderRequest("token", 9L, List.of(3), null, null, null, false);
        assertThrows(BizException.class, () -> service.create(changed, 7L));
        verify(transactions, never()).begin();
    }

    @Test
    void couponRevisionRequiresExplicitConsentAfterConfirmedRollback() throws Exception {
        row.setState("RETRYABLE");
        row.setRequestHash("previous-request-hash");
        assertThrows(BizException.class, () -> service.create(request(4L, false), 7L));
        when(tx.getLocalStatus()).thenReturn(GlobalStatus.Rollbacked);
        service.create(request(4L, true), 7L);
        verify(reservations).claim(eq("token"), eq("xid"), anyString(), anyLong(), eq(true));
    }

    @Test
    void transactionCoordinatorUnavailableDoesNotImplyFailure() throws Exception {
        row.setState("PROCESSING");
        row.setXid("xid");
        row.setExpiresAt(1);
        when(transactions.status("xid")).thenThrow(new RuntimeException("TC offline"));
        assertEquals("PROCESSING", service.status("token", 7L).state());
        assertFalse(service.close("token", 9L, 7L, false));
        verify(reservations, never()).finish(anyString(), anyString(), anyString());
    }

    @Test
    void reservationPublicationCanReplayOnlyIdenticalSeats() {
        when(reservations.publish(anyString(), anyString(), anyString(), anyLong())).thenReturn(0);
        service.publish(new ReservationDtos.Publish("token", List.of(2, 1), List.of("A2", "A1")));
        assertThrows(
                BizException.class,
                () ->
                        service.publish(
                                new ReservationDtos.Publish(
                                        "token", List.of(1, 2), List.of("changed", "A2"))));
    }

    @Test
    void localTransactionCannotAbsorbDurableCoordinationRecord() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThrows(IllegalStateException.class, () -> service.status("token", 7L));
        verify(reservations, never()).find(anyString());
    }
}
