package com.maipiao.movie.controller;

import com.maipiao.common.core.result.R;
import com.maipiao.movie.service.DemoDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo data generation.
 *
 * <p>This exists because the schedule tables cannot be seeded from SQL - see
 * {@link DemoDataService} for the arithmetic. It is a development affordance,
 * not a product feature, and it is gated behind a flag so it cannot be reached
 * on a deployment where {@code maipiao.demo.enabled} is not explicitly set.
 *
 * <p>When admin-service lands, schedule creation moves there with proper
 * authorisation. Until then this is the only way to get bookable screenings.
 */
@Slf4j
@RestController
@RequestMapping("/movie/demo")
@RequiredArgsConstructor
public class DemoDataController {

    private final DemoDataService demoDataService;

    @Value("${maipiao.demo.enabled:true}")
    private boolean demoEnabled;

    /**
     * Generates screenings for the next {@code days} days across every active
     * hall, plus their seat rows.
     *
     * <p>Idempotent in effect but not incremental: existing schedules are
     * cleared first, so calling it twice yields one dataset rather than two
     * overlapping ones.
     */
    @PostMapping("/generate-schedule")
    public R<DemoDataService.GenerateResult> generateSchedule(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "0.25") double soldRatio,
            @RequestParam(defaultValue = "true") boolean rush) {

        requireDemoEnabled();

        if (days < 1 || days > 30) {
            throw new IllegalArgumentException("days must be between 1 and 30");
        }
        if (soldRatio < 0 || soldRatio > 0.9) {
            throw new IllegalArgumentException("soldRatio must be between 0 and 0.9");
        }

        demoDataService.clearSchedules();
        return R.ok(demoDataService.generate(days, soldRatio, rush));
    }

    /**
     * Builds the showcase: a 2000-seat stadium, four price bands, two nights -
     * one sold through a queue, one sold straight through.
     *
     * <p>Must run <b>after</b> {@link #generateSchedule}, which clears every
     * session there is. Re-running this one is safe; it only clears its own
     * project's sessions.
     */
    @PostMapping("/generate-showcase")
    public R<DemoDataService.GenerateResult> generateShowcase() {
        requireDemoEnabled();
        return R.ok(demoDataService.generateShowcase());
    }

    @PostMapping("/clear-schedule")
    public R<Void> clearSchedule() {
        requireDemoEnabled();
        demoDataService.clearSchedules();
        return R.ok();
    }

    private void requireDemoEnabled() {
        if (!demoEnabled) {
            throw new IllegalStateException("demo endpoints are disabled");
        }
    }
}
