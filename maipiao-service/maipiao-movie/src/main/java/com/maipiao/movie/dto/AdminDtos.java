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

/** 后台管理接口的请求和响应 DTO。 */
public final class AdminDtos {

    private AdminDtos() {
    }

    /** 创建待排期的项目。 */
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

    /** 按排区间定义票价档；rowEnd 为 0 表示延续到末排。 */
    public record TierSpec(
            @NotBlank(message = "票档名不能为空") String name,
            @NotNull(message = "票价不能为空") @Positive(message = "票价必须为正") BigDecimal price,
            @NotNull(message = "起始排不能为空") Integer rowStart,
            Integer rowEnd,
            String color
    ) {
    }

    /** 在指定场地和时间创建一个场次。 */
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

    /** 项目及其场次数量摘要。 */
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

    /** 场馆信息。 */
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

    /** 场地信息及座位模板；模板仅影响后续场次。 */
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

    /** 可编辑的场次售卖配置；日期、时间和票档创建后不可改。 */
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

    /** 创建场馆时使用的场地信息，由服务端补充 venueId。 */
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

    /** 在一个请求中创建场馆及其场地。 */
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
