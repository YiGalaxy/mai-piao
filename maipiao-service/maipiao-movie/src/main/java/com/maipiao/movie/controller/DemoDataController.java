package com.maipiao.movie.controller;

import com.maipiao.common.core.result.R;
import com.maipiao.common.web.audit.AdminAuditLog;
import com.maipiao.movie.service.DemoDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 开发环境演示数据接口：仅 dev profile 且显式开启时注册。 */
@Slf4j
@RestController
@RequestMapping("/movie/admin/demo")
@org.springframework.context.annotation.Profile("dev")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "maipiao.demo.enabled",
        havingValue = "true")
@RequiredArgsConstructor
public class DemoDataController {

    private final DemoDataService demoDataService;

    @Value("${maipiao.demo.enabled:false}")
    private boolean demoEnabled;

    /** 生成未来 {@code days} 天的排片和座位。 */
    @PostMapping("/generate-schedule")
    @AdminAuditLog(action = "GENERATE", resource = "DEMO_SCHEDULE")
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

    /** 构建用于压测的体育场、票档和两场演出。 */
    @PostMapping("/generate-showcase")
    @AdminAuditLog(action = "GENERATE", resource = "DEMO_SHOWCASE")
    public R<DemoDataService.GenerateResult> generateShowcase() {
        requireDemoEnabled();
        return R.ok(demoDataService.generateShowcase());
    }

    @PostMapping("/clear-schedule")
    @AdminAuditLog(action = "CLEAR", resource = "DEMO_SCHEDULE")
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
