package com.maipiao.pay.service;

import static com.maipiao.common.core.result.RemoteResult.requireSuccess;

import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.common.core.util.SnowflakeIdGenerator;
import com.maipiao.pay.channel.PaymentChannel;
import com.maipiao.pay.entity.Payment;
import com.maipiao.pay.entity.Refund;
import com.maipiao.pay.feign.OrderClient;
import com.maipiao.pay.mapper.PaymentMapper;
import com.maipiao.pay.mapper.RefundMapper;
import io.seata.spring.annotation.GlobalTransactional;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 经独立 Bean 代理执行全局事务；HTTP 200 的业务失败同样必须回滚。 */
@Service
@RequiredArgsConstructor
public class PaymentTxService {
    private final PaymentMapper paymentMapper;
    private final RefundMapper refundMapper;
    private final OrderClient orderClient;

    @GlobalTransactional(
            name = "pay-success-ticket",
            rollbackFor = Exception.class,
            timeoutMills = 30000)
    @Transactional(rollbackFor = Exception.class)
    public boolean markPaid(PaymentChannel.NotifyResult notify) {
        Payment payment = requireBound(notify);
        if (payment.getStatus() == Payment.STATUS_SUCCESS) return true;
        var order = requireSuccess(orderClient.summary(payment.getOrderNo()), "核验订单状态");
        if (order == null
                || order.status() == null
                || !Objects.equals(order.userId(), payment.getUserId())
                || order.payAmount().compareTo(payment.getAmount()) != 0) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL, "订单快照不一致");
        }
        if (payment.getStatus() == Payment.STATUS_CLOSED || order.status() == 4) {
            // 订单的取消状态经过 FOR UPDATE / Seata 全局锁核验，不能读取一阶段状态后直接退款。
            paymentMapper.closePayment(payment.getPaymentNo());
            refundMapper.insertIfAbsent(
                    SnowflakeIdGenerator.next(),
                    SnowflakeIdGenerator.nextString(),
                    payment.getPaymentNo(),
                    payment.getOrderNo(),
                    payment.getUserId(),
                    payment.getChannel(),
                    payment.getAmount(),
                    Refund.REASON_TIME_OUT_PAID,
                    0);
            return true;
        }
        if (order.status() != 0 && order.status() != 1) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL, "订单状态不允许确认支付");
        }
        if (paymentMapper.casPaySuccess(
                        notify.paymentNo(),
                        notify.channelTradeNo(),
                        notify.amount(),
                        LocalDateTime.now())
                != 1) return false;
        requireSuccess(orderClient.markPaid(payment.getOrderNo(), LocalDateTime.now()), "订单确认支付");
        requireSuccess(
                orderClient.issueTickets(payment.getOrderNo(), payment.getPaymentNo()), "出票");
        return true;
    }

    @GlobalTransactional(
            name = "refund-settle",
            rollbackFor = Exception.class,
            timeoutMills = 30000)
    @Transactional(rollbackFor = Exception.class)
    public void settleRefund(Refund refund, String channelRefundNo) {
        int rows =
                refundMapper.casRefundSuccess(
                        refund.getRefundNo(), channelRefundNo, refund.getRefundAmount());
        if (rows == 0) {
            Refund existing = refundMapper.selectByRefundNo(refund.getRefundNo());
            if (existing == null
                    || existing.getStatus() != Refund.STATUS_SUCCESS
                    || existing.getRefundAmount().compareTo(refund.getRefundAmount()) != 0
                    || !Objects.equals(existing.getChannelRefundNo(), channelRefundNo)) {
                throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL, "退款确认状态冲突");
            }
            return;
        }
        // 迟到收款仅退钱，不能将已取消订单改为已退款或重复释放库存。
        if (Integer.valueOf(1).equals(refund.getReleaseSeat())) {
            requireSuccess(
                    orderClient.markRefunded(refund.getOrderNo(), refund.getRefundAmount()),
                    "订单确认退款");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean markFailed(PaymentChannel.NotifyResult notify) {
        requireBound(notify);
        paymentMapper.casPayFailed(notify.paymentNo());
        return true;
    }

    private Payment requireBound(PaymentChannel.NotifyResult notify) {
        Payment payment = paymentMapper.selectForUpdate(notify.paymentNo());
        if (!notify.signVerified()
                || payment == null
                || !Objects.equals(payment.getOrderNo(), notify.orderNo())
                || payment.getChannelTradeNo() == null
                || !payment.getChannelTradeNo().equals(notify.channelTradeNo())
                || notify.amount() == null
                || payment.getAmount().compareTo(notify.amount()) != 0) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL, "支付通知与本地记录不一致");
        }
        return payment;
    }
}
