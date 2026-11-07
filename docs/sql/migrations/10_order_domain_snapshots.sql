-- Align legacy movie/cinema snapshot column names with the order entity.
-- This preserves values and is intended for existing development databases.
ALTER TABLE maipiao_order.t_order_order
  RENAME COLUMN film_id TO project_id,
  RENAME COLUMN film_name TO project_title,
  RENAME COLUMN cinema_id TO venue_id,
  RENAME COLUMN cinema_name TO venue_name,
  RENAME COLUMN hall_name TO place_name;
