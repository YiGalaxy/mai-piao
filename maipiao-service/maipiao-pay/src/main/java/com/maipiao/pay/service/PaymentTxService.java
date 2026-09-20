package com.maipiao.pay.service;

import com.maipiao.common.core.util.SnowflakeIdGenerator;
import com.maipiao.pay.channel.PaymentChannel;
import com.maipiao.pay.entity.Payment;
import com.maipiao.pay.entity.Refund;
import com.maipiao.pay.feign.OrderClient;
import com.maipiao.pay.mapper.PaymentMapper;
import com.maipiao.pay.mapper.RefundMapper;
import io.seata.spring.annotation.GlobalTransactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * The transactional half of the payment callback.
 *
 * <p>A separate bean, not a private method on {@link PaymentService}, and that
 * is the whole reason it exists. {@code @GlobalTransactional} is applied by a
 * Spring AOP proxy, and a call from one method of a bean to another on the same
 * bean never reaches the proxy - so the annotation is silently ignored. Here
 * that meant G2 ran as three unrelated local transactions: the payment could
 * commit while the order did not, and nothing would roll anything back. There
 * was no error and no log line; the only symptom was a missing
 * "Begin new global transaction" that nobody was looking for.
 *
 * <p>Calling across a bean boundary is what puts the call back through the
 * proxy. Any refactor that folds these methods back into {@code PaymentService}
 * reintroduces the bug.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentTxService {

    private final PaymentMapper paymentMapper;
    private final RefundMapper refundMapper;
    private final OrderClient orderClient;

    /**
     * Applies a successful callback.
     *
     * <p>Returns {@code false} when there was nothing to do - which is a
     * success from the provider's point of view, and must be answered as one,
     * or it will keep retrying a callback that has already been applied.
     */
    @GlobalTransactional(name = "pay-success-ticket", rollbackFor = Exception.class, timeoutMills = 30000)
    public boolean markPaid(PaymentChannel.NotifyResult notify) {

        // ---- L2: the state CAS ----
        int rows = paymentMapper.casPaySuccess(
                notify.paymentNo(), notify.channelTradeNo(), notify.amount(),
                LocalDateTime.now());

        if (rows == 0) {
            return handleCasMiss(notify);
        }

        // ---- G2: order paid and ledger sold, then tickets issued ----
        // markPaid moves the seat ledger from locked to sold as well as the
        // order status; leaving that out is what let paid seats keep counting
        // as merely held.
        orderClient.markPaid(notify.orderNo(), LocalDateTime.now());
        orderClient.issueTickets(notify.orderNo(), notify.paymentNo());

        log.info("payment succeeded: paymentNo={}, orderNo={}, amount={}",
                notify.paymentNo(), notify.orderNo(), notify.amount());
        return true;
    }

    /**
     * G3: the provider has given the money back, so the order says so.
     *
     * <p>Three writes that describe one fact, and they commit or fail
     * together:
     *
     * <ol>
     *   <li>the refund row moves to settled, guarded on the amount so a
     *       mismatched confirmation cannot close out a different refund;</li>
     *   <li>the order moves REFUNDING to REFUNDED, and its seat ledger rows go
     *       from sold back to available;</li>
     *   <li>the sold counter comes down, which is what puts the seats back on
     *       sale rather than merely marking them free.</li>
     * </ol>
     *
     * <p>Redis is not touched here. Clearing the hold is the caller's step,
     * after this commits - see {@code OrderRefundService.clearSeatHold}. A
     * bitmap cleared inside the transaction would survive a rollback and leave
     * seats on sale against an order that still reads as paid.
     *
     * <p>Idempotent: a refund already settled returns without doing anything,
     * because the retry sweep and the original request can both arrive here.
     */
    @GlobalTransactional(name = "refund-settle", rollbackFor = Exception.class, timeoutMills = 30000)
    public void settleRefund(Refund refund, String channelRefundNo) {
        int rows = refundMapper.casRefundSuccess(
                refund.getRefundNo(), channelRefundNo, refund.getRefundAmount());

        if (rows == 0) {
            log.info("refund already settled, nothing to do: refundNo={}", refund.getRefundNo());
            return;
        }

        // The order side. One call, because the order reaching REFUNDED and
        // its seats going back on sale are the same fact - splitting them is
        // how a paid order ends up with seats somebody else can buy.
        orderClient.markRefunded(refund.getOrderNo(), refund.getRefundAmount());

        log.info("refund settled: refundNo={}, orderNo={}, amount={}",
                refund.getRefundNo(), refund.getOrderNo(), refund.getRefundAmount());
    }

    /** Applies a failed callback. Never overwrites a success. */
    @Transactional(rollbackFor = Exception.class)
    public boolean markFailed(PaymentChannel.NotifyResult notify) {
        int rows = paymentMapper.casPayFailed(notify.paymentNo());
        if (rows == 0) {
            log.debug("failure callback ignored, payment not open: paymentNo={}", notify.paymentNo());
        }
        return true;
    }

    /**
     * Works out why the CAS matched nothing.
     *
     * <p>Five distinct situations hide behind "0 rows changed", and they need
     * four different responses. The one that matters most is {@code LATE_PAY}:
     * the money arrived after the order was cancelled, and it must be given
     * back rather than kept.
     */
    private boolean handleCasMiss(PaymentChannel.NotifyResult notify) {
        Payment payment = paymentMapper.selectByPaymentNo(notify.paymentNo());

        if (payment == null) {
            // A callback for a payment we have no record of. Worth shouting
            // about: it means either a bug or someone forging callbacks.
            log.error("callback for unknown payment: paymentNo={}", notify.paymentNo());
            return false;
        }

        if (payment.getStatus() == Payment.STATUS_SUCCESS) {
            boolean sameTrade = notify.channelTradeNo() != null
                    && notify.channelTradeNo().equals(payment.getChannelTradeNo());
            boolean sameAmount = notify.amount() != null
                    && notify.amount().compareTo(payment.getAmount()) == 0;

            if (sameTrade && sameAmount) {
                // A plain repeat. Nothing to do, and the provider should stop
                // retrying, so this is reported as handled.
                log.debug("duplicate callback: paymentNo={}", notify.paymentNo());
                return true;
            }
            // Already paid, but for a different trade or amount than the one
            // being reported. Something is wrong upstream.
            log.error("payment already succeeded with different details: paymentNo={}, "
                            + "recordedTrade={}, notifiedTrade={}, recordedAmount={}, notifiedAmount={}",
                    notify.paymentNo(), payment.getChannelTradeNo(), notify.channelTradeNo(),
                    payment.getAmount(), notify.amount());
            return false;
        }

        if (payment.getStatus() == Payment.STATUS_CLOSED) {
            // The dangerous case: the payment window expired, the order was
            // cancelled and the seats went back on sale, and the user paid
            // anyway. The money must be returned - keeping it would leave
            // someone charged for tickets that do not exist.
            log.warn("payment arrived after close, refunding: paymentNo={}, orderNo={}",
                    notify.paymentNo(), payment.getOrderNo());
            refundMapper.insertIfAbsent(
                    SnowflakeIdGenerator.next(),
                    SnowflakeIdGenerator.nextString(),
                    payment.getPaymentNo(),
                    payment.getOrderNo(),
                    payment.getUserId(),
                    payment.getChannel(),
                    payment.getAmount(),
                    Refund.REASON_TIME_OUT_PAID,
                    // Never release the seats: they were released when the
                    // order timed out, and doing it again would decrement a
                    // counter that no longer refers to them.
                    0);
            return true;
        }

        // Amount mismatch or an unexpected state. Not something to accept.
        log.warn("callback could not be applied: paymentNo={}, status={}",
                notify.paymentNo(), payment.getStatus());
        return false;
    }
}
