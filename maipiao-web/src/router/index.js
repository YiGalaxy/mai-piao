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
  // The other way to buy a seat: the venue assigns it. Separate route rather
  // than a mode inside the seat map, because the two pages share no state -
  // one fetches a map and tracks selections, the other fetches bands and a
  // quantity - and the choice between them is made before either loads.
  {
    path: '/schedules/:id/tickets',
    name: 'ticket-select',
    component: () => import('../views/TicketSelectView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/schedules/:id/queue',
    name: 'rush-queue',
    component: () => import('../views/QueueView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/checkout',
    name: 'checkout',
    component: () => import('../views/CheckoutView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/orders',
    name: 'orders',
    component: () => import('../views/OrderListView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/orders/:orderNo',
    name: 'order-detail',
    component: () => import('../views/OrderDetailView.vue'),
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
