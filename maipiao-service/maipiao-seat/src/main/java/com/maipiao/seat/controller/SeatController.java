package com.maipiao.seat.controller;

import com.maipiao.common.core.result.R;
import com.maipiao.common.web.context.UserContext;
import com.maipiao.seat.dto.SeatDtos;
import com.maipiao.seat.dto.SeatMapVO;
import com.maipiao.seat.service.SeatMapService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/seat")
@RequiredArgsConstructor
public class SeatController {

    private final SeatMapService seatMapService;

    /**
     * The seat map. Public - the gateway whitelists {@code /api/seat/map/**},
     * because looking at which seats are free is not something that should
     * require an account.
     */
    @GetMapping("/map/{scheduleId}")
    public R<SeatMapVO> seatMap(@PathVariable Long scheduleId) {
        return R.ok(seatMapService.getSeatMap(scheduleId));
    }

    /**
     * Claims seats for the current user.
     *
     * <p>Authenticated: the hold is attributed to a user, and without one
     * there is nobody to release it for.
     */
    @PostMapping("/lock")
    public R<SeatDtos.LockSeatResponse> lock(@Valid @RequestBody SeatDtos.LockSeatRequest request) {
        return R.ok(seatMapService.lockSeats(request, UserContext.require()));
    }

    /**
     * Gives a hold back when the user leaves without paying.
     *
     * <p>Not the main path - the lock expires on its own - but without it a
     * user who changes their mind keeps those seats out of circulation for the
     * full hold period.
     */
    @PostMapping("/release")
    public R<Integer> release(@RequestBody SeatDtos.ReleaseSeatRequest request,
                              @RequestParam String lockToken) {
        UserContext.require();
        return R.ok(seatMapService.releaseSeats(request.getScheduleId(), lockToken, false));
    }

    /**
     * Marks a hold as sold. Called by order-service after G2 commits.
     *
     * <p>Internal in intent - the gateway does not route {@code /inner/**} from
     * outside, and this path is only reachable service-to-service.
     */
    @PostMapping("/inner/confirm")
    public R<Void> confirm(@RequestParam Long scheduleId, @RequestParam String orderNo) {
        seatMapService.confirmSeats(scheduleId, orderNo);
        return R.ok();
    }
}
