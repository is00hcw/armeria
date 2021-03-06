/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.service.test.thrift.main.SleepService;
import com.linecorp.armeria.service.test.thrift.main.SleepService.AsyncIface;
import com.linecorp.armeria.test.AbstractServerTest;

public class GracefulShutdownIntegrationTest extends AbstractServerTest {

    private long baselineNanos;

    @Override
    protected void configureServer(ServerBuilder sb) {
        sb.gracefulShutdownTimeout(1000L, 2000L);
        sb.defaultRequestTimeoutMillis(0); // Disable RequestTimeoutException.

        sb.serviceAt("/sleep", THttpService.of(
                (AsyncIface) (milliseconds, resultHandler) ->
                        RequestContext.current().eventLoop().schedule(
                                () -> resultHandler.onComplete(milliseconds), milliseconds, MILLISECONDS)));
    }

    private long baselineNanos() throws Exception {
        if (baselineNanos != 0) {
            return baselineNanos;
        }

        // Measure the baseline time taken for stopping the server without handling any requests.
        startServer();
        final long startTime = System.nanoTime();
        stopServer();
        final long stopTime = System.nanoTime();

        return baselineNanos = stopTime - startTime;
    }

    @Test(timeout = 20000L)
    public void testBaseline() throws Exception {
        final long baselineNanos = baselineNanos();

        // Measure the time taken for stopping the server after handling a single request.
        startServer();
        SleepService.Iface client = newClient();
        client.sleep(0);
        final long startTime = System.nanoTime();
        stopServer();
        final long stopTime = System.nanoTime();

        // .. which should be on par with the baseline.
        assertThat(stopTime - startTime).isBetween(baselineNanos - MILLISECONDS.toNanos(300),
                                                   baselineNanos + MILLISECONDS.toNanos(300));
    }

    @Test(timeout = 20000L)
    public void waitsForRequestToComplete() throws Exception {
        final long baselineNanos = baselineNanos();
        startServer();

        SleepService.Iface client = newClient();
        AtomicBoolean completed = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);
        CompletableFuture.runAsync(() -> {
            try {
                latch.countDown();
                client.sleep(500L);
                completed.set(true);
            } catch (Throwable t) {
                fail("Shouldn't happen: " + t);
            }
        });

        // Wait for the latch to make sure the request has been sent before shutting down.
        latch.await();

        final long startTime = System.nanoTime();
        stopServer();
        final long stopTime = System.nanoTime();
        assertTrue(completed.get());

        // Should take 500 more milliseconds than the baseline.
        assertThat(stopTime - startTime).isBetween(baselineNanos + MILLISECONDS.toNanos(200),
                                                   baselineNanos + MILLISECONDS.toNanos(800));
    }

    @Test(timeout = 20000L)
    public void interruptsSlowRequests() throws Exception {
        final long baselineNanos = baselineNanos();
        startServer();

        SleepService.Iface client = newClient();
        AtomicBoolean completed = new AtomicBoolean(false);
        CountDownLatch latch1 = new CountDownLatch(1);
        CountDownLatch latch2 = new CountDownLatch(1);
        CompletableFuture.runAsync(() -> {
            try {
                latch1.countDown();
                client.sleep(30000L);
                completed.set(true);
            } catch (ClosedSessionException expected) {
                latch2.countDown();
            } catch (Throwable t) {
                fail("Shouldn't happen: " + t);
            }
        });

        // Wait for the latch to make sure the request has been sent before shutting down.
        latch1.await();

        final long startTime = System.nanoTime();
        stopServer();
        assertFalse(completed.get());

        // 'client.sleep()' must fail immediately when the server closes the connection.
        latch2.await();

        // Should take 1 more second than the baseline, because the long sleep will trigger shutdown timeout.
        final long stopTime = System.nanoTime();
        assertThat(stopTime - startTime).isBetween(baselineNanos + MILLISECONDS.toNanos(700),
                                                   baselineNanos + MILLISECONDS.toNanos(1300));
    }

    @Test(timeout = 20000)
    public void testHardTimeout() throws Exception {
        final long baselineNanos = baselineNanos();
        startServer();

        // Keep sending a request after shutdown starts so that the hard limit is reached.
        SleepService.Iface client = newClient();
        final CompletableFuture<Long> stopFuture = CompletableFuture.supplyAsync(() -> {
            final long startTime = System.nanoTime();
            AbstractServerTest.stopServer();
            final long stopTime = System.nanoTime();
            return stopTime - startTime;
        });

        for (int i = 0; i < 50; i++) {
            try {
                client.sleep(100);
            } catch (Exception e) {
                // Server has been shut down
                break;
            }
        }

        // Should take 1 more second than the baseline, because the requests will extend the quiet period
        // until the shutdown timeout is triggered.
        assertThat(stopFuture.join()).isBetween(baselineNanos + MILLISECONDS.toNanos(700),
                                                baselineNanos + MILLISECONDS.toNanos(1300));
    }

    private static SleepService.Iface newClient() throws Exception {
        String uri = "tbinary+" + uri("/sleep");
        return Clients.newClient(uri, SleepService.Iface.class);
    }
}
