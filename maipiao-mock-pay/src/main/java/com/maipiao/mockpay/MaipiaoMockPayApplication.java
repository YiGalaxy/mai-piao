package com.maipiao.mockpay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 支付服务商的替身。
 *
 * <p>真实的支付服务商只会推一次回调，之后按一个固定的、无法按需触发的节奏重试。于是
 * 那些真正会击穿支付处理的故障 —— 回调来了两次、比前一条先到、在订单已取消之后才到、
 * 或者带着一个错误的签名 —— 在真实的沙箱环境里极难复现。
 *
 * <p>本服务可以按命令把它们全都造出来，这正是重点所在：pay-service 里的幂等逻辑，
 * 只有在真的被重复和乱序的回调砸过之后，才算数。
 */
@SpringBootApplication
public class MaipiaoMockPayApplication {

    public static void main(String[] args) {
        SpringApplication.run(MaipiaoMockPayApplication.class, args);
    }
}
