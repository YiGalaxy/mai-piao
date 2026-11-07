-- ============================================================
-- 展示用的场馆与项目：一场体育场演唱会。
--
-- 只有静态行。场次、票档和 2000 行座位都是生成的，因为手写 2000 行算术
-- 就是 2000 次把偏移量写错的机会 —— 而这些偏移量就是 bitmap 位置。
--
-- 顺序很重要。生成器的 generate-schedule 接口在写入新场次之前会清空所有
-- 已存在的场次，所以这个项目的场次由另一个必须跑在它之后的接口产生：
--
--     POST /movie/demo/generate-schedule      （先清空，再全量重建）
--     POST /movie/demo/generate-showcase      （只生成这个项目）
--
-- 顺序反过来跑，展示数据就被静默删掉了。
-- ============================================================

SET NAMES utf8mb4;

USE maipiao_event;

-- ------------------------------------------------------------
-- 一座体育场，数据集里本来没有这种场馆。
-- venue_type 本来就允许 STADIUM；place_type 不允许，而新增一种就意味着
-- 要教会生成器一个新的基础价、以及针对每一类演出的新分支 —— 所以这里把
-- place 声明成 ARENA，它的行为也确实像 ARENA：一个巨大的对号入座碗形场。
-- 它自己的票档由展示数据生成器写入，而不是从 place_type 推导出来。
-- ------------------------------------------------------------

INSERT INTO t_event_venue (id, name, venue_type, address, district, phone, longitude, latitude, status)
VALUES (2199, '深圳湾体育中心·春茧体育场', 'STADIUM', '南山区滨海大道3001号', '南山区',
        '0755-86360000', 113.993200, 22.513400, 1)
ON DUPLICATE KEY UPDATE name = VALUES(name);

-- 40 排 x 50 个可用列 = 正好 2000 个座位。
--
-- 第 17 列和第 35 列是通道，所以座位编号到这两处就有了断档，seat index
-- 也不再等于列位置 —— 邻座必须按几何关系算出来，原因就在这里。
-- 没有坏座：展示数据的卖点是「2000 张票」，少四个角就变成 1996 了。
INSERT INTO t_event_place
  (id, venue_id, name, place_type, seating_mode, row_count, col_count, seat_template, seat_count, status)
VALUES (3199, 2199, '主体育场', 'ARENA', 'SEATED', 40, 52,
        '{"rows":40,"cols":52,"aisleCols":[17,35],"brokenSeats":[],"coupleSeats":[]}',
        2000, 1)
ON DUPLICATE KEY UPDATE name = VALUES(name), seat_count = VALUES(seat_count);

-- ------------------------------------------------------------
-- 艺人。category 为 CONCERT 会把它路由到演出类场馆，并给它多个票档，
-- 而不是影片那种单一票档。
-- ------------------------------------------------------------

INSERT INTO t_event_project
  (id, category, title, en_title, poster_url, duration, tags, show_date, score,
   artist, organizer, description, status)
VALUES (1199, 'CONCERT', '周杰伦「嘉年华」世界巡回演唱会·深圳站', 'Jay Chou Carnival World Tour',
        '/img/poster/1199.jpg', 180, '流行,华语,巡演', '2026-11-15', 9.4,
        '周杰伦', '杰威尔音乐',
        '嘉年华世界巡回演唱会深圳站，内场与看台分区售票，全场对号入座。系统按票档顺序分配连座。', 1)
ON DUPLICATE KEY UPDATE title = VALUES(title), status = VALUES(status);
