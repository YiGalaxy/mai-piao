package com.maipiao.pay.channel;

import java.math.BigDecimal;
import java.util.Map;

/**
 * A payment provider.
 *
 * <p>The point of this interface is that the rest of the service does not know
 * which provider is in play. Everything that differs between providers - how a
 * payment is created, how a callback is verified, how a failure is reported -
 * is behind these six methods, so adding Alipay means adding one class and
 * changing no business logic.
 *
 * <p>The two implementations planned: {@code MockPaymentChannel} against the
 * local stand-in gateway, and {@code AlipaySandboxChannel} for the real
 * sandbox. The second is deliberately absent rather than half-written - a
 * stub that silently returns success would be worse than no stub.
 */
public interface PaymentChannel {

    /** Which provider this is. Matches the {@code channel} column. */
    ChannelType type();

    /**
     * Creates a payment on the provider's side.
     *
     * @return where to send the user, and the provider's trade number
     */
    PrepayResult prepay(PrepayCommand command);

    /**
     * Verifies and parses a callback.
     *
     * <p>Verification happens here rather than in the caller because only the
     * provider knows how its own signature is computed. A callback whose
     * signature does not check out must come back with
     * {@code signVerified = false} - never as an exception, because the
     * provider needs a response either way.
     */
    NotifyResult parseNotify(String rawBody, Map<String, String> headers);

    /** Asks the provider what actually happened, for the reassurance sweep. */
    QueryResult query(String paymentNo);

    /** Requests a refund. */
    RefundResult refund(RefundCommand command);

    /** The exact body the provider expects on success. Alipay wants "success". */
    String successResponse();

    /** The exact body that asks the provider to try again later. */
    String failResponse();

    // ------------------------------------------------------------
    // value types
    // ------------------------------------------------------------

    enum ChannelType {
        MOCK,
        ALIPAY;

        public static ChannelType of(String value) {
            if (value == null) {
                return MOCK;
            }
            try {
                return valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return MOCK;
            }
        }
    }

    record PrepayCommand(String paymentNo, String orderNo, BigDecimal amount, String subject) {
    }

    record PrepayResult(String channelTradeNo, String payUrl, String rawResponse) {
    }

    /**
     * @param signVerified  false when the signature did not check out; the
     *                      caller logs it and rejects, but the provider still
     *                      gets an answer
     * @param status        SUCCESS or FAILED as the provider reports it
     * @param channelTradeNo the provider's trade number, needed for the
     *                       uniqueness check on the payment row
     */
    record NotifyResult(
            boolean signVerified,
            String paymentNo,
            String orderNo,
            String channelTradeNo,
            BigDecimal amount,
            String status,
            String rawBody
    ) {
        public boolean isSuccess() {
            return "SUCCESS".equalsIgnoreCase(status);
        }
    }

    record QueryResult(boolean found, String status, String channelTradeNo, BigDecimal amount) {
    }

    record RefundCommand(String refundNo, String paymentNo, String channelTradeNo,
                         BigDecimal amount, String reason) {
    }

    record RefundResult(boolean accepted, String channelRefundNo, String message) {
    }
}
