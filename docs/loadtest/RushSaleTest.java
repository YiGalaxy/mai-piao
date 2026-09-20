import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 抢购压测生成器：N 个买家、一场次、座位数固定。
 *
 * <p>它走的是真实买家完整的链路——排队、等叫号、拿令牌去换座位——
 * 而不是直接猛打选座接口。因为真正有意思的问题不是选座服务有多快，
 * 而是在有人群、前面还挡着一道排队的情况下，系统卖出去的座位数
 * 是否刚好等于它拥有的座位数。
 *
 * <p>身份是这里现造的，不是注册出来的。注册一万个账号意味着要算一万次
 * bcrypt 哈希、每次约 100ms，那测的是密码哈希器，反而把被测对象埋掉了。
 * 这里的 token 是真的 HS256 token，用的密钥和网关校验时用的同一个，
 * 所以跑的链路是真实链路——只是跳过了建账号这一步。用户 id 必须已经在
 * 数据库里存在，因为座位账本对它们有外键约束。
 *
 * <pre>
 *   java RushSaleTest &lt;baseUrl&gt; &lt;scheduleId&gt; &lt;tierId&gt; &lt;seats&gt; \
 *        &lt;buyers&gt; &lt;threads&gt; &lt;firstUserId&gt; &lt;queue|direct&gt;
 * </pre>
 *
 * <p>{@code queue} 走的是排队加等待；{@code direct} 跳过排队直接打选座接口，
 * 用来展示排队到底在保护什么。
 */
public final class RushSaleTest {

    private static final String SECRET =
            "maipiao-ticket-local-dev-secret-key-change-me-in-production-2026";
    private static final String ISSUER = "maipiao-ticket";

