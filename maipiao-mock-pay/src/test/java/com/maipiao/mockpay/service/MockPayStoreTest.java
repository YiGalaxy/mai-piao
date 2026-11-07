package com.maipiao.mockpay.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class MockPayStoreTest {
    @TempDir Path temp;
    MockPayStore store;
    LocalDateTime deadline = LocalDateTime.now().plusMinutes(5);

    MockPayStore open() throws Exception {
        var value = new MockPayStore(new ObjectMapper().findAndRegisterModules());
        ReflectionTestUtils.setField(value, "stateFile", temp.resolve("state.json").toString());
        value.load();
        return value;
    }

    @BeforeEach
    void setup() throws Exception {
        store = open();
    }

    MockPayStore.Session create() {
        return store.createOrGet("pay", "order", new BigDecimal("100.00"), deadline);
    }

    @Test
    void concurrentRetriesShareOneDurableTransaction() throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Future<String>>();
            for (int i = 0; i < 32; i++) tasks.add(pool.submit(() -> create().getChannelTradeNo()));
            var identities = new HashSet<String>();
            for (var task : tasks) identities.add(task.get(10, TimeUnit.SECONDS));
            assertEquals(1, identities.size());
            assertEquals(
                    identities.iterator().next(),
                    open().findByPaymentNo("pay").getChannelTradeNo());
        }
    }

    @Test
    void identityCannotChangeAmountOwnerOrDeadline() {
        create();
        assertThrows(
                IllegalArgumentException.class,
                () -> store.createOrGet("pay", "order", BigDecimal.ONE, deadline));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.createOrGet("pay", "other", new BigDecimal("100"), deadline));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        store.createOrGet(
                                "pay", "order", new BigDecimal("100"), deadline.plusSeconds(1)));
    }

    @Test
    void closeAndPayHaveOneOutcomeEvenWhenTheyRace() throws Exception {
        String trade = create().getChannelTradeNo();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var paid =
                    pool.submit(
                            () -> {
                                start.await();
                                return store.markSuccess(trade);
                            });
            var closed =
                    pool.submit(
                            () -> {
                                start.await();
                                return store.close("pay").getStatus();
                            });
            start.countDown();
            boolean accepted = paid.get(10, TimeUnit.SECONDS);
            closed.get(10, TimeUnit.SECONDS);
            String expected = accepted ? "SUCCESS" : "CLOSED";
            assertEquals(expected, open().findByPaymentNo("pay").getStatus());
            assertEquals(accepted, store.markSuccess(trade));
        }
    }

    @Test
    void successfulPaymentAndRefundSurviveRestartAndRefundCannotRepeat() throws Exception {
        String trade = create().getChannelTradeNo();
        assertTrue(store.markSuccess(trade));
        var first = store.refund("refund", "pay", trade, new BigDecimal("100"));
        store = open();
        assertEquals("SUCCESS", store.findByPaymentNo("pay").getStatus());
        assertEquals(
                first.getChannelRefundNo(),
                store.refund("refund", "pay", trade, new BigDecimal("100")).getChannelRefundNo());
        assertThrows(
                IllegalArgumentException.class,
                () -> store.refund("another", "pay", trade, new BigDecimal("100")));
        assertEquals(first.getChannelRefundNo(), open().findRefund("refund").getChannelRefundNo());
    }

    @Test
    void failedPersistenceStopsQueriesFromReportingUndurableSuccess() throws Exception {
        String trade = create().getChannelTradeNo();
        Path blocker = temp.resolve("not-a-directory");
        Files.writeString(blocker, "block");
        ReflectionTestUtils.setField(store, "stateFile", blocker.resolve("state.json").toString());
        assertThrows(IllegalStateException.class, () -> store.markSuccess(trade));
        assertThrows(IllegalStateException.class, () -> store.findByPaymentNo("pay"));
        assertEquals("PENDING", open().findByPaymentNo("pay").getStatus());
    }
}
