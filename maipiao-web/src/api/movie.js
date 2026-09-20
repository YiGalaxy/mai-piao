import request from './request'

/**
 * movie-service endpoints, as reached through the gateway.
 *
 * Browsing is public - the gateway whitelists /api/movie/**, so no token is
 * required to look at what is showing. A token is still sent when the user
 * has one, which is what lets the backend personalise later without a
 * separate "logged-in" endpoint.
 */

/**
 * The catalogue.
 *
 * @param {object} params
 * @param {number} [params.status]   0=upcoming 1=on sale 2=closed
 * @param {string} [params.category] MOVIE / CONCERT / TALK_SHOW / THEATER / MUSICAL.
 *                                   Omit for everything - filtering happens
 *                                   server-side, so a category page does not
 *                                   download the whole catalogue to discard most
 *                                   of it.
 */
export function fetchFilms(params = {}) {
  return request.get('/movie/film/list', { params })
}

/** Categories the catalogue actually uses, for building the nav tabs. */
export const CATEGORIES = [
  { value: 'MOVIE', label: '电影' },
  { value: 'CONCERT', label: '演唱会' },
  { value: 'TALK_SHOW', label: '脱口秀' },
  { value: 'THEATER', label: '话剧' },
  { value: 'MUSICAL', label: '音乐剧' }
]

export function fetchFilmDetail(filmId) {
  return request.get(`/movie/film/${filmId}`)
}

export function fetchCinemas(params = {}) {
  return request.get('/movie/cinema/list', { params })
}

export function fetchSchedules(params = {}) {
  return request.get('/movie/schedule/list', { params })
}

export function fetchScheduleDetail(scheduleId) {
  return request.get(`/movie/schedule/${scheduleId}`)
}
