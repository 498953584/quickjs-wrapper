package com.whl.quickjs.wrapper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.whl.quickjs.android.QuickJSLoader;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class QuickJSInterruptTest {
    @Before
    public void loadLibrary() {
        QuickJSLoader.init();
    }

    @Test(timeout = 15000)
    public void interruptCannotBeCaughtAndContextRemainsUsable() throws Exception {
        QuickJSContext context = QuickJSContext.create();
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<Throwable> requesterFailure = new AtomicReference<>();
        context.getGlobalObject().setProperty("entered", (JSCallFunction) args -> {
            entered.countDown();
            return null;
        });

        Thread requester = new Thread(() -> {
            try {
                if (!entered.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("JavaScript loop did not start");
                }
                context.requestInterrupt();
            } catch (Throwable failure) {
                requesterFailure.set(failure);
            }
        }, "quickjs-interrupt-requester");

        try {
            requester.start();
            try {
                context.evaluate("try { entered(); while (true) {} } " +
                        "catch (error) { 'caught'; }");
                fail("interrupt was caught or the loop returned");
            } catch (QuickJSException error) {
                assertTrue(error.isJSError());
                assertTrue(error.getMessage().contains("interrupted"));
            }

            requester.join(5000);
            assertFalse(requester.isAlive());
            assertNull(requesterFailure.get());
            assertEquals(Integer.valueOf(2), context.evaluate("1 + 1"));
        } finally {
            if (requester.isAlive()) {
                context.requestInterrupt();
                requester.join(1000);
            }
            context.close();
        }
    }

    @Test
    public void foreignDestroyIsRejectedAndPostCloseRequestIsNoOp() throws Exception {
        QuickJSContext closed = QuickJSContext.create();
        closed.close();

        AtomicReference<Throwable> requestFailure = new AtomicReference<>();
        Thread requester = new Thread(() -> {
            try {
                closed.requestInterrupt();
            } catch (Throwable failure) {
                requestFailure.set(failure);
            }
        });
        requester.start();
        requester.join(2000);
        assertFalse(requester.isAlive());
        assertNull(requestFailure.get());

        QuickJSContext owned = QuickJSContext.create();
        AtomicReference<Throwable> destroyFailure = new AtomicReference<>();
        Thread destroyer = new Thread(() -> {
            try {
                owned.destroy();
            } catch (Throwable failure) {
                destroyFailure.set(failure);
            }
        });
        destroyer.start();
        destroyer.join(2000);
        assertFalse(destroyer.isAlive());
        assertTrue(destroyFailure.get() instanceof QuickJSException);
        owned.close();
    }
}
