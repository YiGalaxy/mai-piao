package com.maipiao.mockpay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A stand-in for a payment provider.
 *
 * <p>Real providers push a callback once and retry on a fixed schedule that
 * cannot be triggered on demand. The failures that actually break payment
 * handling - a callback that arrives twice, arrives before its predecessor,
 * arrives after the order was cancelled, or carries a bad signature - are
 * therefore very hard to reproduce against a real sandbox.
 *
 * <p>This service can produce all of them on command, which is the point: the
 * idempotency logic in pay-service is only worth anything if it has actually
 * been hit with duplicates and out-of-order arrivals.
 */
@SpringBootApplication
public class MaipiaoMockPayApplication {

    public static void main(String[] args) {
        SpringApplication.run(MaipiaoMockPayApplication.class, args);
    }
}
