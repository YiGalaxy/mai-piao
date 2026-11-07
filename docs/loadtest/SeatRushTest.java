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
 * 并发锁座压测生成器。
 *
 * <p>这里要验证的，是整个选座设计赖以成立的那条断言：当大量用户同时来抢
 * 同一批座位时，每个座位只能归其中恰好一个人。所以光统计成功次数是不够的——
 * 真正要看的是成功数对比不重复座位数，任何一个座位被锁两次就是 bug。
 *
 * <p>每个请求抢的是一个随机座位而不是固定座位，这才让竞争显得真实：
 * 少数座位被大量请求砸，大部分座位只有零星几个请求，而仲裁者在这两种
 * 情况下都必须判对。
 *
 * <p>用法：
 * <pre>
 *   java SeatRushTest &lt;baseUrl&gt; &lt;token&gt; &lt;scheduleId&gt; &lt;seatCount&gt; &lt;threads&gt; &lt;requestsPerThread&gt;
 * </pre>
 *
 * <p>要跑就挑一场没有流量的场次。它锁掉的座位是真实的占用，不会释放，
 * 所以跑完之后这场次也就废了。
 */
public final class SeatRushTest {

    /** 被测接口。路由挪了地方就改这里。 */
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

        // 每个座位一个计数槽，这样"某个座位被锁了两次"是看得见的，
        // 而不只是被计个数。数组按座位数开，不是按请求数开。
        AtomicInteger[] perSeat = new AtomicInteger[seatCount];
        for (int i = 0; i < seatCount; i++) {
            perSeat[i] = new AtomicInteger();
        }

        // HTTP/1.1，连接池开得足够大：我们要测的瓶颈是网关，
        // 不是建连接。
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .executor(daemonPool(threads))
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
                                // 座位已被占——这是预期中的失败方。
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

        // 一个座位成功锁了两次就是超卖，这种事怎么解读数据都是不可接受的。
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


    /** 守护线程，这样 main 返回后 JVM 就退出，不会挂在那里。 */
    private static java.util.concurrent.ExecutorService daemonPool(int threads) {
        return Executors.newFixedThreadPool(Math.min(threads, 512), r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
    }

    private SeatRushTest() {
    }

    // 留作参考：开发期间用的百分位辅助方法。
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
