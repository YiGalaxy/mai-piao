package com.maipiao.movie.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 后台界面用的数据结构。
 *
 * <p>和公开的 DTO 分开，因为两者回答的是不同的问题。买家问的是现在有什么；管理员
 * 说的是将会有什么，而且他需要能直接指定日期、场馆和价格，而不是从别人生成好的
 * 列表里挑。
 *
 * <p>这一块正是演示生成器当初代为顶上的位置。一场演出是提前几个月公布的 ——
 * 一个晚上、一个场馆 —— 而当时没有办法表达这件事，于是生成器替它说了，还说错了：
 * 一场演唱会被排成一天十二场、六个场馆、连着一个星期。
 */
public final class AdminDtos {

    private AdminDtos() {
    }

    /**
     * 创建被售卖的那个东西，此时还没有挂任何日期。
     *
     * <p>{@code category} 几乎决定了下游的一切：海报上写的是 导演 还是 艺人、场次
     * 叫 排片 还是 场次、以及到底提不提供座位图。
     */
    public record CreateProjectRequest(
            @NotBlank(message = "标题不能为空") String title,
            String enTitle,
            @NotBlank(message = "类型不能为空") String category,
            /** 演唱会和舞台演出用；电影则改用导演和演员。 */
            String artist,
            String organizer,
            String director,
            String actors,
            String tags,
            String posterUrl,
            String description,
            Integer duration,
            LocalDate showDate
    ) {
    }

    /**
     * 一个票价档。
     *
     * <p>用排区间而不是座位清单，因为场馆就是这么做买卖的：「1 到 8 排是 VIP 区」。
     * {@code rowEnd} 为 0 表示一直到场馆末尾，这样最后一档不必知道自己有多少排。
     */
    public record TierSpec(
            @NotBlank(message = "票档名不能为空") String name,
            @NotNull(message = "票价不能为空") @Positive(message = "票价必须为正") BigDecimal price,
            @NotNull(message = "起始排不能为空") Integer rowStart,
            Integer rowEnd,
            String color
    ) {
    }

    /**
     * 让一个项目在某个场地、某个时间上架开卖。
     *
     * <p>电影院会从循环排期里推出来的每个字段，这里都改成直接写出来，因为演出没有
     * 排期可推 —— 它只有一个日期。一次调用创建一晚。
     *
     * <p>{@code tierSpecs} 是必填而不是给默认值。一个没有任何票档的场次会把每个座位
     * 定成零价，而一场免费演唱会「是失误」的次数远远多于「是有意为之」。
     */
    public record CreateSessionRequest(
            @NotNull(message = "项目不能为空") Long projectId,
            @NotNull(message = "场馆不能为空") Long placeId,
            @NotNull(message = "日期不能为空") LocalDate showDate,
            @NotNull(message = "开演时间不能为空") LocalTime startTime,
            /** 0 = 买家自己选座，1 = 系统分配。null 按 0 处理。 */
            Integer seatMode,
            /** 0 表示除平台上限之外不再限制。 */
            Integer purchaseLimit,
            Integer requireRealName,
            /** 1 表示这场售卖走排队。 */
            Integer rushMode,
            /** 队列开启时间；rushMode 为 1 时必填。 */
            LocalDateTime rushStartTime,
            /** 开票时间；null 表示立即开票。 */
            LocalDateTime saleStartTime,
            @NotEmpty(message = "至少需要一个票档") @Valid List<TierSpec> tierSpecs
    ) {
    }

    /**
     * 一场演出，连同它上演的日期。
     *
     * <p>成组返回，因为管理员就是按这个单位想的：这场演出，以及它什么时候演。平铺的
     * 场次列表也有，但它回答的是排期问题，不是编排问题。
     */
    public record ProjectSummary(
            Long id,
            String title,
            String category,
            String artist,
            LocalDate showDate,
            Integer status,
            int sessionCount
    ) {
    }

    // ------------------------------------------------------------
    // 场馆和场地
    // ------------------------------------------------------------

    /**
     * 一个场馆：那栋楼。
     *
     * <p>{@code venueType} 不是装饰 —— 它既是后台界面分组的依据，也是有人在找地方
     * 办演出时，用来区分体育场和影厅的那个字段。
     */
    public record VenueRequest(
            @NotBlank(message = "名称不能为空") String name,
            @NotBlank(message = "类型不能为空") String venueType,
            String address,
            String district,
            String phone,
            BigDecimal longitude,
            BigDecimal latitude,
            /** 0 停业，1 营业。传 null 默认营业。 */
            Integer status
    ) {
    }

