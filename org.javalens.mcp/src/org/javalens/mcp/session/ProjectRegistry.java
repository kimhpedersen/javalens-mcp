package org.javalens.mcp.session;

import org.javalens.core.IJdtService;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.ProjectLoadingState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Registry of loaded projects keyed by canonical path, shared by every
 * session attached to a given path. Loading the same path from two
 * different sessions reuses the same in-flight/loaded {@link LoadedProject}
 * instead of loading it twice — the whole point is that a new session
 * attaching to an already-loaded codebase doesn't pay for another reload.
 *
 * <p>A project with no attached sessions is only disposed after sitting
 * idle past the configured timeout (see {@link #evictIdleProjects()}), so a
 * session that reconnects, or a second session that attaches moments later,
 * doesn't force a reload it could have avoided.
 *
 * <p>Known gap (see multiplexing.md, Phase 3): {@code WorkspaceManager}'s
 * stale-project sweep can delete another *live* project's workspace entry
 * when two different canonical paths share the same directory basename.
 * This registry makes that collision easier to trigger than the old
 * one-project-per-process model did, because it's now normal for two
 * different paths to be loaded at once. Fix that before relying on this
 * with more than one distinct project path live at a time.
 */
public class ProjectRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ProjectRegistry.class);
    private static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration DEFAULT_SWEEP_INTERVAL = Duration.ofMinutes(1);

    private final ConcurrentHashMap<Path, LoadedProject> projects = new ConcurrentHashMap<>();
    private final Duration idleTimeout;
    private final ScheduledExecutorService sweeper;
    private final ScheduledFuture<?> sweepTask;

    public ProjectRegistry() {
        this(DEFAULT_IDLE_TIMEOUT, DEFAULT_SWEEP_INTERVAL);
    }

    public ProjectRegistry(Duration idleTimeout, Duration sweepInterval) {
        this.idleTimeout = idleTimeout;
        this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "javalens-project-sweeper");
            t.setDaemon(true);
            return t;
        });
        this.sweepTask = sweeper.scheduleWithFixedDelay(
            this::evictIdleProjects, sweepInterval.toMillis(), sweepInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Attaches {@code session} to the project at {@code projectRoot}, reusing
     * an already-loaded (or loading) project for that canonical path if one
     * exists, otherwise kicking off exactly one load. If the session was
     * already attached to a different project, that project is detached
     * first. Attaching to the path the session is already on is a no-op that
     * returns the existing project unchanged.
     *
     * <p>The find-or-create step and the ref-count increment happen inside a
     * single {@link ConcurrentHashMap#compute} call so they can't interleave
     * with {@link #evictIdleProjects()}'s check-and-remove for the same key —
     * both lock the same map bin, which is what actually prevents a session
     * from attaching to an entry the sweep is concurrently evicting.
     */
    public LoadedProject attach(Session session, Path projectRoot) {
        Path canonical = projectRoot.toAbsolutePath().normalize();
        LoadedProject previous = session.getAttachedProject();
        if (previous != null && previous.getCanonicalPath().equals(canonical)) {
            return previous;
        }

        LoadedProject next = projects.compute(canonical, (path, existing) -> {
            LoadedProject project = existing != null ? existing : startLoading(path);
            project.incrementRefCount();
            return project;
        });
        attachAndReleasePrevious(session, next);
        log.info("Session {} attached to project {}", session.getId(), canonical);
        return next;
    }

    /** Detaches {@code session} from whatever project it's attached to, if any. Idempotent. */
    public void detach(Session session) {
        LoadedProject project = session.getAttachedProject();
        if (project != null) {
            release(project);
            session.attachProject(null);
        }
    }

    /**
     * Registers a service the caller already loaded synchronously — e.g.
     * {@code load_project}'s tool contract returns full project stats in the
     * same response, so it cannot go through {@link #attach}'s
     * fire-and-continue-loading-in-the-background path. Attaches
     * {@code session} to the result and stores it in the registry keyed by
     * the service's project root, so a *different* session later attaching
     * to that same path (via {@link #attach}) reuses it instead of
     * reloading.
     *
     * <p>If another {@code LoadedProject} is already registered for that
     * exact path, this replaces it — the caller just did a fresh load, which
     * is more authoritative than whatever was there. Any other session still
     * attached to the superseded entry keeps working (it holds a direct
     * reference), it just won't be found by a future {@link #attach} call
     * for that path anymore. Unreachable in single-session stdio mode, since
     * there only one session ever attaches to any given path; revisit if a
     * multi-session HTTP transport needs sharper replace semantics.
     */
    public LoadedProject registerLoaded(Session session, IJdtService service) {
        Path canonical = service.getProjectRoot();
        LoadedProject project = new LoadedProject(canonical);
        project.setJdtService(service);
        project.setLoadingState(ProjectLoadingState.LOADED);
        project.incrementRefCount();

        projects.put(canonical, project);
        attachAndReleasePrevious(session, project);
        log.info("Session {} registered freshly-loaded project {}", session.getId(), canonical);
        return project;
    }

    /**
     * Attaches {@code session} to a synthetic project already known to have
     * failed, without ever attempting a load. Used when a caller validates
     * preconditions itself before deciding a load is even worth attempting
     * (e.g. {@code JAVA_PROJECT_PATH} pointing at a path that doesn't exist)
     * and needs that failure visible through the same
     * {@code getLoadingState()}/{@code getLoadingError()} surface a real
     * load would use. Not stored in the path-keyed map — a validation
     * failure isn't a loaded project, so there's nothing for a future
     * {@link #attach} call on that path to usefully reuse.
     */
    public LoadedProject registerFailed(Session session, Path projectRoot, String errorMessage) {
        Path canonical = projectRoot.toAbsolutePath().normalize();
        LoadedProject project = new LoadedProject(canonical);
        project.setLoadingState(ProjectLoadingState.FAILED);
        project.setLoadingError(errorMessage);
        project.incrementRefCount();

        attachAndReleasePrevious(session, project);
        log.info("Session {} registered failed project {}: {}", session.getId(), canonical, errorMessage);
        return project;
    }

    private void attachAndReleasePrevious(Session session, LoadedProject project) {
        LoadedProject previous = session.getAttachedProject();
        session.attachProject(project);
        if (previous != null) {
            release(previous);
        }
    }

    private void release(LoadedProject project) {
        project.decrementRefCount();
        log.debug("Released project {} (refCount now {})", project.getCanonicalPath(), project.getRefCount());
    }

    private LoadedProject startLoading(Path canonicalPath) {
        LoadedProject project = new LoadedProject(canonicalPath);
        project.setLoadingState(ProjectLoadingState.LOADING);
        CompletableFuture.runAsync(() -> loadAsync(project));
        log.info("Loading project (new): {}", canonicalPath);
        return project;
    }

    private void loadAsync(LoadedProject project) {
        try {
            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(project.getCanonicalPath());
            project.setJdtService(service);
            project.setLoadingState(ProjectLoadingState.LOADED);
            log.info("Project loaded: {}", project.getCanonicalPath());
        } catch (Exception e) {
            project.setLoadingState(ProjectLoadingState.FAILED);
            project.setLoadingError(e.getMessage());
            log.error("Failed to load project {}: {}", project.getCanonicalPath(), e.getMessage(), e);
        }
    }

    /** Number of distinct projects currently tracked (loaded, loading, or failed). */
    public int size() {
        return projects.size();
    }

    /** Snapshot of every tracked project. */
    public Collection<LoadedProject> all() {
        return List.copyOf(projects.values());
    }

    /**
     * Evicts every project with no attached sessions that has sat idle past
     * the timeout. The check and the removal happen inside one
     * {@link ConcurrentHashMap#computeIfPresent} call per key so a
     * concurrent {@link #attach} for that same path can't race it (see
     * {@link #attach} for the full argument).
     */
    void evictIdleProjects() {
        Instant cutoff = Instant.now().minus(idleTimeout);
        for (Path path : projects.keySet()) {
            projects.computeIfPresent(path, (key, project) -> {
                if (project.getRefCount() == 0 && project.getLastAccessedAt().isBefore(cutoff)) {
                    log.info("Evicting idle project {} (last accessed {})", key, project.getLastAccessedAt());
                    project.dispose();
                    return null;
                }
                return project;
            });
        }
    }

    /** Stops the idle-sweep background thread and disposes every tracked project. */
    @Override
    public void close() {
        sweepTask.cancel(true);
        sweeper.shutdownNow();
        for (Path path : List.copyOf(projects.keySet())) {
            LoadedProject project = projects.remove(path);
            if (project != null) {
                project.dispose();
            }
        }
    }
}
