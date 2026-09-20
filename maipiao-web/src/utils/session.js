const TOKEN_KEY = 'maipiao_token'
const USER_KEY = 'maipiao_user'

/**
 * Session persistence.
 *
 * localStorage rather than a cookie: the API is called with a Bearer header
 * and nothing else, so there is no cookie to protect against CSRF and no
 * server-side session to keep in sync.
 *
 * The trade-off is real and worth stating: anything that can run script on
 * this origin can read the token. That is acceptable for a project where the
 * API and the app are served from the same place and no third-party script is
 * loaded; it would not be for a page embedding untrusted widgets.
 */

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setToken(token) {
  localStorage.setItem(TOKEN_KEY, token)
}

export function getStoredUser() {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) {
    return null
  }
  try {
    return JSON.parse(raw)
  } catch {
    // Corrupted entry - drop it rather than crashing every page that reads it.
    localStorage.removeItem(USER_KEY)
    return null
  }
}

export function setStoredUser(user) {
  localStorage.setItem(USER_KEY, JSON.stringify(user))
}

export function clearSession() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}
