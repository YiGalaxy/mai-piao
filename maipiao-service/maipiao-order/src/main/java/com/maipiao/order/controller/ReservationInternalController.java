package com.maipiao.order.controller;

import com.maipiao.common.core.dto.ReservationDtos;
import com.maipiao.common.core.result.R;
import com.maipiao.order.service.OrderCreationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/inner/reservation")
@RequiredArgsConstructor
public class ReservationInternalController {
    private final OrderCreationService creation;

    @PostMapping("/prepare")
    public R<Void> prepare(@RequestBody ReservationDtos.Prepare request) {
        creation.prepare(request);
        return R.ok();
    }

    @PostMapping("/publish")
    public R<Void> publish(@RequestBody ReservationDtos.Publish request) {
        creation.publish(request);
        return R.ok();
    }

    @PostMapping("/close")
    public R<Boolean> close(
            @RequestParam String token,
            @RequestParam Long scheduleId,
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "false") boolean abandon) {
        return R.ok(creation.close(token, scheduleId, userId, abandon));
    }
}
