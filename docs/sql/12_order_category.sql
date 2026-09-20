-- ============================================================
-- The category a ticket was sold under, on the order.
--
-- The order already snapshots the title, the venue and the place, because it
-- is a record of what was bought and must not change when the catalogue does.
-- The category belongs with them: without it the customer's order page cannot
-- tell a cinema ticket from a concert ticket, so it calls both a film, sends
-- both to a cinema, and tells a concert-goer to collect their ticket from a
-- cinema box office.
--
-- Deriving it at read time from project_id would work today and break the
-- moment a project is recategorised or removed - on a historical record, which
-- is the one place that must not happen.
-- ============================================================

SET NAMES utf8mb4;

USE maipiao_order;

ALTER TABLE t_order_order
  ADD COLUMN category VARCHAR(16) NOT NULL DEFAULT 'MOVIE'
    COMMENT 'MOVIE / CONCERT / TALK_SHOW / THEATER / MUSICAL';
