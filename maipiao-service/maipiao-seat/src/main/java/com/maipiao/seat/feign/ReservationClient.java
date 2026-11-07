package com.maipiao.seat.feign;

import com.maipiao.common.core.dto.ReservationDtos;
import com.maipiao.common.core.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "maipiao-order", path = "/inner/reservation")
public interface ReservationClient {
    @PostMapping("/prepare")
    R<Void> prepare(@RequestBody ReservationDtos.Prepare request);

    @PostMapping("/publish")
    R<Void> publish(@RequestBody ReservationDtos.Publish request);

    @PostMapping("/close")
    R<Boolean> close(
            @RequestParam String token,
            @RequestParam Long scheduleId,
            @RequestParam(required = false) Long userId,
            @RequestParam boolean abandon);
}
