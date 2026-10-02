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
 * 演示数据生成。
 *
 * <p>它存在是因为排期这几张表没法用 SQL 初始化 —— 那套算术见
 * {@link DemoDataService}。这是开发期的便利，不是产品功能，所以被一个开关挡住：
 * 没有显式设置 {@code maipiao.demo.enabled} 的部署环境够不到它。
 *
 * <p>等 admin-service 落地，排期创建会带着正经的鉴权搬过去。在那之前，这是拿到
 * 可预订场次的唯一办法。
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
     * 为接下来 {@code days} 天、每个在用场馆生成排片，以及它们的座位行。
     *
     * <p>效果上幂等，但不是增量的：已有的排期会先被清掉，所以调两次得到的是一份数据集，
     * 而不是两份互相重叠的。
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
     * 构建 showcase：一个 2000 座的体育场、四个票价档、两晚 —— 一晚走排队卖，
     * 一晚直接卖。
     *
     * <p>必须在 {@link #generateSchedule} <b>之后</b>运行，那个会清掉所有场次。
     * 这一个重跑是安全的；它只清除自己那个项目的场次。
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
