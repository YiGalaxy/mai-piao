package com.maipiao.order.entity;

import lombok.Data;

@Data
public class OrderReservation {
    private String token;
    private Long scheduleId;
    private Long userId;
    private String seatIndexes;
    private String seatLabels;
    private long expiresAt;
    private String state;
    private String requestHash;
    private String xid;
}
