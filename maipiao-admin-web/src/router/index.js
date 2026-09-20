import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from '../utils/session'

const routes = [
  { path: '/', redirect: '/performances' },
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/LoginView.vue')
  },
  {
    path: '/performances',
    name: 'performances',
    component: () => import('../views/PerformanceListView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/performances/new',
    name: 'performance-new',
    component: () => import('../views/PerformanceEditView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/performances/:id',
    name: 'performance-detail',
    component: () => import('../views/PerformanceDetailView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/venues',
    name: 'venues',
    component: () => import('../views/VenueListView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/users',
    name: 'users',
    component: () => import('../views/UserListView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/orders',
    name: 'orders',
    component: () => import('../views/OrderListView.vue'),
    meta: { requiresAuth: true }
  },
  { path: '/:pathMatch(.*)*', redirect: '/performances' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

/**
 * A redirect for somebody who has not signed in, not a security boundary.
 *
 * The token in localStorage says nothing about whether it is an admin one, and
 * this guard does not pretend to check. The gateway refuses the admin API to a
 * non-admin token, so a user who gets past this sees empty pages and errors -
 * which is the correct outcome, and the reason the real check lives there.
 */
router.beforeEach((to) => {
  if (to.meta.requiresAuth && !getToken()) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
