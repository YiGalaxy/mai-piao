package com.maipiao.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * API gateway : the single entry point for every client.
 *
 * <p>Responsibilities, in order of importance:
 * <ul>
 *   <li>Drop anything a client is not allowed to assert about itself
 *       ({@code X-User-Id}, {@code X-Internal-Call}) before routing.</li>
 *   <li>Verify the JWT and forward the resolved user id downstream, so
 *       services never parse tokens themselves.</li>
 *   <li>Route by path prefix.</li>
 *   <li>Rate limit.</li>
 * </ul>
 *
 * <p>It is deliberately not a business service: no database, no domain logic.
 * Anything that needs to know about seats or orders belongs downstream.
 */
@SpringBootApplication
@EnableDiscoveryClient
public class MaipiaoGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(MaipiaoGatewayApplication.class, args);
    }
}