    public static void main(String[] args) throws Exception {
        if (args.length < 8) {
            System.err.println("usage: RushSaleTest <baseUrl> <scheduleId> <tierId> <seats> "
                    + "<buyers> <threads> <firstUserId> <queue|direct>");
            System.exit(2);
        }

        String baseUrl = args[0];
        String scheduleId = args[1];
        String[] tierIds = args[2].split(",");
        int seats = Integer.parseInt(args[3]);
        int buyers = Integer.parseInt(args[4]);
        int threads = Integer.parseInt(args[5]);
        long firstUserId = Long.parseLong(args[6]);
        String mode = args[7].toLowerCase();
        boolean useQueue = "queue".equals(mode);
        // flood：不排队、不等待，就是对着一场已售罄的场次，能压出多少请求
        // 就压多少请求。这个数字回答的是"它能吸收多少流量"，和"排队表现如何"
        // 是两回事，两者的答案也不一样。
        boolean flood = "flood".equals(mode);

        System.out.printf("schedule=%s tiers=%s seats=%d buyers=%d threads=%d mode=%s%n",
                scheduleId, args[2], seats, buyers, threads, mode);
        System.out.println("---");

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .executor(daemonPool(threads))
                .build();

        // ---- token 签发 ----
        String[] tokens = new String[buyers];
        for (int i = 0; i < buyers; i++) {
            tokens[i] = mint(firstUserId + i);
        }

        AtomicInteger joined = new AtomicInteger();
        AtomicInteger admitted = new AtomicInteger();
        AtomicInteger bought = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();
        AtomicInteger errored = new AtomicInteger();

        AtomicLong joinNanos = new AtomicLong();
        AtomicLong buyNanos = new AtomicLong();

        // 记录每次成功抢到的是哪个座位。同一个座位被认领两次就是超卖，
        // 这种事怎么解读数据都是不可接受的。
        Map<String, AtomicInteger> seatTally = new ConcurrentHashMap<>();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(buyers);

        long wallStart;
        for (int i = 0; i < buyers; i++) {
            final String token = tokens[i];
            pool.submit(() -> {
                try {
                    start.await();
                    if (flood) {
                        // 这些请求本来就预期全都会被拒。测的是拒得有多便宜。
                        // 随便哪个票档都行：场次已售罄，票档根本不会被看到。
                        floodOne(client, baseUrl, scheduleId, tierIds[0], token,
                                refused, bought, soldOut, errored, buyNanos);
                        return;
                    }

                    String admission = null;
                    if (useQueue) {
                        admission = joinAndWait(client, baseUrl, scheduleId, token, joined,
                                admitted, soldOut, errored, joinNanos);
                        if (admission == null) {
                            return;
                        }
                    }
                    // 随机挑一个票档，这样整个场子会被填满，而不是某一个档
                    // 先卖光、剩下的测试时间都在测系统说"不"有多快。
                    String band = tierIds[ThreadLocalRandom.current().nextInt(tierIds.length)];
                    buy(client, baseUrl, scheduleId, band, token, admission, bought, refused,
                            soldOut, errored, buyNanos, seatTally);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        wallStart = System.nanoTime();
        start.countDown();
        done.await();
        long wallNanos = System.nanoTime() - wallStart;

        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        int doubled = 0;
        for (AtomicInteger count : seatTally.values()) {
            if (count.get() > 1) {
                doubled++;
            }
        }

        double seconds = wallNanos / 1_000_000_000.0;
        System.out.println();
        if (useQueue) {
            System.out.println("joined     : " + joined.get());
            System.out.println("admitted   : " + admitted.get());
        }
        System.out.println("bought     : " + bought.get());
        System.out.println("refused    : " + refused.get());
        System.out.println("sold-out   : " + soldOut.get());
        System.out.println("errors     : " + errored.get());
        System.out.println("---");
        System.out.printf("wall clock : %.2f s%n", seconds);
        System.out.printf("throughput : %.0f req/s%n", buyers / seconds);
        if (admitted.get() > 0) {
            System.out.printf("buy latency: %.1f ms%n",
                    buyNanos.get() / 1_000_000.0 / Math.max(1, admitted.get()));
        }
        System.out.println("---");
        System.out.println("distinct seats: " + seatTally.size());
        System.out.println("seats expected: " + seats);
        System.out.println("OVERSOLD SEATS: " + doubled
                + (doubled == 0 ? "  <- none" : "  <- DOUBLE BOOKED"));
    }

    // ------------------------------------------------------------

    /**
     * 排队，然后一直轮询，直到被放行、售罄，或者等得不耐烦了。
     *
     * @return 放行令牌；如果这个买家始终没进去，则返回 null
     */
    private static String joinAndWait(HttpClient client, String baseUrl, String scheduleId,
                                      String token, AtomicInteger joined, AtomicInteger admitted,
                                      AtomicInteger soldOut, AtomicInteger errored,
                                      AtomicLong joinNanos) {
        long t0 = System.nanoTime();
        String joinedBody = post(client, baseUrl + "/api/queue/join?scheduleId=" + scheduleId,
                null, token);
        joinNanos.addAndGet(System.nanoTime() - t0);

        if (joinedBody == null || !joinedBody.contains("\"success\":true")) {
            errored.incrementAndGet();
            return null;
        }
        if (joinedBody.contains("SOLD_OUT")) {
            soldOut.incrementAndGet();
            return null;
        }
        joined.incrementAndGet();

        // 轮询到调度器叫到我们为止。超时时间给得很宽，
        // 因为长队是这个测试的正常形态，不是故障。
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            String body = get(client, baseUrl + "/api/queue/position?scheduleId=" + scheduleId, token);
            if (body == null) {
                errored.incrementAndGet();
                return null;
            }
            if (body.contains("SOLD_OUT")) {
                soldOut.incrementAndGet();
                return null;
            }
            if (body.contains("PASSED")) {
                admitted.incrementAndGet();
                // 这个放行凭证必须一路带到下单那一步，否则选座服务会拒掉它——
                // 这正是令牌存在的意义，忘记带上它的测试，测到的只是拒绝路径。
                return field(body, "token");
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /** 不用 JSON 解析器，直接从响应里抠出一个字符串字段。 */
    private static String field(String body, String name) {
        int at = body.indexOf("\"" + name + "\":\"");
        if (at < 0) {
            return null;
        }
        int from = at + name.length() + 4;
        int to = body.indexOf('"', from);
        return to < 0 ? null : body.substring(from, to);
    }

    /**
     * 对一场已售罄的场次发一个请求，不指望它能成。
     *
     * <p>回来的东西一律计数，不做归类：重点是速率，
     * 在这里被拒绝才是成功的结果。
     */
    private static void floodOne(HttpClient client, String baseUrl, String scheduleId,
                                 String tierId, String token, AtomicInteger refused,
                                 AtomicInteger bought, AtomicInteger soldOut,
                                 AtomicInteger errored, AtomicLong buyNanos) {
        String body = "{\"scheduleId\":\"" + scheduleId + "\",\"tierId\":\"" + tierId
                + "\",\"quantity\":1,\"adjacent\":true}";

        long t0 = System.nanoTime();
        String response = post(client, baseUrl + "/api/seat/assign?scheduleId=" + scheduleId,
                body, token);
        buyNanos.addAndGet(System.nanoTime() - t0);

        if (response == null) {
            errored.incrementAndGet();
        } else if (response.contains("\"success\":true")) {
            bought.incrementAndGet();
        } else if (response.contains("售罄") || response.contains("10007")) {
            soldOut.incrementAndGet();
        } else {
            refused.incrementAndGet();
        }
    }

    /** 把放行凭证花在选座上。 */
    private static void buy(HttpClient client, String baseUrl, String scheduleId, String tierId,
                            String token, String admission, AtomicInteger bought,
                            AtomicInteger refused, AtomicInteger soldOut, AtomicInteger errored,
                            AtomicLong buyNanos, Map<String, AtomicInteger> seatTally) {
        String body = "{\"scheduleId\":\"" + scheduleId + "\",\"tierId\":\"" + tierId
                + "\",\"quantity\":1,\"adjacent\":true"
                + (admission == null ? "" : ",\"queueToken\":\"" + admission + "\"") + "}";

        long t0 = System.nanoTime();
        String response = post(client, baseUrl + "/api/seat/assign?scheduleId=" + scheduleId,
                body, token);
        buyNanos.addAndGet(System.nanoTime() - t0);

        if (response == null) {
            errored.incrementAndGet();
            return;
        }
        if (response.contains("\"success\":true")) {
            bought.incrementAndGet();
            for (String index : seatIndexesOf(response)) {
                seatTally.computeIfAbsent(index, k -> new AtomicInteger()).incrementAndGet();
            }
        } else if (response.contains("售罄") || response.contains("10007")) {
            soldOut.incrementAndGet();
        } else if (response.contains("余票不足") || response.contains("连座")
                || response.contains("请先排队") || response.contains("排队资格")) {
            refused.incrementAndGet();
        } else {
            errored.incrementAndGet();
        }
    }

    /** 不用 JSON 解析器，直接从响应里抠出座位下标。 */
    private static List<String> seatIndexesOf(String body) {
        List<String> indexes = new ArrayList<>();
        int at = body.indexOf("\"seatIndexes\":[");
        if (at < 0) {
            return indexes;
        }
        int end = body.indexOf(']', at);
        for (String part : body.substring(at + 15, end).split(",")) {
            String trimmed = part.replace("\"", "").trim();
            if (!trimmed.isEmpty()) {
                indexes.add(trimmed);
            }
        }
        return indexes;
    }

    // ------------------------------------------------------------

    /**
     * 签一个网关能接受的 token。
     *
     * <p>手工实现而不是调项目里的 JwtUtil，这样它在一个裸 JDK 上、没有
     * classpath 也能跑。这里的 claim 和那个类产出的一模一样：sub 是座位账本
     * 要落库的那个用户 id，jti 之所以要有，是因为网关每个请求都会拿它去
     * 登出黑名单里查一次。
     */
    private static String mint(long userId) {
        long now = System.currentTimeMillis() / 1000;
        String header = "{\"alg\":\"HS256\"}";
        String payload = "{\"sub\":\"" + userId + "\",\"jti\":\""
                + UUID.randomUUID().toString().replace("-", "")
                + "\",\"phone\":\"1" + String.format("%010d", userId % 10_000_000_000L)
                + "\",\"role\":\"USER\",\"iss\":\"" + ISSUER + "\",\"iat\":" + now
                + ",\"exp\":" + (now + 7200) + "}";

        String signingInput = b64(header.getBytes(StandardCharsets.UTF_8)) + "."
                + b64(payload.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + b64(hmac(signingInput));
    }

    private static byte[] hmac(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("cannot sign", e);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // ------------------------------------------------------------

    private static String post(HttpClient client, String url, String body, String token) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token);
            if (body == null) {
                builder.POST(HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            }
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception e) {
            return null;
        }
    }

    private static String get(HttpClient client, String url, String token) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception e) {
            return null;
        }
    }


    /** 守护线程，这样 main 返回后 JVM 就退出，不会挂在那里。 */
    private static java.util.concurrent.ExecutorService daemonPool(int threads) {
        return Executors.newFixedThreadPool(Math.min(threads, 512), r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
    }

    private RushSaleTest() {
    }

    /** 留给那些只想拿一个 token、不想跑测试的调用方。 */
    static String tokenFor(long userId) {
        return mint(userId);
    }

    static Map<String, Integer> emptyTally() {
        return new HashMap<>();
    }
}
