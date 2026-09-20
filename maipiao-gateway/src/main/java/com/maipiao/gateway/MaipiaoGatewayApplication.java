package com.maipiao.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * API gateway : the single entry point for every client.
 *
 * <p>Responsibilities, in order of importance:
 * <ul>
 *   <li>Drop anything a client is not allowed to assert about itself
 *       ({@code X-User-Id}, {@code X-Internal-Call}) before routing.</li>
 *   <li>Verify the JWT and forward the resolved user id downstream, so
 *       services never parse tokens themselves.</li>
 *   <li>Short-circuit the requests a rush sale cannot serve, so they never
 *       reach a service ({@link com.maipiao.gateway.filter.RushGateFilter}).</li>
 *   <li>Route by path prefix.</li>
 * </ul>
 *
 * <p>{@code @EnableScheduling} is for keeping the rush-state snapshot warm.
 * The filter needs it to answer without a Redis lookup per request, and a
 * snapshot that is never refreshed would answer wrongly forever.
 *
 * <p>It is deliberately not a business service: no database, no domain logic.
 * Anything that needs to know about seats or orders belongs downstream.
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableScheduling
public class MaipiaoGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(MaipiaoGatewayApplication.class, args);
    }
}
