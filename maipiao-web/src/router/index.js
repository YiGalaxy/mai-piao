import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from '../utils/session'

const routes = [
  {
    path: '/',
    name: 'home',
    component: () => import('../views/HomeView.vue')
  },
  {
    path: '/films',
    name: 'films',
    component: () => import('../views/FilmListView.vue')
  },
  {
    path: '/films/:id',
    name: 'film-detail',
    component: () => import('../views/FilmDetailView.vue')
  },
  {
    path: '/schedules/:id/seats',
    name: 'seat-select',
    component: () => import('../views/SeatSelectView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/LoginView.vue'),
    meta: { guestOnly: true }
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('../views/RegisterView.vue'),
    meta: { guestOnly: true }
  },
  {
    path: '/profile',
    name: 'profile',
    component: () => import('../views/ProfileView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/coupons',
    name: 'coupons',
    component: () => import('../views/CouponView.vue'),
    meta: { requiresAuth: true }
  },
  {
    // Anything unrecognised goes home rather than showing a blank page.
    path: '/:pathMatch(.*)*',
    redirect: '/'
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

/**
 * Route guard.
 *
 * A UX guard, not a security boundary. Everything it protects is also enforced
 * server-side by the gateway - editing localStorage or calling the API directly
 * still yields a 401. Its job is to avoid showing a screen that is guaranteed
 * to fail.
 */
router.beforeEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · 麦票` : '麦票'

  const loggedIn = Boolean(getToken())

  if (to.meta.requiresAuth && !loggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  if (to.meta.guestOnly && loggedIn) {
    return { name: 'home' }
  }

  return true
})

export default router
