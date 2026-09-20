import { defineStore } from 'pinia'
import * as userApi from '../api/user'
import {
  clearSession,
  getStoredUser,
  getToken,
  setStoredUser,
  setToken
} from '../utils/session'

/**
 * Session state.
 *
 * Hydrated from localStorage on creation so a page refresh does not log the
 * user out, but the token itself is never trusted for display - `profile` is
 * refreshed from the server whenever we are not sure it is current.
 */
export const useUserStore = defineStore('user', {
  state: () => ({
    token: getToken(),
    profile: getStoredUser()
  }),

  getters: {
    isLoggedIn: (state) => Boolean(state.token),
    displayName: (state) => state.profile?.nickname || '未登录',
    maskedPhone: (state) => {
      const phone = state.profile?.phone
      return phone && phone.length === 11
        ? `${phone.slice(0, 3)}****${phone.slice(7)}`
        : ''
    }
  },

  actions: {
    async login(payload) {
      const data = await userApi.login(payload)
      this.applySession(data)
      return data
    },

    async register(payload) {
      const userId = await userApi.register(payload)
      // Registration does not return a token, so sign in straight away -
      // making the user type the password again immediately after choosing it
      // is friction with no security benefit.
      await this.login({ phone: payload.phone, password: payload.password })
      return userId
    },

    /** Re-reads the profile. Called on app start and after any profile change. */
    async refreshProfile() {
      const profile = await userApi.fetchProfile()
      this.profile = profile
      setStoredUser(profile)
      return profile
    },

    async logout() {
      try {
        // Best effort: the server adds the jti to the blacklist. If it fails,
        // the local session still goes away - the token simply stays valid
        // until it expires, which is the pre-existing behaviour anyway.
        await userApi.logout()
      } catch {
        // ignored on purpose
      } finally {
        this.clear()
      }
    },

    applySession({ token, userId, phone, nickname, avatar }) {
      this.token = token
      this.profile = { id: userId, phone, nickname, avatar }
      setToken(token)
      setStoredUser(this.profile)
    },

    clear() {
      this.token = ''
      this.profile = null
      clearSession()
    }
  }
})
