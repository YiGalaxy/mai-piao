import request from './request'

/**
 * pay-service endpoints, as reached through the gateway.
 *
 * <p>Callbacks are deliberately not here: a provider posts to the URL it was
 * given at onboarding, which points straight at pay-service. Payment
 * notifications do not travel through our gateway.
 */

/**
 * Creates (or reuses) the payment for an order and returns where to send the
 * user.
 *
 * Idempotent per order: reloading the payment page reuses the existing payment
 * rather than opening a second one that could be paid in parallel.
 */
export function precreatePayment(payload) {
  return request.post('/pay/precreate', payload)
}

/** Payment status, for polling while the provider processes. */
export function fetchPayment(paymentNo) {
  return request.get(`/pay/query/${paymentNo}`)
}

/** The payment for an order, so the order page can find it without an id. */
export function fetchPaymentByOrder(orderNo) {
  return request.get(`/pay/order/${orderNo}`)
}
