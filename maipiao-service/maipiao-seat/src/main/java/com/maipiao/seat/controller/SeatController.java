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
     * 座位图。公开接口 —— 网关把 {@code /api/seat/map/**} 放进了白名单，因为看哪些
     * 座位空着，不该需要先有账号。
     */
    @GetMapping("/map/{sessionId}")
    public R<SeatMapVO> seatMap(@PathVariable Long sessionId) {
        return R.ok(seatMapService.getSeatMap(sessionId));
    }

    /**
     * 为当前用户占下座位。
     *
     * <p>需要鉴权：持有关系是记在某个用户名下的，没有用户，也就没有人为谁去释放它。
     */
    @PostMapping("/lock")
    public R<SeatDtos.LockSeatResponse> lock(@Valid @RequestBody SeatDtos.LockSeatRequest request) {
        return R.ok(seatMapService.lockSeats(request, UserContext.require()));
    }

    /**
     * 为不提供座位图的场次，按票档分配座位。
     *
     * <p>是 {@link #lock} 的替代方案，不是它的一个变体：买家发来一个票档和一个数量，
     * 座位选好了送回来。从这一步往后的所有东西 —— 订单事务、支付、退款 —— 都分辨不出
     * 这两者，因为它们产出的是同样的已持有座位和同样的令牌。
     */
    @PostMapping("/assign")
    public R<SeatDtos.LockSeatResponse> assign(@Valid @RequestBody SeatDtos.AssignSeatRequest request) {
        return R.ok(seatMapService.assignSeats(request, UserContext.require()));
    }

    /**
     * 用户没付款就离开时交回持有。
     *
     * <p>不是主路径 —— 锁自己会过期 —— 但没有它的话，一个改了主意的用户会让这些座位
     * 在整个持有期内都无法流通。
     */
    @PostMapping("/release")
    public R<Integer> release(@RequestBody SeatDtos.ReleaseSeatRequest request,
                              @RequestParam String lockToken) {
        UserContext.require();
        return R.ok(seatMapService.releaseSeats(request.getScheduleId(), lockToken, false));
    }
}
