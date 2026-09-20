/**
 * The words to use for a given kind of event.
 *
 * A cinema ticket and a concert ticket are the same transaction to the
 * backend - a seat, a price, an order - and deliberately so. But they are not
 * the same thing to the person holding one, and the interface was written for
 * films and then applied to everything: it told concert-goers their "film"
 * started at eight, sent them to a "cinema", and asked them to collect their
 * ticket from a cinema box office.
 *
 * So the vocabulary follows the category. One place decides it, because the
 * alternative is the same ternary repeated across every view and eventually
 * disagreeing with itself.
 */

const MOVIE = 'MOVIE'

/** What is being sold: a film, or a performance. */
const SUBJECT = {
  MOVIE: '影片',
  CONCERT: '演出',
  TALK_SHOW: '演出',
  THEATER: '演出',
  MUSICAL: '演出'
}

/** Where it happens. A concert is not at a cinema. */
const VENUE = {
  MOVIE: '影院',
  CONCERT: '场馆',
  TALK_SHOW: '场馆',
  THEATER: '剧场',
  MUSICAL: '剧场'
}

/** The room inside the venue. */
const PLACE = {
  MOVIE: '影厅',
  CONCERT: '场地',
  TALK_SHOW: '场地',
  THEATER: '剧场',
  MUSICAL: '剧场'
}

/**
 * How sessions come about.
 *
 * A cinema schedules: the same film, many times a day, for weeks. A concert is
 * announced: one night, at one venue, months ahead. "排片" is the scheduling
 * word and reads as nonsense above a concert listing.
 */
const SCHEDULE = {
  MOVIE: '排片',
  CONCERT: '场次',
  TALK_SHOW: '场次',
  THEATER: '场次',
  MUSICAL: '场次'
}

/** How the ticket is collected. */
const COLLECT = {
  MOVIE: '请凭取票码到影院自助机取票',
  CONCERT: '请凭电子票及本人证件入场',
  TALK_SHOW: '请凭电子票入场',
  THEATER: '请凭电子票入场',
  MUSICAL: '请凭电子票入场'
}

function pick(table, category) {
  return table[category] || table.movie || table[MOVIE] || ''
}

/** True when this is a film, which is the only category with a cinema. */
export function isFilm(category) {
  return !category || category === MOVIE
}

export function subjectOf(category) {
  return pick(SUBJECT, category)
}

export function venueOf(category) {
  return pick(VENUE, category)
}

export function placeOf(category) {
  return pick(PLACE, category)
}

export function scheduleOf(category) {
  return pick(SCHEDULE, category)
}

export function collectHintOf(category) {
  return pick(COLLECT, category)
}
