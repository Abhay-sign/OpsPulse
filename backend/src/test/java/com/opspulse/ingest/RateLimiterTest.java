package com.opspulse.ingest;

import com.opspulse.api.ApiTestSupport;
import com.opspulse.common.error.RateLimitedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest extends ApiTestSupport {

    @Autowired RateLimiter limiter;

    @Test
    void bucketEmptiesThenReportsHowLongToWait() {
        UUID key = UUID.randomUUID();
        for (int i = 0; i < 3; i++) assertThat(limiter.tryAcquire(key, 3)).isZero();
        long wait = limiter.tryAcquire(key, 3);
        // 3 tokens/minute refill one every 20s
        assertThat(wait).isBetween(15_000L, 20_000L);
    }

    @Test
    void bucketsArePerKey() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertThat(limiter.tryAcquire(a, 1)).isZero();
        assertThat(limiter.tryAcquire(a, 1)).isPositive();
        assertThat(limiter.tryAcquire(b, 1)).isZero();
    }

    @Test
    void concurrentCallersCannotOverspend() throws Exception {
        UUID key = UUID.randomUUID();
        AtomicInteger allowed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch go = new CountDownLatch(1);
        var futures = new java.util.ArrayList<Future<?>>();
        for (int i = 0; i < 50; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                if (limiter.tryAcquire(key, 10) == 0) allowed.incrementAndGet();
                return null;
            }));
        }
        go.countDown();
        for (var f : futures) f.get(10, TimeUnit.SECONDS);
        pool.shutdown();
        // the Lua script is atomic, so exactly the bucket size gets through
        assertThat(allowed.get()).isEqualTo(10);
    }

    @Test
    void exceptionCarriesARoundedUpRetryAfter() {
        assertThat(new RateLimitedException(1).retryAfterSeconds()).isEqualTo(1);
        assertThat(new RateLimitedException(19_001).retryAfterSeconds()).isEqualTo(20);
    }
}
