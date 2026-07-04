package org.javalens.mcp.session;

import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SessionManager}'s bookkeeping: create/get/touch,
 * termination (including detaching from {@link ProjectRegistry}), idle
 * eviction, and thread-safety of concurrent session creation.
 *
 * <p>Deliberately out of scope here (tracked as Phase 3 in multiplexing.md):
 * whether two sessions loading same-named-directory projects collide inside
 * {@code WorkspaceManager}. This suite only exercises the session map and
 * its interaction with {@link ProjectRegistry}'s attach/detach contract.
 */
class SessionManagerTest {

    /** Long enough that the background sweep never fires during a test unless told to. */
    private static final Duration NO_BACKGROUND_SWEEP = Duration.ofHours(1);

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private SessionManager manager;
    private ProjectRegistry projectRegistry;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
        if (projectRegistry != null) {
            projectRegistry.close();
        }
    }

    private SessionManager newManager(Duration idleTimeout) {
        projectRegistry = new ProjectRegistry(NO_BACKGROUND_SWEEP, NO_BACKGROUND_SWEEP);
        manager = new SessionManager(new ToolRegistry(), projectRegistry, idleTimeout, NO_BACKGROUND_SWEEP);
        return manager;
    }

    @Test
    @DisplayName("create() returns distinct, registered sessions")
    void create_returnsDistinctSessions() {
        SessionManager mgr = newManager(NO_BACKGROUND_SWEEP);

        Session a = mgr.create();
        Session b = mgr.create();

        assertNotEquals(a.getId(), b.getId());
        assertEquals(2, mgr.size());
    }

    @Test
    @DisplayName("get() finds a registered session by id")
    void get_knownId_returnsSession() {
        SessionManager mgr = newManager(NO_BACKGROUND_SWEEP);
        Session created = mgr.create();

        Optional<Session> found = mgr.get(created.getId());

        assertTrue(found.isPresent());
        assertEquals(created.getId(), found.get().getId());
    }

    @Test
    @DisplayName("get() returns empty for an unknown id")
    void get_unknownId_returnsEmpty() {
        SessionManager mgr = newManager(NO_BACKGROUND_SWEEP);

        assertTrue(mgr.get("does-not-exist").isEmpty());
    }

    @Test
    @DisplayName("get() touches lastAccessedAt so the session isn't evicted as idle")
    void get_touchesLastAccessed() throws Exception {
        SessionManager mgr = newManager(NO_BACKGROUND_SWEEP);
        Session created = mgr.create();
        Instant afterCreate = created.getLastAccessedAt();

        Thread.sleep(5);
        mgr.get(created.getId());

        assertTrue(created.getLastAccessedAt().isAfter(afterCreate),
            "get() must refresh lastAccessedAt");
    }

    @Test
    @DisplayName("terminate() removes the session and detaches it from its project")
    void terminate_removesAndDetaches() {
        SessionManager mgr = newManager(NO_BACKGROUND_SWEEP);
        Session session = mgr.create();
        LoadedProject project = projectRegistry.attach(session, helper.getFixturePath("simple-maven"));
        assertEquals(1, project.getRefCount());

        mgr.terminate(session.getId());

        assertEquals(0, mgr.size());
        assertTrue(mgr.get(session.getId()).isEmpty());
        assertEquals(0, project.getRefCount(), "terminate() must detach the session from its attached project");
    }

    @Test
    @DisplayName("terminate() on a session with nothing attached does not throw")
    void terminate_unattachedSession_doesNotThrow() {
        SessionManager mgr = newManager(NO_BACKGROUND_SWEEP);
        Session session = mgr.create();

        mgr.terminate(session.getId()); // must not throw

        assertEquals(0, mgr.size());
    }

    @Test
    @DisplayName("terminate() on an unknown id is a harmless no-op")
    void terminate_unknownId_doesNotThrow() {
        SessionManager mgr = newManager(NO_BACKGROUND_SWEEP);
        mgr.terminate("does-not-exist"); // must not throw
        assertEquals(0, mgr.size());
    }

    @Test
    @DisplayName("evictIdleSessions() terminates only sessions past the idle timeout")
    void evictIdleSessions_terminatesOnlyStaleSessions() throws Exception {
        SessionManager mgr = newManager(Duration.ofMinutes(1));
        Session stale = mgr.create();
        Session fresh = mgr.create();
        backdateLastAccessed(stale, Instant.now().minus(Duration.ofHours(1)));

        mgr.evictIdleSessions();

        assertTrue(mgr.get(stale.getId()).isEmpty(), "Session idle past the timeout must be evicted");
        assertTrue(mgr.get(fresh.getId()).isPresent(), "Recently accessed session must survive the sweep");
    }

    @Test
    @DisplayName("close() stops the sweeper and terminates every remaining session")
    void close_terminatesAllSessions() {
        SessionManager mgr = newManager(NO_BACKGROUND_SWEEP);
        Session session = mgr.create();
        LoadedProject project = projectRegistry.attach(session, helper.getFixturePath("simple-maven"));

        mgr.close();

        assertEquals(0, mgr.size());
        assertEquals(0, project.getRefCount(), "close() must detach every session's project");
    }

    @Test
    @DisplayName("Concurrent create() calls never collide or get lost")
    void create_concurrent_allUnique() throws Exception {
        SessionManager mgr = newManager(NO_BACKGROUND_SWEEP);
        int threadCount = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        Set<String> ids = ConcurrentHashMap.newKeySet();

        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    ids.add(mgr.create().getId());
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

        assertEquals(threadCount, ids.size(), "Every concurrent create() must produce a unique id");
        assertEquals(threadCount, mgr.size(), "No session may be lost under concurrent creation");
    }

    /** Reflection is the only way in: Session has no public setter for lastAccessedAt by design. */
    private static void backdateLastAccessed(Session session, Instant when) throws Exception {
        Field field = Session.class.getDeclaredField("lastAccessedAt");
        field.setAccessible(true);
        field.set(session, when);
    }
}
