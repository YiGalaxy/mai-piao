const TOKEN_KEY = 'maipiao_token'
const USER_KEY = 'maipiao_user'

/**
 * 会话持久化。
 *
 * 用 localStorage 而不是 cookie：调 API 只靠 Bearer 头，别的什么都不用，
 * 所以没有 cookie 需要防 CSRF，也没有服务端会话要保持同步。
 *
 * 代价是真实存在的，值得说清楚：任何能在这个源上跑脚本的东西都能读到 token。
 * 对于一个 API 和应用同源部署、又不加载任何第三方脚本的项目来说，这可以接受；
 * 但如果页面上嵌了不可信的组件，就不行了。
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
    // 数据坏了 —— 直接丢掉，而不是让每个读它的页面都崩掉。
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
