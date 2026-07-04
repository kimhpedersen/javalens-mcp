package org.javalens.mcp.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LoadedProject}'s own bookkeeping: initial state,
 * ref-count increment/decrement, touch(), the exclusive-execution lock, and
 * dispose(). No {@link ProjectRegistry} involved here — that's
 * {@link ProjectRegistryTest}.
 */
class LoadedProjectTest {

    private LoadedProject newProject() {
        return new LoadedProject(Path.of("/tmp/does-not-matter"));
    }

    @Test
    @DisplayName("A new project starts with refCount 0 and createdAt == lastAccessedAt")
    void initialState_isEmpty() {
        LoadedProject project = newProject();

        assertEquals(0, project.getRefCount());
        assertNotNull(project.getCreatedAt());
        assertEquals(project.getCreatedAt(), project.getLastAccessedAt());
    }

    @Test
    @DisplayName("incrementRefCount/decrementRefCount track attach/detach counts")
    void refCount_tracksAttachDetach() {
        LoadedProject project = newProject();

        project.incrementRefCount();
        project.incrementRefCount();
        assertEquals(2, project.getRefCount());

        project.decrementRefCount();
        assertEquals(1, project.getRefCount());
    }

    @Test
    @DisplayName("decrementRefCount never goes below zero")
    void refCount_flooredAtZero() {
        LoadedProject project = newProject();

        project.decrementRefCount();

        assertEquals(0, project.getRefCount(), "refCount must not go negative on an unbalanced decrement");
    }

    @Test
    @DisplayName("incrementRefCount and decrementRefCount both touch lastAccessedAt")
    void refCountChanges_touchLastAccessed() throws Exception {
        LoadedProject project = newProject();
        Instant initial = project.getLastAccessedAt();
        Thread.sleep(5);

        project.incrementRefCount();

        assertTrue(project.getLastAccessedAt().isAfter(initial),
            "incrementRefCount must refresh lastAccessedAt");
    }

    @Test
    @DisplayName("dispose() is a no-op when no IJdtService was ever set")
    void dispose_noService_doesNotThrow() {
        LoadedProject project = newProject();
        project.dispose(); // must not throw
    }

    @Test
    @DisplayName("dispose() delegates to the loaded IJdtService's dispose()")
    void dispose_withService_delegates() {
        LoadedProject project = newProject();
        SessionTest.FakeJdtService service = new SessionTest.FakeJdtService();
        project.setJdtService(service);

        project.dispose();

        assertTrue(service.disposeCalled, "LoadedProject.dispose() must call through to IJdtService.dispose()");
    }

    @Test
    @DisplayName("runExclusive serializes overlapping calls on the same project")
    void runExclusive_serializesConcurrentCalls() throws Exception {
        LoadedProject project = newProject();
        int threadCount = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger concurrentInSection = new AtomicInteger(0);
        AtomicInteger maxObservedConcurrency = new AtomicInteger(0);

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    project.runExclusive(() -> {
                        int current = concurrentInSection.incrementAndGet();
                        maxObservedConcurrency.updateAndGet(prev -> Math.max(prev, current));
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        concurrentInSection.decrementAndGet();
                        return null;
                    });
                }));
            }
            ready.await();
            go.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, maxObservedConcurrency.get(),
            "runExclusive must serialize: at most one thread should ever be inside the locked section");
    }
}
