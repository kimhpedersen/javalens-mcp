package org.javalens.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lifecycle invariants for {@link JavaLensApplication}'s shutdown path.
 *
 * <p>The message loop is single-threaded — tool invocations are dispatched
 * sequentially from stdin. {@link JavaLensApplication#stop()} sets a volatile
 * {@code running} flag; the loop re-checks it at the top of every iteration.
 * A tool call already in progress when {@code stop()} fires completes
 * naturally and writes its response before the loop exits — there is no
 * mid-call cancellation surface, so half-written tool output is not a
 * reachable state.
 *
 * <p>JDT workspace metadata is persisted transactionally by Eclipse's
 * resource framework. JavaLens itself does not write workspace metadata
 * outside JDT's APIs, so the "half-written workspace metadata" concern in
 * the C2-2 plan item has no path to occur from JavaLens code.
 *
 * <p>Project/loading-state now lives on a {@code Session} attached to a
 * {@code LoadedProject} in the shared {@code ProjectRegistry} (see
 * multiplexing.md, Phase 2), not on this class — so those invariants are
 * covered by {@code SessionTest}/{@code SessionManagerTest}/
 * {@code ProjectRegistryTest} instead of here. This file only pins {@code
 * running}'s lifecycle, which stayed on {@code JavaLensApplication} itself.
 */
class JavaLensApplicationLifecycleTest {

    @Test
    @DisplayName("stop() sets the running flag to false")
    void stop_setsRunningFalse() throws Exception {
        JavaLensApplication app = new JavaLensApplication();
        assertTrue(readRunning(app),
            "running must be true on a freshly constructed application");

        app.stop();

        assertFalse(readRunning(app),
            "stop() must set running=false so the message loop exits at its next iteration boundary");
    }

    @Test
    @DisplayName("stop() is idempotent — second call does not throw or flip the flag back")
    void stop_isIdempotent() throws Exception {
        JavaLensApplication app = new JavaLensApplication();
        app.stop();
        app.stop(); // must not throw or change state
        assertFalse(readRunning(app),
            "running must stay false across repeated stop() calls");
    }

    @Test
    @DisplayName("Two application instances have independent running flags (no shared state)")
    void runningFlag_isInstanceScoped() throws Exception {
        JavaLensApplication a = new JavaLensApplication();
        JavaLensApplication b = new JavaLensApplication();

        a.stop();

        assertFalse(readRunning(a), "a.running must be false after a.stop()");
        assertTrue(readRunning(b),
            "b.running must remain true — running is per-instance, not static");
    }

    private static boolean readRunning(JavaLensApplication app) throws Exception {
        Field f = JavaLensApplication.class.getDeclaredField("running");
        f.setAccessible(true);
        return (boolean) f.get(app);
    }

    @Test
    @DisplayName("stop() called from another thread while the running flag is true takes effect promptly")
    void stop_fromConcurrentThread_takesEffect() throws Exception {
        // Pins the volatile-visibility contract: a thread reading `running` sees the
        // false written by another thread calling stop(). The message loop relies on
        // exactly this — the stdin-reading thread sees the flag change set by an OSGi
        // shutdown thread. Without volatile this would be a JMM bug; the test forces
        // the read in another thread and checks it observes the write.
        JavaLensApplication app = new JavaLensApplication();
        assertTrue(readRunning(app));

        java.util.concurrent.atomic.AtomicBoolean observed = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);

        Thread reader = new Thread(() -> {
            try {
                ready.countDown();
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
                while (System.nanoTime() < deadline) {
                    if (!readRunning(app)) {
                        observed.set(false);
                        done.countDown();
                        return;
                    }
                    Thread.onSpinWait();
                }
                done.countDown();
            } catch (Exception e) {
                done.countDown();
            }
        }, "running-flag-reader");
        reader.start();

        assertTrue(ready.await(1, java.util.concurrent.TimeUnit.SECONDS),
            "Reader thread must reach its spin loop within 1s");
        app.stop();
        assertTrue(done.await(2, java.util.concurrent.TimeUnit.SECONDS),
            "Reader must observe running=false within 2s of stop() being called");
        assertFalse(observed.get(),
            "Cross-thread volatile-visibility contract: reader thread must observe "
                + "running=false after stop() — got running=" + observed.get());
        reader.join(1000);
    }
}
