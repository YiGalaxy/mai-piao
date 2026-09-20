package com.maipiao.pay.channel;

import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 为支付挑选渠道。
 *
 * <p>Spring 会把每一个 {@link PaymentChannel} bean 都注入进来，所以接入一家渠道方就是
 * 加一个类的事 —— 这里不用改，调用这里的代码也不用改。
 */
@Slf4j
@Component
public class PaymentChannelFactory {

    private final Map<PaymentChannel.ChannelType, PaymentChannel> channels =
            new EnumMap<>(PaymentChannel.ChannelType.class);

    public PaymentChannelFactory(List<PaymentChannel> implementations) {
        for (PaymentChannel channel : implementations) {
            channels.put(channel.type(), channel);
        }
        log.info("payment channels registered: {}", channels.keySet());
    }

    public PaymentChannel get(PaymentChannel.ChannelType type) {
        PaymentChannel channel = channels.get(type);
        if (channel == null) {
            throw new BizException(ErrorCode.PAYMENT_CHANNEL_NOT_SUPPORTED,
                    "不支持的支付渠道: " + type);
        }
        return channel;
    }

    public PaymentChannel get(String type) {
        return get(PaymentChannel.ChannelType.of(type));
    }
}
