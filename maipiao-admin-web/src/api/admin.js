import request from './request'

/**
 * Admin endpoints, all under /api/movie/admin.
 *
 * Through the gateway, which refuses the whole /api/*&#47;admin/** surface to a
 * token without the admin role. The frontend does not enforce that and must
 * not appear to: hiding a button is not access control, and the check that
 * matters is the one the gateway does on every call.
 */

/**
 * Venues and their rooms, nested, for the picker and the management screen.
 *
 * includeClosed defaults to true because this list is also where a venue taken
 * out of service has to remain visible - otherwise taking it out looks like
 * deleting it.
 */
export function fetchVenues() {
  return request.get('/movie/admin/venues', { params: { includeClosed: true } })
}

export function createVenue(payload) {
  return request.post('/movie/admin/venues', payload)
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
 * Changes how a session sells, not what it is selling.
 *
 * Date, time and price bands are not accepted here on purpose: moving a
 * session moves every seat it sold, and re-banding one remaps seats people
 * already hold.
 */
export function updateSession(sessionId, payload) {
  return request.put(`/movie/admin/sessions/${sessionId}`, payload)
}

/** Projects, newest first; optionally narrowed to one category. */
export function fetchProjects(category) {
  return request.get('/movie/admin/projects', { params: { category } })
}

/** A project's dates. What an administrator checks after adding one. */
export function fetchProjectSessions(projectId) {
  return request.get(`/movie/admin/projects/${projectId}/sessions`)
}

export function createProject(payload) {
  return request.post('/movie/admin/projects', payload)
}

/**
 * Puts a project on sale for one date at one place.
 *
 * One call, one night. A three-night run is three calls, because they are
 * three separate things to put on sale.
 */
export function createSession(payload) {
  return request.post('/movie/admin/sessions', payload)
}

export function deleteSession(sessionId) {
  return request.delete(`/movie/admin/sessions/${sessionId}`)
}

// ------------------------------------------------------------
// users
// ------------------------------------------------------------

export function fetchUsers(params) {
  return request.get('/user/admin/users', { params })
}

export function fetchUser(userId) {
  return request.get(`/user/admin/users/${userId}`)
}

/** 0 disables the account, 1 re-enables it. Disabling is the only removal. */
export function setUserStatus(userId, status) {
  return request.put(`/user/admin/users/${userId}/status`, null, { params: { status } })
}

/** 'ADMIN' or 'USER'. Refused for yourself, and for the last administrator. */
export function setUserRole(userId, role) {
  return request.put(`/user/admin/users/${userId}/role`, null, { params: { role } })
}

// ------------------------------------------------------------
// orders
// ------------------------------------------------------------

export function fetchOrders(params) {
  return request.get('/order/admin/orders', { params })
}

export function fetchOrder(orderNo) {
  return request.get(`/order/admin/orders/${orderNo}`)
}
