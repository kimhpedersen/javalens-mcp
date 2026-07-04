package org.javalens.mcp.session;

import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ProjectRegistry}: attach/detach bookkeeping, reuse of
 * an already-loaded (or loading) project by canonical path, concurrent
 * attach() calls to the same new path never producing two loads, and idle
 * eviction.
 *
 * <p>Deliberately out of scope here (tracked as Phase 3 in multiplexing.md):
 * two *different* canonical paths that share a directory basename can still
 * collide inside {@code WorkspaceManager}'s stale-project sweep. Tests below
 * that need two distinct paths use fixtures with different basenames
 * ("simple-maven" / "plain-java") specifically to avoid tripping that
 * pre-existing bug rather than exercising it.
 */
class ProjectRegistryTest {

    /** Long enough that the background sweep never fires during a test unless told to. */
    private static final Duration NO_BACKGROUND_SWEEP = Duration.ofHours(1);

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ProjectRegistry registry;

    @AfterEach
    void tearDown() {
        if (registry != null) {
            registry.close();
        }
    }

    private ProjectRegistry newRegistry(Duration idleTimeout) {
        registry = new ProjectRegistry(idleTimeout, NO_BACKGROUND_SWEEP);
        return registry;
    }

    private Session newSession() {
        return new Session(java.util.UUID.randomUUID().toString(), new ToolRegistry());
    }

    @Test
    @DisplayName("attach() to a new path creates a project and attaches the session")
    void attach_newPath_createsAndAttaches() {
        ProjectRegistry reg = newRegistry(NO_BACKGROUND_SWEEP);
        Session session = newSession();
        Path path = helper.getFixturePath("simple-maven");

        LoadedProject project = reg.attach(session, path);

        assertEquals(1, project.getRefCount());
        assertEquals(session.getAttachedProject(), project);
        assertEquals(1, reg.size());
    }

    @Test
    @DisplayName("attach() to the same path from two sessions reuses one LoadedProject")
    void attach_samePath_twoSessions_reusesOneProject() {
        ProjectRegistry reg = newRegistry(NO_BACKGROUND_SWEEP);
        Path path = helper.getFixturePath("simple-maven");
        Session a = newSession();
        Session b = newSession();

        LoadedProject viaA = reg.attach(a, path);
        LoadedProject viaB = reg.attach(b, path);

        assertSame(viaA, viaB, "Two sessions attaching to the same canonical path must share one LoadedProject");
        assertEquals(2, viaA.getRefCount());
        assertEquals(1, reg.size(), "Only one project should be tracked for one canonical path");
    }

    @Test
    @DisplayName("attach() to the path a session is already on is a no-op (no double increment)")
    void attach_samePathSameSession_isNoOp() {
        ProjectRegistry reg = newRegistry(NO_BACKGROUND_SWEEP);
        Path path = helper.getFixturePath("simple-maven");
        Session session = newSession();

        LoadedProject first = reg.attach(session, path);
        LoadedProject second = reg.attach(session, path);

        assertSame(first, second);
        assertEquals(1, first.getRefCount(), "Re-attaching to the same path must not double-count the ref");
    }

    @Test
    @DisplayName("attach() to a different path detaches the session from its old project")
    void attach_differentPath_detachesOld() {
        ProjectRegistry reg = newRegistry(NO_BACKGROUND_SWEEP);
        Session session = newSession();
        LoadedProject first = reg.attach(session, helper.getFixturePath("simple-maven"));

        LoadedProject second = reg.attach(session, helper.getFixturePath("plain-java"));

        assertNotSame(first, second);
        assertEquals(0, first.getRefCount(), "Old project must be released when the session moves on");
        assertEquals(1, second.getRefCount());
        assertEquals(second, session.getAttachedProject());
    }

    @Test
    @DisplayName("Two distinct canonical paths get two distinct, independently ref-counted projects")
    void attach_distinctPaths_createTwoProjects() {
        ProjectRegistry reg = newRegistry(NO_BACKGROUND_SWEEP);
        Session a = newSession();
        Session b = newSession();

        LoadedProject viaA = reg.attach(a, helper.getFixturePath("simple-maven"));
        LoadedProject viaB = reg.attach(b, helper.getFixturePath("plain-java"));

        assertNotSame(viaA, viaB);
        assertEquals(2, reg.size());
        assertEquals(1, viaA.getRefCount());
        assertEquals(1, viaB.getRefCount());
    }

