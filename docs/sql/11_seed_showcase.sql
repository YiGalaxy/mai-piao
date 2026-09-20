-- ============================================================
-- Showcase venue and project: a stadium concert.
--
-- Static rows only. Sessions, price bands and the 2000 seat rows are
-- generated, because 2000 rows of arithmetic written by hand is 2000 chances
-- to get an offset wrong - and the offsets are bitmap positions.
--
-- ORDER MATTERS. The generator's generate-schedule call clears every existing
-- session before writing new ones, so this project's sessions are produced by
-- a separate call that must run AFTER it:
--
--     POST /movie/demo/generate-schedule      (clears, then regenerates all)
--     POST /movie/demo/generate-showcase      (this project only)
--
-- Run them the other way round and the showcase is silently deleted.
-- ============================================================

SET NAMES utf8mb4;

USE maipiao_event;

-- ------------------------------------------------------------
-- A stadium, which the dataset does not otherwise have.
-- venue_type already allows STADIUM; place_type does not, and adding one
-- would mean teaching the generator a new base price and a new branch for
-- every kind of event - so the place is declared ARENA, which is what it
-- behaves like: a big seated bowl. Its own price bands are written by the
-- showcase generator rather than derived from the place type.
-- ------------------------------------------------------------

INSERT INTO t_event_venue (id, name, venue_type, address, district, phone, longitude, latitude, status)
VALUES (2199, '深圳湾体育中心·春茧体育场', 'STADIUM', '南山区滨海大道3001号', '南山区',
        '0755-86360000', 113.993200, 22.513400, 1)
ON DUPLICATE KEY UPDATE name = VALUES(name);

-- 40 rows x 50 usable columns = exactly 2000 seats.
--
-- Columns 17 and 35 are walkways, so the row numbering has gaps at those
-- points and seat index stops matching column position - which is the whole
-- reason adjacency is computed from the geometry. No broken seats: the
-- showcase headline is "2000 tickets", and four missing corners would make it
-- 1996.
INSERT INTO t_event_place
  (id, venue_id, name, place_type, seating_mode, row_count, col_count, seat_template, seat_count, status)
VALUES (3199, 2199, '主体育场', 'ARENA', 'SEATED', 40, 52,
        '{"rows":40,"cols":52,"aisleCols":[17,35],"brokenSeats":[],"coupleSeats":[]}',
        2000, 1)
ON DUPLICATE KEY UPDATE name = VALUES(name), seat_count = VALUES(seat_count);

-- ------------------------------------------------------------
-- The artist. category CONCERT routes it to performance venues and gives it
-- several price bands rather than the single band a film gets.
-- ------------------------------------------------------------

INSERT INTO t_event_project
  (id, category, title, en_title, poster_url, duration, tags, show_date, score,
   artist, organizer, description, status)
VALUES (1199, 'CONCERT', '周杰伦「嘉年华」世界巡回演唱会·深圳站', 'Jay Chou Carnival World Tour',
        '/img/poster/1199.jpg', 180, '流行,华语,巡演', '2026-11-15', 9.4,
        '周杰伦', '杰威尔音乐',
        '嘉年华世界巡回演唱会深圳站，内场与看台分区售票，全场对号入座。系统按票档顺序分配连座。', 1)
ON DUPLICATE KEY UPDATE title = VALUES(title), status = VALUES(status);