    /**
     * 场馆里的一个场地，以及它里面的座位网格。
     *
     * <p>{@code seatTemplate} 是场馆对自己的描述 —— 过道、坏座、成对座 —— 在这里
     * 创建的每个场次都按它构造座位行。所以改它会改变之后场次的样子，而不动已有的：
     * 那些场次的座位是当时写下来的，对它们来说那就是事实。一个场子完全可能在两场
     * 活动之间被重新布置，硬要装作不会，等于拒绝一个再平常不过的改动。
     */
    public record PlaceRequest(
            @NotNull(message = "所属场馆不能为空") Long venueId,
            @NotBlank(message = "名称不能为空") String name,
            @NotBlank(message = "场地类型不能为空") String placeType,
            /** SEATED / STANDING / MIXED。 */
            @NotBlank(message = "座位形式不能为空") String seatingMode,
            @NotNull(message = "行数不能为空") @Positive Integer rowCount,
            @NotNull(message = "列数不能为空") @Positive Integer colCount,
            /** JSON: {"aisleCols":[9,24],"brokenSeats":["1-1"],"coupleSeats":[["7-8","7-9"]]} */
            String seatTemplate,
            /** 声明的容量。仅供参考；场次自己数自己的座位行。 */
            Integer seatCount,
            Integer status
    ) {
    }

    /** 一个项目，按它在创建之后可以被编辑的样子。 */
    public record UpdateProjectRequest(
            String title,
            String enTitle,
            String artist,
            String organizer,
            String director,
            String actors,
            String tags,
            String posterUrl,
            String description,
            Integer duration,
            LocalDate showDate,
            /** 0 待映，1 在售，2 下架。 */
            Integer status
    ) {
    }

    /**
     * 一个场次，按它在创建之后可以被编辑的样子。
     *
     * <p>日期、时间和票价档是故意缺位的。挪动一个场次会连带动到它卖出去的每一个
     * 座位，重新划分票档会把人们已经握在手里的座位重新映射 —— 两者都是披着伪装的
     * 取消，而取消是要退钱的。这里有的东西改的是票怎么卖，不是已经卖了什么。
     */
    public record UpdateSessionRequest(
            Integer purchaseLimit,
            Integer requireRealName,
            Integer rushMode,
            LocalDateTime rushStartTime,
            LocalDateTime saleStartTime,
            /** 0 在售，1 暂停。把场次下架即停止售卖。 */
            Integer status
    ) {
    }

    /**
     * 场地，但还没有归属的场馆。
     *
     * <p>和 {@link PlaceRequest} 的唯一区别就是没有 {@code venueId}，而这个区别是
     * 真的：嵌在「建场馆」请求里的场地，它属于哪个场馆是**服务端刚建出来的那个**，
     * 请求方根本无从知道。让这个字段必填然后传个占位值，是在用校验规则掩盖一个
     * 本来就不存在的字段。
     */
    public record PlaceSpec(
            @NotBlank(message = "名称不能为空") String name,
            @NotBlank(message = "场地类型不能为空") String placeType,
            @NotBlank(message = "座位形式不能为空") String seatingMode,
            @NotNull(message = "行数不能为空") @Positive Integer rowCount,
            @NotNull(message = "列数不能为空") @Positive Integer colCount,
            String seatTemplate,
            Integer seatCount,
            Integer status
    ) {
    }

    /**
     * 一次建好一个场馆和它下面的场地。
     *
     * <p>分开两次调用看起来更小，但会留下一个中间状态：场馆建好了、场地没建成。
     * 那种场馆排不了任何演出、也卖不了票，只能靠人去发现并重试 —— 而它跟「场地本身
     * 填错了」在界面上长得一模一样。
     *
     * <p>场馆就是它的场地。一次建完，要么都有要么都没有。
     */
    public record CreateVenueWithPlacesRequest(
            @Valid @NotNull(message = "场馆信息不能为空") VenueRequest venue,
            /** 可以为空：先把场馆建出来、场地稍后再加，也是合理的用法。 */
            @Valid List<PlaceSpec> places
    ) {
    }

    /** 创建一个场次产出了什么，好让调用方核对。 */
    public record SessionCreated(
            Long sessionId,
            Long placeId,
            String venueName,
            String placeName,
            LocalDate showDate,
            LocalTime startTime,
            int totalSeat,
            int tierCount
    ) {
    }
}