    @Test
    @DisplayName("detach() decrements the ref count and clears the session's attachment")
    void detach_decrementsAndClears() {
        ProjectRegistry reg = newRegistry(NO_BACKGROUND_SWEEP);
        Session session = newSession();
        LoadedProject project = reg.attach(session, helper.getFixturePath("simple-maven"));

        reg.detach(session);

        assertEquals(0, project.getRefCount());
        assertNull(session.getAttachedProject());
    }

    @Test
    @DisplayName("detach() on a session with nothing attached is a harmless no-op")
    void detach_unattachedSession_doesNotThrow() {
        ProjectRegistry reg = newRegistry(NO_BACKGROUND_SWEEP);
        Session session = newSession();
        reg.detach(session); // must not throw
        assertNull(session.getAttachedProject());
    }

    @Test
    @DisplayName("Concurrent attach() calls to the same new path never produce two loads")
    void attach_concurrentSamePath_onlyOneProjectCreated() throws Exception {
        ProjectRegistry reg = newRegistry(NO_BACKGROUND_SWEEP);
        Path path = helper.getFixturePath("simple-maven");
        int sessionCount = 30;
        List<Session> sessionList = new java.util.ArrayList<>();
        for (int i = 0; i < sessionCount; i++) {
            sessionList.add(newSession());
        }

        ExecutorService pool = Executors.newFixedThreadPool(sessionCount);
        CountDownLatch ready = new CountDownLatch(sessionCount);
        CountDownLatch go = new CountDownLatch(1);
        Set<LoadedProject> seen = ConcurrentHashMap.newKeySet();

        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (Session session : sessionList) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    seen.add(reg.attach(session, path));
                }));
            }
            ready.await();
            go.countDown();
            for (Future<?> f : futures) {
                f.get(15, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, seen.size(), "Every concurrent attach() to the same path must return the same LoadedProject");
        assertEquals(1, reg.size());
        assertEquals(sessionCount, seen.iterator().next().getRefCount());
    }

    @Test
    @DisplayName("evictIdleProjects() evicts only unreferenced projects idle past the timeout")
    void evictIdleProjects_evictsOnlyIdleUnreferenced() throws Exception {
        ProjectRegistry reg = newRegistry(Duration.ofMinutes(1));
        Session idleSession = newSession();
        Session activeSession = newSession();
        LoadedProject idle = reg.attach(idleSession, helper.getFixturePath("simple-maven"));
        LoadedProject active = reg.attach(activeSession, helper.getFixturePath("plain-java"));
        reg.detach(idleSession); // refCount -> 0, eligible for eviction once idle
        backdateLastAccessed(idle, Instant.now().minus(Duration.ofHours(1)));

        reg.evictIdleProjects();

        assertEquals(1, reg.size(), "Only the idle, unreferenced project should be evicted");
        assertTrue(reg.all().contains(active), "The still-attached project must survive the sweep");
        assertTrue(reg.all().stream().noneMatch(p -> p == idle), "The idle, unreferenced project must be gone");
    }

    @Test
    @DisplayName("evictIdleProjects() never evicts a project with a positive ref count, no matter how idle")
    void evictIdleProjects_neverEvictsReferencedProject() throws Exception {
        ProjectRegistry reg = newRegistry(Duration.ofMinutes(1));
        Session session = newSession();
        LoadedProject project = reg.attach(session, helper.getFixturePath("simple-maven"));
        backdateLastAccessed(project, Instant.now().minus(Duration.ofHours(1)));

        reg.evictIdleProjects();

        assertEquals(1, reg.size(), "A project with an attached session must never be evicted");
    }

    @Test
    @DisplayName("close() stops the sweeper and disposes every remaining project")
    void close_disposesAllProjects() {
        // Local instance (not the `registry` field) so @AfterEach's tearDown doesn't double-close it.
        ProjectRegistry reg = new ProjectRegistry(NO_BACKGROUND_SWEEP, NO_BACKGROUND_SWEEP);
        Session session = newSession();
        reg.attach(session, helper.getFixturePath("simple-maven"));

        reg.close();

        assertEquals(0, reg.size());
    }

    /** Reflection is the only way in: LoadedProject has no public setter for lastAccessedAt by design. */
    private static void backdateLastAccessed(LoadedProject project, Instant when) throws Exception {
        Field field = LoadedProject.class.getDeclaredField("lastAccessedAt");
        field.setAccessible(true);
        field.set(project, when);
    }
}
