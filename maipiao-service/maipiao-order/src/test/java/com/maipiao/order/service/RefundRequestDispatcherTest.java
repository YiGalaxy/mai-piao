package com.maipiao.order.service;

import static org.mockito.Mockito.*;

import com.maipiao.common.core.result.R;
import com.maipiao.order.entity.RefundRequest;
import com.maipiao.order.feign.PayClient;
import com.maipiao.order.mapper.RefundRequestMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RefundRequestDispatcherTest {
    RefundRequestMapper requests = mock(RefundRequestMapper.class);
    PayClient pay = mock(PayClient.class);
    RefundRequestDispatcher dispatcher = new RefundRequestDispatcher(requests, pay);

    RefundRequest request() {
        var r = new RefundRequest();
        r.setOrderNo("order");
        r.setAmount(new BigDecimal("100"));
        when(requests.find("order")).thenReturn(r);
        return r;
    }

    @Test
    void responseLossRetriesOriginalRequestWithoutUndoingRefundIntent() {
        var r = request();
        when(pay.applyRefund("order", r.getAmount()))
                .thenThrow(new RuntimeException("response lost"))
                .thenReturn(R.ok("refund"));
        dispatcher.dispatch("order");
        verify(requests, never()).sent(anyString(), anyString());
        dispatcher.dispatch("order");
        verify(pay, times(2)).applyRefund("order", r.getAmount());
        verify(requests).sent("order", "refund");
    }

    @Test
    void businessFailureRemainsPendingAndDeliveredIntentIsNotResent() {
        var r = request();
        when(pay.applyRefund("order", r.getAmount())).thenReturn(R.fail(503, "not accepted"));
        dispatcher.dispatch("order");
        verify(requests).failed(eq("order"), anyString());
        verify(requests, never()).sent(anyString(), anyString());
        r.setRefundNo("refund");
        dispatcher.dispatch("order");
        verify(pay, times(1)).applyRefund(anyString(), any());
    }
}
