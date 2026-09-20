import request from './request'

/**
 * 管理端接口，都在 /api/movie/admin 下。
 *
 * 全部经网关访问，网关会把整个 /api/*&#47;admin/** 面拒绝给不带管理员角色的
 * token。前端不做这个校验，也不该让人觉得它在做：藏起一个按钮不是访问控制，
 * 真正算数的是网关在每次调用上做的那道检查。
 */

/**
 * 场馆及其下属场地，嵌套返回，供选择器和管理页使用。
 *
 * includeClosed 默认为 true，因为这份列表也是停用场馆必须继续露面的地方 ——
 * 否则「停用」看起来就跟「删除」一样了。
 */
export function fetchVenues() {
  return request.get('/movie/admin/venues', { params: { includeClosed: true } })
}

export function createVenue(payload) {
  return request.post('/movie/admin/venues', payload)
}

/**
 * 一次建好场馆和它下面的场地。
 *
 * 界面走的是这一个。分成两次调用会留下「场馆建好了、场地没建成」的中间状态，
 * 那种场馆排不了演出也卖不了票 —— 而在界面上它和「场地填错了」长得一模一样。
 */
export function createVenueWithPlaces(payload) {
  return request.post('/movie/admin/venues/full', payload)
}

export function updateVenue(venueId, payload) {
  return request.put(`/movie/admin/venues/${venueId}`, payload)
}

export function createPlace(payload) {
  return request.post('/movie/admin/places', payload)
}

export function updatePlace(placeId, payload) {
  return request.put(`/movie/admin/places/${placeId}`, payload)
}

export function updateProject(projectId, payload) {
  return request.put(`/movie/admin/projects/${projectId}`, payload)
}

/**
 * 改的是场次怎么卖，不是卖什么。
 *
 * 这里刻意不接受日期、时间和票价档位：改场次时间会连带挪动它已经卖出的每一个
 * 座位，重划档位则会把用户手里已经持有的座位重新映射一遍。
 */
export function updateSession(sessionId, payload) {
  return request.put(`/movie/admin/sessions/${sessionId}`, payload)
}

/** 剧目列表，最新的在前；可选地只筛一个分类。 */
export function fetchProjects(category) {
  return request.get('/movie/admin/projects', { params: { category } })
}

/** 某个剧目的所有场次日期。管理员加完之后就是来看这个的。 */
export function fetchProjectSessions(projectId) {
  return request.get(`/movie/admin/projects/${projectId}/sessions`)
}

export function createProject(payload) {
  return request.post('/movie/admin/projects', payload)
}

/**
 * 把一个剧目在某一天、某个场地开卖。
 *
 * 一次调用就是一场。连演三晚就是三次调用，因为那本来就是三件要分别上架的事。
 */
export function createSession(payload) {
  return request.post('/movie/admin/sessions', payload)
}

export function deleteSession(sessionId) {
  return request.delete(`/movie/admin/sessions/${sessionId}`)
}

// ------------------------------------------------------------
// 用户
// ------------------------------------------------------------

export function fetchUsers(params) {
  return request.get('/user/admin/users', { params })
}

export function fetchUser(userId) {
  return request.get(`/user/admin/users/${userId}`)
}

/** 0 停用账号，1 重新启用。停用是唯一的「删除」方式。 */
export function setUserStatus(userId, status) {
  return request.put(`/user/admin/users/${userId}/status`, null, { params: { status } })
}

/** 'ADMIN' 或 'USER'。改自己会被拒，改最后一个管理员也会被拒。 */
export function setUserRole(userId, role) {
  return request.put(`/user/admin/users/${userId}/role`, null, { params: { role } })
}

// ------------------------------------------------------------
// 订单
// ------------------------------------------------------------

export function fetchOrders(params) {
  return request.get('/order/admin/orders', { params })
}

export function fetchOrder(orderNo) {
  return request.get(`/order/admin/orders/${orderNo}`)
}
