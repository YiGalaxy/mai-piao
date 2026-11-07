package com.maipiao.pay.service;

import com.maipiao.common.core.util.SnowflakeIdGenerator;
import com.maipiao.pay.entity.Payment;
import com.maipiao.pay.mapper.PaymentMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 渠道调用之前独立提交支付身份，超时后继续使用同一 paymentNo。 */
@Service
@RequiredArgsConstructor
public class PaymentIntentService {
    private final PaymentMapper payments;

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Payment getOrCreate(
            String orderNo, Long userId, BigDecimal amount, String channel, LocalDateTime expires) {
        Payment candidate = new Payment();
        candidate.setId(SnowflakeIdGenerator.next());
        candidate.setPaymentNo(SnowflakeIdGenerator.nextString());
        candidate.setOrderNo(orderNo);
        candidate.setUserId(userId);
        candidate.setAmount(amount);
        candidate.setChannel(channel);
        candidate.setStatus(Payment.STATUS_PENDING);
        candidate.setExpireTime(expires);
        payments.insertIntent(candidate);
        return payments.selectLatestByOrderNo(orderNo);
    }
}
