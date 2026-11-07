package com.maipiao.order.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.maipiao.common.core.exception.BizException;
import com.maipiao.order.entity.Order;
import com.maipiao.order.entity.OrderReservation;
import com.maipiao.order.entity.OrderStatus;
import com.maipiao.order.feign.*;
import com.maipiao.order.mapper.*;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class OrderReleaseAndPaymentTest {
    OrderMapper orders = mock(OrderMapper.class);
    OrderStateMachine states = mock(OrderStateMachine.class);
    MovieClient movie = mock(MovieClient.class);
    SeatClient seats = mock(SeatClient.class);
    UserClient users = mock(UserClient.class);
    OrderReservationMapper reservations = mock(OrderReservationMapper.class);
    OrderCancellationService cancellation = mock(OrderCancellationService.class);
    OrderService service =
            new OrderService(
                    orders,
                    mock(OrderItemMapper.class),
                    states,
                    movie,
                    seats,
                    users,
                    reservations,
                    cancellation);

    @Test
    void uncertainCancellationCannotReleaseRedis() {
        Order order = new Order();
        order.setOrderNo("token");
        order.setUserId(7L);
        order.setScheduleId(9L);
        order.setStatus(OrderStatus.CANCELLED);
        when(states.require("token")).thenReturn(order);
        when(cancellation.confirmedCancelled("token")).thenReturn(false);
        service.cancel("token", 7L, true);
        verify(seats, never()).release(anyLong(), anyString(), anyBoolean());
        when(cancellation.confirmedCancelled("token"))
                .thenThrow(new RuntimeException("global lock conflict"));
        assertThrows(RuntimeException.class, () -> service.releaseCancelledResources(order));
        verifyNoInteractions(seats);
    }

    @Test
    void partialCreationCannotEnterPaymentOrApplyCallback() {
        OrderReservation reservation = new OrderReservation();
        reservation.setState("PROCESSING");
        when(reservations.find("token")).thenReturn(reservation);
        assertThrows(BizException.class, () -> service.paymentSummary("token"));
        assertThrows(BizException.class, () -> service.markPaid("token", LocalDateTime.now()));
        verifyNoInteractions(states, movie);
    }

    @Test
    void anotherUserCannotUseProcessingGuardToBypassOwnershipCheck() {
        OrderReservation reservation = new OrderReservation();
        reservation.setState("PROCESSING");
        reservation.setUserId(7L);
        when(reservations.find("token")).thenReturn(reservation);
        assertThrows(BizException.class, () -> service.cancel("token", 8L, true));
        verifyNoInteractions(cancellation, seats);
    }
}
