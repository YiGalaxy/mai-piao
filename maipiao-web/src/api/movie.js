import request from './request'

/**
 * movie-service 的接口，都是经网关访问的。
 *
 * 浏览是公开的 —— 网关把 /api/movie/** 放进了白名单，看有什么在映不需要
 * token。用户手上要是有 token，仍然会带上，这样后端以后要做个性化，
 * 不必再单开一个「已登录」版本的接口。
 */

/**
 * 剧目列表。
 *
 * @param {object} params
 * @param {number} [params.status]   0=即将开售 1=在售 2=已结束
 * @param {string} [params.category] MOVIE / CONCERT / TALK_SHOW / THEATER / MUSICAL。
 *                                   不传就是全部 —— 过滤在服务端做，这样分类页
 *                                   不用把整个剧目库拉下来再丢掉大部分。
 */
export function fetchFilms(params = {}) {
  return request.get('/movie/film/list', { params })
}

/** 剧目库实际用到的分类，用来拼导航标签。 */
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
