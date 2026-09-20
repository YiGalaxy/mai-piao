import request from './request'

/**
 * Admin endpoints, all under /api/movie/admin.
 *
 * Through the gateway, which refuses the whole /api/*&#47;admin/** surface to a
 * token without the admin role. The frontend does not enforce that and must
 * not appear to: hiding a button is not access control, and the check that
 * matters is the one the gateway does on every call.
 */

/** Venues and their rooms, nested, for the place picker. */
export function fetchVenues() {
  return request.get('/movie/admin/venues')
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
