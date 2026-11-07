package com.maipiao.pay.service;

import com.maipiao.common.core.util.SnowflakeIdGenerator;
import com.maipiao.pay.entity.Payment;
import com.maipiao.pay.entity.Refund;
import com.maipiao.pay.mapper.RefundMapper;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 独立提交退款意图，再由调用方访问渠道，避免自调用绕过事务代理。 */
@Service
@RequiredArgsConstructor
public class RefundIntentService {
    private final RefundMapper refundMapper;

    /**
     * 在任何别的事情发生之前，先把退款行写下去。
     *
     * <p>幂等设计里的 L4：{@code payment_no} 上的唯一键让重复请求发生冲突， 而不是造出第二笔退款。冲突本身就是答案，不是失败 —— 已经存在的那一行，
     * 正是调用方想要的。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public String createRefund(Payment payment, BigDecimal amount) {
        String refundNo = SnowflakeIdGenerator.nextString();
        int created =
                refundMapper.insertIfAbsent(
                        SnowflakeIdGenerator.next(),
                        refundNo,
                        payment.getPaymentNo(),
                        payment.getOrderNo(),
                        payment.getUserId(),
                        payment.getChannel(),
                        amount == null ? payment.getAmount() : amount,
                        Refund.REASON_USER_APPLY,
                        // 用户主动退款成功后，需要释放已售座位。
                        1);

        if (created == 0) {
            Refund existing = refundMapper.selectByPaymentNo(payment.getPaymentNo());
            if (existing == null) throw new IllegalStateException("退款意图未持久化");
            return existing.getRefundNo();
        }
        return refundNo;
    }
}
