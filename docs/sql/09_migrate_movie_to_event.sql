-- ============================================================
-- Migrate the film catalogue into the unified event model
--
-- Films were the only category when the original schema was written, so they
-- live in maipiao_movie with names that say "movie". Now that a row in
-- t_event_project can be a concert, the film rows move across and gain the
-- category value that distinguishes them.
--
-- One-way, and safe to re-run: the inserts are keyed on ids that cannot
-- collide with the performance seed data (projects 1001-1024 vs 1101-1112,
-- venues 2001-2008 vs 2101-2106, places 3001-3040 vs 3101-3112).
--
-- Sessions and seats are NOT migrated. They are generated, not authored, and
-- regenerating them against the new schema is simpler and less error-prone
-- than translating a hundred thousand rows across a rename.
-- ============================================================

SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- projects : films become rows with category = MOVIE
--
-- The film-specific columns (director, actors) carry data; the performance
-- ones (artist, organizer) stay empty. Which set is populated is what the
-- category tells you.
-- ------------------------------------------------------------
INSERT INTO maipiao_event.t_event_project
  (id, category, title, en_title, poster_url, duration, tags, show_date, score, status,
   director, actors, artist, organizer, description)
SELECT
  id,
  'MOVIE',
  name,
  en_name,
  poster_url,
  duration,
  film_type,
  release_date,
  score,
  status,
  director,
  actors,
  '',
  '',
  ''
FROM maipiao_movie.t_movie_film
ON DUPLICATE KEY UPDATE
  title = VALUES(title),
  score = VALUES(score),
  status = VALUES(status);

-- ------------------------------------------------------------
-- venues : cinemas become venues of type CINEMA
-- ------------------------------------------------------------
INSERT INTO maipiao_event.t_event_venue
  (id, name, venue_type, address, district, phone, longitude, latitude, status)
SELECT id, name, 'CINEMA', address, district, phone, longitude, latitude, status
FROM maipiao_movie.t_movie_cinema
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  address = VALUES(address);

-- ------------------------------------------------------------
-- places : screens become places, all seated
--
-- A cinema screen is the simplest case in the model: seated, one price, no
-- admission control. That a concert needs more does not make this wrong -
-- it is the same model with fewer of its fields in play.
-- ------------------------------------------------------------
INSERT INTO maipiao_event.t_event_place
  (id, venue_id, name, place_type, seating_mode, row_count, col_count, seat_template, seat_count, status)
SELECT id, cinema_id, name, hall_type, 'SEATED', row_count, col_count, seat_template, seat_count, status
FROM maipiao_movie.t_movie_hall
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  seat_template = VALUES(seat_template);

-- ------------------------------------------------------------
-- Verification
-- ------------------------------------------------------------
SELECT 'projects by category' AS check_name;
SELECT category, COUNT(*) AS cnt FROM maipiao_event.t_event_project GROUP BY category ORDER BY category;

SELECT 'venues by type' AS check_name;
SELECT venue_type, COUNT(*) AS cnt FROM maipiao_event.t_event_venue GROUP BY venue_type ORDER BY venue_type;

SELECT 'places by seating mode' AS check_name;
SELECT seating_mode, COUNT(*) AS cnt FROM maipiao_event.t_event_place GROUP BY seating_mode;
