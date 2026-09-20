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
 * @param {object} params
 * @param {number} [params.status] 0=upcoming 1=now showing 2=offline
 */
export function fetchFilms(params = {}) {
  return request.get('/movie/film/list', { params })
}

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
