package com.maipiao.pay.service;

import static org.junit.jupiter.api.Assertions.*;

import com.maipiao.pay.entity.*;
import com.maipiao.pay.mapper.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;

/** 独立临时 MySQL：验证初始化、08 增量迁移、真实 mapper 和事务代理。 */
@EnabledIfSystemProperty(named = "trade.integration", matches = "true")
class TradeInfrastructureTest {
    static GenericContainer<?> mysql;
    static DriverManagerDataSource source;
    static SqlSessionTemplate sql;
    static DataSourceTransactionManager transactions;
    static PaymentMapper payments;
    static RefundMapper refunds;
    static PaymentIntentService paymentIntents;
    static RefundIntentService refundIntents;

    static Path root() {
        Path p = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(p.resolve("docs/sql/schema"))) p = p.getParent();
        return p;
    }

    static void execute(String script) throws Exception {
        script = script.replaceAll("(?m)^--.*$", "");
        try (var c = source.getConnection();
                var s = c.createStatement()) {
            for (String part : script.split(";")) if (!part.isBlank()) s.execute(part);
        }
    }

    @BeforeAll
    static void start() throws Exception {
        mysql =
                new GenericContainer<>("mysql:8.4")
                        .withEnv("MYSQL_ROOT_PASSWORD", "trade-test")
                        .withEnv("MYSQL_DATABASE", "maipiao_pay")
                        .withExposedPorts(3306);
        mysql.start();
        source =
                new DriverManagerDataSource(
                        "jdbc:mysql://"
                                + mysql.getHost()
                                + ":"
                                + mysql.getMappedPort(3306)
                                + "/maipiao_pay?allowPublicKeyRetrieval=true&useSSL=false",
                        "root",
                        "trade-test");
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(90))
                .ignoreExceptions()
                .until(
                        () -> {
                            try (var c = source.getConnection()) {
                                return c.isValid(2);
                            }
                        });
        String schema = Files.readString(root().resolve("docs/sql/schema/03_pay.sql"));
        execute(schema); // New installation must also execute without errors.
        // Restore pre-08 columns and indexes, then exercise the actual migration.
        execute(
                """
            ALTER TABLE t_pay_payment DROP COLUMN cashier_url, DROP INDEX idx_seat_sync,
              DROP COLUMN seat_synced, DROP INDEX uk_order_no, DROP INDEX idx_status_update,
              ADD INDEX idx_order_no(order_no);
            ALTER TABLE t_pay_refund DROP INDEX idx_seat_release, DROP COLUMN seat_released;
            CREATE DATABASE maipiao_order;
            CREATE TABLE maipiao_order.t_order_order(order_no VARCHAR(32) PRIMARY KEY,
              pay_amount DECIMAL(10,2),status TINYINT);
            INSERT INTO maipiao_order.t_order_order VALUES('backfill',100,5),('paid',100,2);
            """);
        execute(Files.readString(root().resolve("docs/sql/migrations/08_trade_reliability.sql")));
        var config =
                new Configuration(
                        new Environment(
                                "trade-test", new SpringManagedTransactionFactory(), source));
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(PaymentMapper.class);
        config.addMapper(RefundMapper.class);
        sql = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        payments = sql.getMapper(PaymentMapper.class);
        refunds = sql.getMapper(RefundMapper.class);
        transactions = new DataSourceTransactionManager(source);
        paymentIntents = proxied(new PaymentIntentService(payments));
        refundIntents = proxied(new RefundIntentService(refunds));
    }

    @SuppressWarnings("unchecked")
    static <T> T proxied(T target) {
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(
                new TransactionInterceptor(
                        transactions, new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }

    @AfterAll
    static void stop() {
        if (mysql != null) mysql.stop();
    }

    @Test
    void migrationBackfillsOnlyRefundingOrders() throws Exception {
        try (var c = source.getConnection();
                var s = c.createStatement();
                var r =
                        s.executeQuery(
                                "SELECT order_no,status FROM maipiao_order.t_order_refund_request")) {
            assertTrue(r.next());
            assertEquals("backfill", r.getString(1));
            assertEquals(0, r.getInt(2));
            assertFalse(r.next());
        }
    }

    @Test
    void concurrentPaymentCreationAndOuterRollbackKeepOneStableIdentity() throws Exception {
        var deadline = LocalDateTime.now().plusMinutes(2).withNano(0);
        try (var executor = Executors.newFixedThreadPool(16)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Payment>> results = new ArrayList<>();
            for (int i = 0; i < 16; i++)
                results.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    return paymentIntents.getOrCreate(
                                            "concurrent", 7L, BigDecimal.TEN, "MOCK", deadline);
                                }));
            start.countDown();
            Set<String> ids = new HashSet<>();
            for (var f : results) {
                var p = f.get(30, TimeUnit.SECONDS);
                ids.add(p.getPaymentNo());
                assertEquals(deadline, p.getExpireTime());
            }
            assertEquals(1, ids.size());
        }
        assertThrows(
                IllegalStateException.class,
                () ->
                        new TransactionTemplate(transactions)
                                .execute(
                                        status -> {
                                            paymentIntents.getOrCreate(
                                                    "outer-rollback",
                                                    7L,
                                                    BigDecimal.TEN,
                                                    "MOCK",
                                                    deadline);
                                            throw new IllegalStateException(
                                                    "simulate later channel timeout");
                                        }));
        assertNotNull(payments.selectLatestByOrderNo("outer-rollback"));
    }

    @Test
    void databaseCasRejectsWrongAmountAndSuccessCannotBeOverwritten() {
        var p =
                paymentIntents.getOrCreate(
                        "cas", 7L, BigDecimal.TEN, "MOCK", LocalDateTime.now().plusMinutes(2));
        assertEquals(1, payments.bindChannel(p.getPaymentNo(), "trade-cas", "http://cashier"));
        assertEquals(1, payments.casPayFailed(p.getPaymentNo()));
        assertEquals(
                0,
                payments.casPaySuccess(
                        p.getPaymentNo(), "trade-cas", BigDecimal.ONE, LocalDateTime.now()));
        assertEquals(
                1,
                payments.casPaySuccess(
                        p.getPaymentNo(), "trade-cas", BigDecimal.TEN, LocalDateTime.now()));
        assertEquals(0, payments.casPayFailed(p.getPaymentNo()));
        assertEquals(0, payments.closePayment(p.getPaymentNo()));
        assertTrue(
                payments.selectSeatSyncPending(LocalDateTime.now().plusMinutes(1)).stream()
                        .anyMatch(row -> row.getPaymentNo().equals(p.getPaymentNo())));
        payments.markSeatSynced(p.getPaymentNo());
        assertFalse(
                payments.selectSeatSyncPending(LocalDateTime.now().plusMinutes(1)).stream()
                        .anyMatch(row -> row.getPaymentNo().equals(p.getPaymentNo())));
    }

    @Test
    void refundIntentSurvivesRollbackAndHighRetryCountRemainsRecoverable() throws Exception {
        var p =
                paymentIntents.getOrCreate(
                        "refund", 7L, BigDecimal.TEN, "MOCK", LocalDateTime.now().plusMinutes(2));
        assertThrows(
                IllegalStateException.class,
                () ->
                        new TransactionTemplate(transactions)
                                .execute(
                                        status -> {
                                            refundIntents.createRefund(p, BigDecimal.TEN);
                                            throw new IllegalStateException("timeout");
                                        }));
        Refund refund = refunds.selectByPaymentNo(p.getPaymentNo());
        assertNotNull(refund);
        assertEquals(refund.getRefundNo(), refundIntents.createRefund(p, BigDecimal.TEN));
        try (var c = source.getConnection();
                var s = c.createStatement()) {
            s.executeUpdate(
                    "UPDATE t_pay_refund SET retry_count=99,next_retry_time=NOW(3)-INTERVAL 1 MINUTE");
        }
        assertTrue(
                refunds.selectRetryable(50).stream()
                        .anyMatch(r -> r.getRefundNo().equals(refund.getRefundNo())));
        assertEquals(
                1, refunds.recordFailure(refund.getRefundNo(), Refund.STATUS_REFUNDING, "unknown"));
        assertEquals(
                1,
                refunds.casRefundSuccess(refund.getRefundNo(), "channel-refund", BigDecimal.TEN));
        assertEquals(
                0,
                refunds.casRefundSuccess(refund.getRefundNo(), "channel-refund", BigDecimal.TEN));
        assertTrue(
                refunds.selectSeatReleasePending(LocalDateTime.now().plusMinutes(1)).stream()
                        .anyMatch(r -> r.getRefundNo().equals(refund.getRefundNo())));
        refunds.markSeatReleased(refund.getRefundNo());
        assertTrue(refunds.selectSeatReleasePending(LocalDateTime.now().plusMinutes(1)).isEmpty());
    }
}
