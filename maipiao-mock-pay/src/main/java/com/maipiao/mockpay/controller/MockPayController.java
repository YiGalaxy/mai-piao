package com.maipiao.mockpay.controller;

import com.maipiao.mockpay.service.MockNotifySender;
import com.maipiao.mockpay.service.MockPayStore;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 假服务商的 API，外加一个可以手动点着走的收银台页面。
 *
 * <p>面向两类使用者：
 * <ul>
 *   <li>pay-service，它调用 {@code /precreate} 的方式，和调用真实服务商的下单接口
 *       一模一样。</li>
 *   <li>运维人员，他用 {@code /admin/**} 投递那些真实服务商不会应要求造出来的回调
 *       形态。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/mock-pay")
@RequiredArgsConstructor
public class MockPayController {

    private final MockPayStore store;
    private final MockNotifySender notifySender;

    @Value("${maipiao.mock-pay.callback-delay-ms:800}")
    private long callbackDelayMs;

    // ============================================================
    // 面向服务商协议的那一面
    // ============================================================

    @Data
    public static class PrecreateRequest {
        private String paymentNo;
        private String orderNo;
        private BigDecimal amount;
    }

    @Data
    public static class PrecreateResponse {
        private String channelTradeNo;
        private String cashierUrl;
        private String status;
    }

    /**
     * 创建一个支付会话，并返回该把用户送去哪里。
     *
     * <p>照着真实服务商下单接口的返回来做：一个他们那边的标识，加上一个客户端要打开的
     * URL。
     */
    @PostMapping("/precreate")
    public PrecreateResponse precreate(@RequestBody PrecreateRequest request) {
        var session = store.createOrGet(request.getPaymentNo(), request.getOrderNo(), request.getAmount());

        PrecreateResponse response = new PrecreateResponse();
        response.setChannelTradeNo(session.getChannelTradeNo());
        response.setCashierUrl("http://127.0.0.1:9007/mock-pay/cashier/" + session.getChannelTradeNo());
        response.setStatus(session.getStatus());

        log.info("payment session created: paymentNo={}, channelTradeNo={}",
                request.getPaymentNo(), session.getChannelTradeNo());
        return response;
    }

    // ============================================================
    // 一个由人手动点着走的收银台页面
    // ============================================================

    @GetMapping(value = "/cashier/{channelTradeNo}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> cashier(@PathVariable String channelTradeNo) {
        var session = store.get(channelTradeNo);
        if (session == null) {
            return ResponseEntity.ok(page("会话不存在", "这个支付会话已经失效，请回到订单页重新发起支付。", null));
        }
        return ResponseEntity.ok(page(
                "模拟收银台",
                "这是模拟支付网关的收银台页面，用于演示。",
                session));
    }

    /**
     * 用户按下"支付"。
     *
     * <p>刻意做成异步的：服务商立刻返回，结果通过回调送达，真实服务商就是这么做的，
     * 这也是为什么客户端必须轮询，而不能当场就得到答复。
     */
    @PostMapping("/cashier/{channelTradeNo}/confirm")
    public Map<String, Object> confirm(@PathVariable String channelTradeNo) {
        var session = store.get(channelTradeNo);
        if (session == null) {
            return Map.of("success", false, "message", "session not found");
        }

        store.markSuccess(channelTradeNo);

        // 发出去就不管了，延迟一小会儿，就像真实服务商那样。
        new Thread(() -> notifySender.sendAfter(
                session.getChannelTradeNo(), session.getPaymentNo(), session.getOrderNo(),
                session.getAmount(), "SUCCESS", callbackDelayMs)).start();

        return Map.of("success", true, "message", "支付已受理，结果将异步通知商户");
    }

    // ============================================================
    // 运维控制
    // ============================================================

    @GetMapping("/admin/sessions")
    public List<MockPayStore.Session> sessions() {
        return List.copyOf(store.list());
    }

    @Data
    public static class NotifyRequest {
        private String channelTradeNo;
        private String paymentNo;
        private String orderNo;
        private BigDecimal amount;
        /** SUCCESS 或 FAILED */
        private String status = "SUCCESS";
        /** 同一条回调投递多少次。 */
        private int times = 1;
        /** 投递前延迟多少毫秒。 */
        private long delayMs = 0;
        /** 发送一个无效签名。 */
        private boolean breakSignature = false;
    }

    @Data
    public static class NotifyResult {
        private int delivered;
        private int failed;
        private List<MockNotifySender.NotifyOutcome> outcomes;
    }

    /**
     * 按运维人员要求的形态投递一条回调。
     *
     * <p>这个服务存在的理由就在这里。想在真实沙箱上复现"服务商发了两次"或者"服务商在
     * 我们取消之后又发来一条成功"，意味着要等一个可能永远不会产生那个确切序列的重试
     * 节奏。
     */
    @PostMapping("/admin/notify")
    public NotifyResult notify(@RequestBody NotifyRequest request) {
        MockPayStore.Session session = resolveSession(request);
        if (session == null) {
            NotifyResult result = new NotifyResult();
            result.setFailed(1);
            result.setOutcomes(List.of());
            return result;
        }

        String status = request.getStatus() == null ? "SUCCESS" : request.getStatus();
        int times = Math.max(1, request.getTimes());

        List<MockNotifySender.NotifyOutcome> outcomes;
        if (request.getDelayMs() > 0) {
            outcomes = List.of(notifySender.sendAfter(
                    session.getChannelTradeNo(), session.getPaymentNo(), session.getOrderNo(),
                    session.getAmount(), status, request.getDelayMs()));
        } else if (request.isBreakSignature()) {
            outcomes = List.of(notifySender.send(
                    session.getChannelTradeNo(), session.getPaymentNo(), session.getOrderNo(),
                    session.getAmount(), status, true));
        } else {
            outcomes = notifySender.sendRepeated(
                    session.getChannelTradeNo(), session.getPaymentNo(), session.getOrderNo(),
                    session.getAmount(), status, times);
        }

        NotifyResult result = new NotifyResult();
        result.setDelivered((int) outcomes.stream().filter(MockNotifySender.NotifyOutcome::delivered).count());
        result.setFailed((int) outcomes.stream().filter(o -> !o.delivered()).count());
        result.setOutcomes(outcomes);

        log.info("manual notify: paymentNo={}, status={}, times={}, delivered={}",
                session.getPaymentNo(), status, times, result.getDelivered());
        return result;
    }

    private MockPayStore.Session resolveSession(NotifyRequest request) {
        if (request.getChannelTradeNo() != null) {
            return store.get(request.getChannelTradeNo());
        }
        if (request.getPaymentNo() != null) {
            return store.findByPaymentNo(request.getPaymentNo());
        }
        return null;
    }

    // ------------------------------------------------------------

    private String page(String title, String message, MockPayStore.Session session) {
        String body;
        if (session == null) {
            body = """
                    <p class="msg">%s</p>
                    """.formatted(message);
        } else {
            body = """
                    <div class="card">
                      <div class="label">收款方</div>
                      <div class="value">麦票 · 在线电影售票</div>

                      <div class="label">订单号</div>
                      <div class="value mono">%s</div>

                      <div class="label">支付单号</div>
                      <div class="value mono">%s</div>

                      <div class="amount">¥%s</div>

                      <p class="msg">%s</p>

                      <button id="pay" onclick="confirmPay()">确认支付</button>
                      <p class="hint" id="hint">点击后支付结果会异步通知商户，本页面不会跳转</p>
                    </div>
                    <script>
                      function confirmPay() {
                        document.getElementById('pay').disabled = true;
                        document.getElementById('hint').textContent = '支付处理中…';
                        fetch('/mock-pay/cashier/%s/confirm', { method: 'POST' })
                          .then(r => r.json())
                          .then(() => {
                            document.getElementById('hint').textContent =
                              '支付已受理，可关闭本页面返回订单查看结果';
                          })
                          .catch(() => {
                            document.getElementById('hint').textContent = '请求失败，请重试';
                            document.getElementById('pay').disabled = false;
                          });
                      }
                    </script>
                    """.formatted(
                    session.getOrderNo(),
                    session.getPaymentNo(),
                    session.getAmount() == null ? "0.00" : session.getAmount().toPlainString(),
                    message,
                    session.getChannelTradeNo());
        }

        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>%s</title>
                  <style>
                    body { margin:0; min-height:100vh; display:flex; align-items:center;
                           justify-content:center; background:#f5f5f5;
                           font-family:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif; }
                    .card { background:#fff; border-radius:12px; padding:36px 40px; width:400px;
                            box-shadow:0 8px 32px rgba(0,0,0,.1); }
                    h1 { font-size:18px; margin:0 0 24px; color:#111; }
                    .label { font-size:12px; color:#999; margin-top:16px; }
                    .value { font-size:14px; color:#333; margin-top:4px; }
                    .mono { font-family:ui-monospace,Consolas,monospace; font-size:13px; }
                    .amount { font-size:32px; font-weight:700; color:#ff6700;
                              text-align:center; margin:28px 0; }
                    .msg { font-size:13px; color:#666; text-align:center; margin:0 0 20px; }
                    button { width:100%%; padding:13px; border:none; border-radius:999px;
                             background:#ff6700; color:#fff; font-size:16px; font-weight:500;
                             cursor:pointer; box-shadow:0 4px 12px rgba(255,103,0,.32); }
                    button:hover { background:#e05a00; }
                    button:disabled { opacity:.5; cursor:default; }
                    .hint { font-size:12px; color:#999; text-align:center; margin:14px 0 0; }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <h1>%s</h1>
                    %s
                  </div>
                </body>
                </html>
                """.formatted(title, title, body);
    }
}
