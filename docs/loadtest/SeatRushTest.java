import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Concurrent seat-lock load generator.
 *
 * <p>The claim under test is the one the whole seat design rests on: when many
 * users reach for the same seats at once, every seat goes to exactly one of
 * them. Counting successes is therefore not enough - the number that matters is
 * successes versus distinct seats, and any seat locked twice is the bug.
 *
 * <p>Each request asks for one random seat rather than a fixed one, which is
 * what makes the contention realistic: a few seats are hit by many requests,
 * most by few, and the arbiter has to be right in both cases.
 *
 * <p>Usage:
 * <pre>
 *   java SeatRushTest &lt;baseUrl&gt; &lt;token&gt; &lt;scheduleId&gt; &lt;seatCount&gt; &lt;threads&gt; &lt;requestsPerThread&gt;
 * </pre>
 *
 * <p>Run it against a screening with no traffic on it. The seats it locks are
 * real holds and are not released, so the screening is used up afterwards.
 */
public final class SeatRushTest {

    /** Endpoint under test. Change here if the route moves. */
    private static final String LOCK_PATH = "/api/seat/lock";

    public static void main(String[] args) throws Exception {
        if (args.length < 6) {
            System.err.println("usage: SeatRushTest <baseUrl> <token> <scheduleId> "
                    + "<seatCount> <threads> <requestsPerThread>");
            System.exit(2);
        }

        String baseUrl = args[0];
        String token = args[1];
        String scheduleId = args[2];
        int seatCount = Integer.parseInt(args[3]);
        int threads = Integer.parseInt(args[4]);
        int perThread = Integer.parseInt(args[5]);

        System.out.printf("场次=%s 座位数=%d 并发=%d 总请求=%d%n",
                scheduleId, seatCount, threads, threads * perThread);
        System.out.println("---");

        AtomicInteger locked = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicInteger otherError = new AtomicInteger();
        AtomicInteger transportError = new AtomicInteger();
        AtomicLong totalLatencyNanos = new AtomicLong();

        // One slot per seat, so a seat locked twice is visible rather than
        // merely counted. Sized by seat count, not by request count.
        AtomicInteger[] perSeat = new AtomicInteger[seatCount];
        for (int i = 0; i < seatCount; i++) {
            perSeat[i] = new AtomicInteger();
        }

        // HTTP/1.1 with a generous pool: the gateway is the bottleneck we want
        // to measure, not connection setup.
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newFixedThreadPool(Math.min(threads, 256)))
                .build();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        int seat = ThreadLocalRandom.current().nextInt(seatCount);
                        String body = "{\"scheduleId\":\"" + scheduleId
                                + "\",\"seatIndexes\":[" + seat
                                + "],\"seatLabels\":[\"seat" + seat + "\"]}";

                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(baseUrl + LOCK_PATH))
                                .timeout(Duration.ofSeconds(20))
                                .header("Content-Type", "application/json")
                                .header("Authorization", "Bearer " + token)
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build();

                        long t0 = System.nanoTime();
                        try {
                            HttpResponse<String> response =
                                    client.send(request, HttpResponse.BodyHandlers.ofString());
                            long elapsed = System.nanoTime() - t0;
                            totalLatencyNanos.addAndGet(elapsed);

                            String text = response.body();
                            if (text.contains("\"success\":true")) {
                                locked.incrementAndGet();
                                perSeat[seat].incrementAndGet();
                            } else if (text.contains("\"code\":10001")) {
                                // Seat already taken - the expected loser.
                                conflict.incrementAndGet();
                            } else {
                                otherError.incrementAndGet();
                                if (otherError.get() <= 3) {
                                    System.out.println("  非预期响应: " + trim(text));
                                }
                            }
                        } catch (Exception e) {
                            transportError.incrementAndGet();
                        }
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        long wallStart = System.nanoTime();
        start.countDown();
        done.await();
        long wallNanos = System.nanoTime() - wallStart;
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        // A seat that succeeded twice is an oversell, and there is no reading
        // of the numbers under which that is acceptable.
        int doubled = 0;
        int maxPerSeat = 0;
        for (AtomicInteger count : perSeat) {
            int v = count.get();
            if (v > 1) {
                doubled++;
            }
            maxPerSeat = Math.max(maxPerSeat, v);
        }

        int total = threads * perThread;
        double seconds = wallNanos / 1_000_000_000.0;
        double avgMs = totalLatencyNanos.get() / 1_000_000.0 / Math.max(1, locked.get() + conflict.get());

        System.out.println();
        System.out.println("成功锁座   : " + locked.get());
        System.out.println("座位冲突   : " + conflict.get());
        System.out.println("其它错误   : " + otherError.get());
        System.out.println("连接失败   : " + transportError.get());
        System.out.println("---");
        System.out.printf("总请求     : %d%n", total);
        System.out.printf("耗时       : %.2f s%n", seconds);
        System.out.printf("吞吐       : %.0f req/s%n", total / seconds);
        System.out.printf("平均延迟   : %.1f ms%n", avgMs);
        System.out.println("---");
        System.out.println("被重复锁定的座位数: " + doubled
                + (doubled == 0 ? "  <- 无超卖" : "  <- 超卖！"));
        System.out.println("单个座位最多被锁次数: " + maxPerSeat);
    }

    private static String trim(String s) {
        return s.length() > 160 ? s.substring(0, 160) + "..." : s;
    }

    private SeatRushTest() {
    }

    // Kept for reference: the percentile helper used during development.
    @SuppressWarnings("unused")
    private static long percentile(List<Long> sorted, double p) {
        List<Long> copy = new ArrayList<>(sorted);
        Collections.sort(copy);
        int index = (int) Math.ceil(p / 100.0 * copy.size()) - 1;
        return copy.get(Math.max(0, Math.min(copy.size() - 1, index)));
    }

    @SuppressWarnings("unused")
    private static List<Long> asList(long[] values) {
        List<Long> list = new ArrayList<>(values.length);
        Arrays.stream(values).forEach(list::add);
        return list;
    }
}
