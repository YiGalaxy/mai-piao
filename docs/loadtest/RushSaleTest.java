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
 * Rush-sale load generator: N buyers, one screening, a fixed number of seats.
 *
 * <p>Drives the whole path a real buyer takes - join the line, wait to be
 * admitted, spend the token on a seat - rather than hammering the seat
 * endpoint directly, because the interesting question is not how fast the
 * seat service is. It is whether the system sells exactly as many seats as it
 * has, under a crowd, with a queue in front of it.
 *
 * <p>Identities are minted here rather than registered. Registering ten
 * thousand accounts would mean ten thousand bcrypt hashes at ~100ms each,
 * which measures the password hasher and buries the thing under test. The
 * tokens are real HS256 tokens signed with the same secret the gateway
 * verifies against, so the path being exercised is the real one - only the
 * account creation is skipped. The user ids must already exist in the
 * database, because the seat ledger has a foreign key opinion about them.
 *
 * <pre>
 *   java RushSaleTest &lt;baseUrl&gt; &lt;scheduleId&gt; &lt;tierId&gt; &lt;seats&gt; \
 *        &lt;buyers&gt; &lt;threads&gt; &lt;firstUserId&gt; &lt;queue|direct&gt;
 * </pre>
 *
 * <p>{@code queue} drives join-and-wait; {@code direct} skips the line and
 * calls the seat endpoint, which exists to show what the line is protecting.
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
        boolean useQueue = "queue".equalsIgnoreCase(args[7]);

        System.out.printf("schedule=%s tiers=%s seats=%d buyers=%d threads=%d mode=%s%n",
                scheduleId, args[2], seats, buyers, threads, useQueue ? "queue" : "direct");
        System.out.println("---");

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .executor(daemonPool(threads))
                .build();

        // ---- tokens ----
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

        // Which seat each success took. A seat claimed twice is an oversell and
        // there is no reading of the numbers under which that is acceptable.
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
                    String admission = null;
                    if (useQueue) {
                        admission = joinAndWait(client, baseUrl, scheduleId, token, joined,
                                admitted, soldOut, errored, joinNanos);
                        if (admission == null) {
                            return;
                        }
                    }
                    // A band at random, so the whole venue fills instead of one
                    // band selling out and the rest of the test measuring how
                    // fast the system says no.
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
     * Joins the line and polls until admitted, sold out, or out of patience.
     *
     * @return the admission token, or null when the buyer never got in
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

        // Poll until the dispatcher reaches us. The deadline is generous
        // because a long line is the expected shape of the test, not a fault.
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
                // The admission has to be carried to the purchase, or the seat
                // service refuses it - which is the whole point of the token,
                // and a test that forgets it measures only the refusal path.
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

    /** Pulls a string field out of a response without a JSON parser. */
    private static String field(String body, String name) {
        int at = body.indexOf("\"" + name + "\":\"");
        if (at < 0) {
            return null;
        }
        int from = at + name.length() + 4;
        int to = body.indexOf('"', from);
        return to < 0 ? null : body.substring(from, to);
    }

    /** Spends the admission on seats. */
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

    /** Pulls the seat indexes out of the response without a JSON parser. */
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
     * Signs a token the gateway will accept.
     *
     * <p>Written by hand rather than by calling the project's JwtUtil, so this
     * runs from a bare JDK with no classpath. The claims are the ones that
     * class produces: subject is the user id the seat ledger will be written
     * against, and jti is present because the gateway looks it up in the
     * logout blacklist on every request.
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


    /** Daemon threads, so the JVM exits when main returns instead of hanging. */
    private static java.util.concurrent.ExecutorService daemonPool(int threads) {
        return Executors.newFixedThreadPool(Math.min(threads, 512), r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
    }

    private RushSaleTest() {
    }

    /** Kept for callers that want a token without running a test. */
    static String tokenFor(long userId) {
        return mint(userId);
    }

    static Map<String, Integer> emptyTally() {
        return new HashMap<>();
    }
}
