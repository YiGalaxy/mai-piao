package com.maipiao.order.service;

import static org.junit.jupiter.api.Assertions.*;

import com.maipiao.order.entity.OrderReservation;
import com.maipiao.order.mapper.OrderReservationMapper;
import io.seata.core.context.RootContext;
import io.seata.core.model.GlobalStatus;
import io.seata.rm.datasource.DataSourceProxy;
import io.seata.tm.api.GlobalTransaction;
import java.net.ServerSocket;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;

/** 显式启用：mvn ... test -Dreservation.integration=true。使用独立临时容器，不连接开发库。 */
@EnabledIfSystemProperty(named = "reservation.integration", matches = "true")
class ReservationInfrastructureTest {
    static GenericContainer<?> mysql;
    static GenericContainer<?> redis;
    static GenericContainer<?> seata;
    static boolean clientsInitialized;
    static DriverManagerDataSource rawOrder;
    static DriverManagerDataSource rawEvent;
    static DataSourceProxy orderProxy;
    static DataSourceProxy eventProxy;
    static final Map<String, String> originalProperties = new HashMap<>();
    static SqlSessionFactory sql;
    static LettuceConnectionFactory lettuce;
    static StringRedisTemplate template;
    static DefaultRedisScript<List> lock;
    static DefaultRedisScript<List> allocate;
    static DefaultRedisScript<Long> release;

