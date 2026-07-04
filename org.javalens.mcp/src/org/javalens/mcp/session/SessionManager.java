package org.javalens.mcp.session;

import org.javalens.mcp.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Owns the set of live MCP client sessions for a multiplexed (HTTP) server
 * process. Sessions are cheap: creating one does not load anything, it only
 * allocates handshake state. Attaching a session to a project (on
 * {@code load_project}) goes through the shared {@link ProjectRegistry}
 * passed to this manager, so sessions targeting the same canonical path
 * reuse the same loaded project instead of each loading their own copy.
 */
public class SessionManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);
    private static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration DEFAULT_SWEEP_INTERVAL = Duration.ofMinutes(1);

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final ToolRegistry toolRegistry;
    private final ProjectRegistry projectRegistry;
    private final Duration idleTimeout;
    private final ScheduledExecutorService sweeper;
    private final ScheduledFuture<?> sweepTask;

    public SessionManager(ToolRegistry toolRegistry, ProjectRegistry projectRegistry) {
        this(toolRegistry, projectRegistry, DEFAULT_IDLE_TIMEOUT, DEFAULT_SWEEP_INTERVAL);
    }

    /**
     * @param toolRegistry    shared registry passed to every session's protocol handler
     * @param projectRegistry shared registry sessions attach to/detach from on load_project
     * @param idleTimeout     how long a session may sit unaccessed before the sweep evicts it
     * @param sweepInterval   how often the background sweep checks for idle sessions
     */
    public SessionManager(ToolRegistry toolRegistry, ProjectRegistry projectRegistry,
                           Duration idleTimeout, Duration sweepInterval) {
        this.toolRegistry = toolRegistry;
        this.projectRegistry = projectRegistry;
        this.idleTimeout = idleTimeout;
        this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "javalens-session-sweeper");
            t.setDaemon(true);
            return t;
        });
        this.sweepTask = sweeper.scheduleWithFixedDelay(
            this::evictIdleSessions, sweepInterval.toMillis(), sweepInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Creates a new, empty session (not attached to any project yet) and registers it. */
    public Session create() {
        String id = UUID.randomUUID().toString();
        Session session = new Session(id, toolRegistry);
        sessions.put(id, session);
        log.info("Session created: {}", id);
        return session;
    }

    /** Looks up a session by id, touching its last-accessed time if found. */
    public Optional<Session> get(String id) {
        Session session = sessions.get(id);
        if (session != null) {
            session.touch();
        }
        return Optional.ofNullable(session);
    }

    /** Number of currently live sessions. */
    public int size() {
        return sessions.size();
    }

    /** Snapshot of currently live sessions. */
    public Collection<Session> all() {
        return List.copyOf(sessions.values());
    }

    /** Removes the session and detaches it from its project (if any). Idempotent. */
    public void terminate(String id) {
        Session session = sessions.remove(id);
        if (session != null) {
            projectRegistry.detach(session);
            log.info("Session terminated: {}", id);
        }
    }

    /** Evicts every session whose last access is older than the configured idle timeout. */
    void evictIdleSessions() {
        Instant cutoff = Instant.now().minus(idleTimeout);
        for (Session session : sessions.values()) {
            if (session.getLastAccessedAt().isBefore(cutoff)) {
                log.info("Evicting idle session {} (last accessed {})", session.getId(), session.getLastAccessedAt());
                terminate(session.getId());
            }
        }
    }

    /** Stops the idle-sweep background thread and terminates every live session. */
    @Override
    public void close() {
        sweepTask.cancel(true);
        sweeper.shutdownNow();
        for (String id : List.copyOf(sessions.keySet())) {
            terminate(id);
        }
    }
}