    static Path root() {
        Path path = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(path.resolve("maipiao-service/maipiao-seat")))
            path = path.getParent();
        return path;
    }

    @BeforeAll
    static void startInfrastructure() throws Exception {
        mysql =
                new GenericContainer<>("mysql:8.4")
                        .withEnv("MYSQL_ROOT_PASSWORD", "reservation-test")
                        .withEnv("MYSQL_DATABASE", "maipiao_order")
                        .withExposedPorts(3306);
        redis = new GenericContainer<>("redis:7.4").withExposedPorts(6379);
        mysql.start();
        redis.start();
        var dataSource =
                new DriverManagerDataSource(
                        "jdbc:mysql://"
                                + mysql.getHost()
                                + ":"
                                + mysql.getMappedPort(3306)
                                + "/maipiao_order?allowPublicKeyRetrieval=true&useSSL=false",
                        "root",
                        "reservation-test");
        // Port 可能先由初始化实例打开；等到正式实例能够执行 SQL。
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(90))
                .ignoreExceptions()
                .until(
                        () -> {
                            try (var c = dataSource.getConnection()) {
                                return c.isValid(2);
                            }
                        });
        String schema =
                Files.readString(root().resolve("docs/sql/schema/07_order_reservation.sql"));
        try (var c = dataSource.getConnection();
                var statement = c.createStatement()) {
            for (String part : schema.split(";")) if (!part.isBlank()) statement.execute(part);
        }
        String undo =
                Files.readString(root().resolve("docs/sql/schema/05_seata_undo_log.sql"))
                        .replaceAll("(?m)^--.*$", "");
        try (var c = dataSource.getConnection();
                var statement = c.createStatement()) {
            for (String part : undo.split(";")) if (!part.isBlank()) statement.execute(part);
            for (String db : List.of("maipiao_order", "maipiao_event")) {
                statement.execute(
                        "CREATE TABLE "
                                + db
                                + ".t_seata_probe(id BIGINT PRIMARY KEY, qty INT NOT NULL)");
                statement.execute("INSERT INTO " + db + ".t_seata_probe VALUES(1,10),(2,10)");
            }
        }
        int tcPort;
        try (ServerSocket port = new ServerSocket(0)) {
            tcPort = port.getLocalPort();
        }
        String serverConfig =
                Files.readString(root().resolve("docker/config/seata/application.yml"))
                        .replace("type: nacos", "type: file")
                        .replace("service-port: 8091", "service-port: " + tcPort)
                        .replace("enable-check-auth: true", "enable-check-auth: false");
        seata =
                new FixedTc(tcPort)
                        .withEnv("SEATA_IP", "127.0.0.1")
                        .withEnv("SEATA_PORT", "" + tcPort)
                        .withCopyToContainer(
                                Transferable.of(
                                        serverConfig.getBytes(
                                                java.nio.charset.StandardCharsets.UTF_8)),
                                "/seata-server/resources/application.yml");
        seata.start();
        property("config.type", "file");
        property("config.file.name", "file.conf");
        property("registry.type", "file");
        property("service.vgroupMapping.reservation-test", "default");
        property("service.default.grouplist", "127.0.0.1:" + tcPort);
        property("client.rm.lock.retryTimes", "2");
        property("client.rm.lock.retryInterval", "10");
        // 不启动完整应用，但仍注册 Starter 配置 SPI 所需的环境与默认配置类。
        org.apache.seata.spring.boot.autoconfigure.SeataCoreEnvironmentPostProcessor.init();
        new org.apache.seata.spring.boot.autoconfigure.SeataClientEnvironmentPostProcessor()
                .postProcessEnvironment(
                        new org.springframework.core.env.StandardEnvironment(),
                        new org.springframework.boot.SpringApplication(
                                ReservationInfrastructureTest.class));
        io.seata.tm.TMClient.init("reservation-test", "reservation-test");
        io.seata.rm.RMClient.init("reservation-test", "reservation-test");
        clientsInitialized = true;
        rawOrder = dataSource;
        rawEvent =
                new DriverManagerDataSource(
                        dataSource.getUrl().replace("/maipiao_order?", "/maipiao_event?"),
                        "root",
                        "reservation-test");
        orderProxy = new DataSourceProxy(rawOrder);
        eventProxy = new DataSourceProxy(rawEvent);
        Configuration config =
                new Configuration(
                        new Environment("integration", new JdbcTransactionFactory(), orderProxy));
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(OrderReservationMapper.class);
        sql = new SqlSessionFactoryBuilder().build(config);
        lettuce = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        lettuce.afterPropertiesSet();
        lettuce.start();
        template = new StringRedisTemplate(lettuce);
        Path scripts = root().resolve("maipiao-service/maipiao-seat/src/main/resources/lua");
        lock =
                new DefaultRedisScript<>(
                        Files.readString(scripts.resolve("seat_lock.lua")), List.class);
        allocate =
                new DefaultRedisScript<>(
                        Files.readString(scripts.resolve("seat_allocate.lua")), List.class);
        release =
                new DefaultRedisScript<>(
                        Files.readString(scripts.resolve("seat_release.lua")), Long.class);
    }

    @AfterAll
    static void stopInfrastructure() {
        RootContext.unbind();
        RootContext.unbindGlobalLockFlag();
        if (lettuce != null) lettuce.destroy();
        try {
            if (clientsInitialized) {
                org.apache.seata.core.rpc.netty.TmNettyRemotingClient.getInstance().destroy();
                org.apache.seata.core.rpc.netty.RmNettyRemotingClient.getInstance().destroy();
            }
        } finally {
            try {
                if (seata != null) seata.stop();
            } finally {
                try {
                    if (redis != null) redis.stop();
                } finally {
                    if (mysql != null) mysql.stop();
                    originalProperties.forEach(
                            (key, value) -> {
                                if (value == null) System.clearProperty(key);
                                else System.setProperty(key, value);
                            });
                }
            }
        }
    }

    static void property(String key, String value) {
        originalProperties.put(key, System.getProperty(key));
        System.setProperty(key, value);
    }

    /** XID 包含 TC 地址，保持容器内外端口一致，避免随机映射后回滚请求发往错误端口。 */
    static class FixedTc extends GenericContainer<FixedTc> {
        FixedTc(int port) {
            super("apache/seata-server:2.2.0");
            addFixedExposedPort(port, port);
            withExposedPorts(port);
            waitingFor(
                    org.testcontainers.containers.wait.strategy.Wait.forListeningPort()
                            .withStartupTimeout(java.time.Duration.ofSeconds(90)));
        }
    }

    void reserved(String token) {
        try (var session = sql.openSession(true)) {
            var mapper = session.getMapper(OrderReservationMapper.class);
            var row = new OrderReservation();
            row.setToken(token);
            row.setScheduleId(9L);
            row.setUserId(7L);
            row.setExpiresAt(System.currentTimeMillis() + 60000);
            mapper.prepare(row);
            assertEquals(1, mapper.publish(token, "[1,2]", "[\"A1\",\"A2\"]", 0));
        }
    }

    @Test
    void onlyOneOfThirtyTwoConcurrentCreatorsAcquiresExecution() throws Exception {
        reserved("concurrent");
        try (var executor = Executors.newFixedThreadPool(32)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                final int attempt = i;
                results.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    try (var session = sql.openSession(true)) {
                                        return session.getMapper(OrderReservationMapper.class)
                                                .claim(
                                                        "concurrent",
                                                        "xid-" + attempt,
                                                        "hash",
                                                        System.currentTimeMillis(),
                                                        false);
                                    }
                                }));
            }
            start.countDown();
            int winners = 0;
            for (var result : results) winners += result.get(30, TimeUnit.SECONDS);
            assertEquals(1, winners);
        }
        try (var session = sql.openSession(true)) {
            var mapper = session.getMapper(OrderReservationMapper.class);
            assertEquals("PROCESSING", mapper.find("concurrent").getState());
            assertEquals(0, mapper.finish("concurrent", "stale-xid", "RETRYABLE"));
            assertEquals(0, mapper.close("concurrent", Long.MAX_VALUE, true));
        }
    }

    @Test
    void closeAndClaimAreMutuallyExclusiveAcrossConnections() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int attempt = 0; attempt < 30; attempt++) {
                String token = "race-" + attempt;
                reserved(token);
                CountDownLatch start = new CountDownLatch(1);
                Future<Integer> claim =
                        executor.submit(
                                () -> {
                                    start.await();
                                    try (var session = sql.openSession(true)) {
                                        return session.getMapper(OrderReservationMapper.class)
                                                .claim(token, "x", "h", 0, false);
                                    }
                                });
                Future<Integer> close =
                        executor.submit(
                                () -> {
                                    start.await();
                                    try (var session = sql.openSession(true)) {
                                        return session.getMapper(OrderReservationMapper.class)
                                                .close(token, 0, true);
                                    }
                                });
                start.countDown();
                assertEquals(1, claim.get(10, TimeUnit.SECONDS) + close.get(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void oldAttemptCannotFinishRevisedRequestAndDeadlineCannotExtend() {
        reserved("revision");
        try (var session = sql.openSession(true)) {
            var mapper = session.getMapper(OrderReservationMapper.class);
            long deadline = mapper.find("revision").getExpiresAt();
            assertEquals(1, mapper.claim("revision", "x1", "h1", 0, false));
            assertEquals(1, mapper.finish("revision", "x1", "RETRYABLE"));
            assertEquals(0, mapper.claim("revision", "x2", "h2", 0, false));
            assertEquals(1, mapper.claim("revision", "x2", "h2", 0, true));
            assertEquals(0, mapper.finish("revision", "x1", "SUCCEEDED"));
            assertEquals(deadline, mapper.find("revision").getExpiresAt());
        }
    }

    List<String> keys(String prefix, String token) {
        return List.of(
                prefix + ":map",
                prefix + ":owner",
                prefix + ":delay",
                prefix + ":order:" + token,
                prefix + ":sold",
                prefix + ":active");
    }

    @Test
    void redisLockIsAllOrNothingAndOldReleaseCannotFreeNewOwner() {
        var old = keys("ownership", "old");
        assertEquals(
                1L, template.execute(lock, old, "old", future(), "20", "9", "1", "2").getFirst());
        assertEquals(-1L, template.getExpire(old.get(3))); // 索引不能早于未决事务消失
        var other = keys("ownership", "new");
        assertEquals(
                0L, template.execute(lock, other, "new", future(), "20", "9", "3", "2").getFirst());
        assertFalse(template.opsForValue().getBit(old.get(0), 3));
        // 旧版 TTL 已丢索引仍应能按 owner 回收。
        template.delete(old.get(3));
        assertEquals(2L, template.execute(release, old.subList(0, 5), "old", "0", "0"));
        assertEquals(
                1L, template.execute(lock, other, "new", future(), "20", "9", "1", "2").getFirst());
        assertEquals(0L, template.execute(release, old.subList(0, 5), "old", "0", "0"));
        assertEquals("new", template.opsForHash().get(old.get(1), "1"));
        assertTrue(template.opsForValue().getBit(old.get(0), 1));
    }

    @Test
    void paidSeatsAreProtectedAndExpiredScriptsCannotAcquire() {
        var keys = keys("paid", "paid-token");
        template.execute(lock, keys, "paid-token", future(), "20", "9", "1");
        template.opsForHash().put(keys.get(1), "1", "SOLD:paid-token");
        assertEquals(0L, template.execute(release, keys.subList(0, 5), "paid-token", "0", "0"));
        assertTrue(template.opsForValue().getBit(keys.get(0), 1));
        assertEquals(1L, template.execute(release, keys.subList(0, 5), "paid-token", "0", "1"));
        assertEquals(0L, template.execute(lock, keys, "late", "1", "20", "9", "2").getFirst());
        assertEquals(
                0L,
                template.execute(allocate, keys, "late", "1", "2", "20", "1", "9", "2", "4")
                        .getFirst());
    }

    @Test
    void actualSeataRollbackRestoresBothBranchesButRetainsCoordinationAndRedis() throws Exception {
        reserved("seata-rollback");
        var keys = keys("seata-rollback", "seata-rollback");
        template.execute(lock, keys, "seata-rollback", future(), "20", "9", "1", "2");
        GlobalTransaction tx = new CreationTransactions().begin();
        String xid = tx.getXid();
        try {
            RootContext.unbind();
            try (var session = sql.openSession(true)) {
                assertEquals(
                        1,
                        session.getMapper(OrderReservationMapper.class)
                                .claim("seata-rollback", xid, "hash", 0, false));
            }
            RootContext.bind(xid);
            updateProbe(orderProxy, 1);
            updateProbe(eventProxy, 1);
            RootContext.unbind();
            assertEquals(9, quantity(rawOrder, 1, false)); // AT 一阶段数据对普通查询可见
            RootContext.bindGlobalLockFlag();
            try {
                assertThrows(
                        java.sql.SQLException.class,
                        () -> quantity(orderProxy, 1, true),
                        "释放前的全局锁核验必须拒绝一阶段暂存状态");
            } finally {
                RootContext.unbindGlobalLockFlag();
            }
            RootContext.bind(xid);
            tx.rollback();
            assertEquals(GlobalStatus.Rollbacked, tx.getLocalStatus());
            RootContext.unbind();
            assertEquals(10, quantity(rawOrder, 1, false));
            assertEquals(10, quantity(rawEvent, 1, false));
            try (var session = sql.openSession(true)) {
                var mapper = session.getMapper(OrderReservationMapper.class);
                assertEquals("PROCESSING", mapper.find("seata-rollback").getState());
                assertEquals(1, mapper.finish("seata-rollback", xid, "RETRYABLE"));
            }
            assertTrue(template.opsForValue().getBit(keys.get(0), 1));
            RootContext.bindGlobalLockFlag();
            try {
                assertEquals(10, quantity(orderProxy, 1, true));
            } finally {
                RootContext.unbindGlobalLockFlag();
            }
        } finally {
            RootContext.unbind();
        }
    }

    @Test
    void actualSeataCommitFinalizesBranchesBeforeGlobalLockVerification() throws Exception {
        GlobalTransaction tx = new CreationTransactions().begin();
        try {
            updateProbe(orderProxy, 2);
            updateProbe(eventProxy, 2);
            tx.commit();
            assertTrue(
                    Set.of(GlobalStatus.Committed, GlobalStatus.AsyncCommitting)
                            .contains(tx.getLocalStatus()));
            RootContext.unbind();
            RootContext.bindGlobalLockFlag();
            try {
                assertEquals(9, quantity(orderProxy, 2, true));
                assertEquals(9, quantity(eventProxy, 2, true));
            } finally {
                RootContext.unbindGlobalLockFlag();
            }
        } finally {
            RootContext.unbind();
        }
    }

    @Test
    void concurrentQueueDispatchersShareCapacityAndTokenCanOnlyBeConsumedOnce() throws Exception {
        Path scripts = root().resolve("maipiao-service/maipiao-queue/src/main/resources/lua");
        var admit =
                new DefaultRedisScript<>(
                        Files.readString(scripts.resolve("queue_admit.lua")), List.class);
        var consume =
                new DefaultRedisScript<>(
                        Files.readString(scripts.resolve("queue_consume.lua")), Long.class);
        var keys = List.of("queue-test:wait", "queue-test:inflight", "queue-test:token:");
        for (int i = 0; i < 100; i++) template.opsForZSet().add(keys.get(0), "" + i, i);
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var first =
                    executor.submit(
                            () -> {
                                start.await();
                                return template.execute(
                                        admit, keys, "10", future(), "a", "60", "3600", "10");
                            });
            var second =
                    executor.submit(
                            () -> {
                                start.await();
                                return template.execute(
                                        admit, keys, "10", future(), "b", "60", "3600", "10");
                            });
            start.countDown();
            assertEquals(
                    20,
                    first.get(10, TimeUnit.SECONDS).size()
                            + second.get(10, TimeUnit.SECONDS).size());
            assertEquals(10L, template.opsForZSet().zCard(keys.get(1)));
            assertEquals(90L, template.opsForZSet().zCard(keys.get(0)));
            String token = template.opsForValue().get(keys.get(2) + "0");
            var consumeKeys = List.of(keys.get(2) + "0", keys.get(1));
            assertEquals(0L, template.execute(consume, consumeKeys, "forged", "0"));
            var c1 = executor.submit(() -> template.execute(consume, consumeKeys, token, "0"));
            var c2 = executor.submit(() -> template.execute(consume, consumeKeys, token, "0"));
            assertEquals(1L, c1.get(10, TimeUnit.SECONDS) + c2.get(10, TimeUnit.SECONDS));
            // A stale request cannot remove a replacement token.
            template.opsForValue().set(keys.get(2) + "0", "new-token");
            assertEquals(0L, template.execute(consume, consumeKeys, token, "0"));
            assertEquals("new-token", template.opsForValue().get(keys.get(2) + "0"));
        }
    }

    void updateProbe(javax.sql.DataSource source, int id) throws Exception {
        try (var c = source.getConnection();
                var stmt = c.prepareStatement("UPDATE t_seata_probe SET qty=qty-1 WHERE id=?")) {
            c.setAutoCommit(false);
            stmt.setInt(1, id);
            assertEquals(1, stmt.executeUpdate());
            c.commit();
        }
    }

    int quantity(javax.sql.DataSource source, int id, boolean locking)
            throws java.sql.SQLException {
        try (var c = source.getConnection();
                var stmt =
                        c.prepareStatement(
                                "SELECT qty FROM t_seata_probe WHERE id=?"
                                        + (locking ? " FOR UPDATE" : ""))) {
            c.setAutoCommit(false);
            stmt.setInt(1, id);
            try (var rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            } finally {
                c.rollback();
            }
        }
    }

    String future() {
        return Long.toString(System.currentTimeMillis() + 60000);
    }
}
