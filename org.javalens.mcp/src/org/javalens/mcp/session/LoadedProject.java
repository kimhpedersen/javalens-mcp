package org.javalens.mcp.session;

import org.javalens.core.IJdtService;
import org.javalens.mcp.ProjectLoadingState;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * A single loaded project, shared by every {@link Session} currently
 * attached to it. At most one instance exists per canonical project path at
 * a time; {@link ProjectRegistry} owns creation, attach/detach ref-counting,
 * and eviction — nothing else should construct or mutate one directly.
 */
public final class LoadedProject {

    private final Path canonicalPath;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicInteger refCount = new AtomicInteger(0);
    private final Instant createdAt;

    private volatile IJdtService jdtService;
    private volatile ProjectLoadingState loadingState = ProjectLoadingState.NOT_LOADED;
    private volatile String loadingError;
    private volatile Instant lastAccessedAt;

    LoadedProject(Path canonicalPath) {
        this.canonicalPath = canonicalPath;
        this.createdAt = Instant.now();
        this.lastAccessedAt = this.createdAt;
    }

    public Path getCanonicalPath() {
        return canonicalPath;
    }

    public IJdtService getJdtService() {
        return jdtService;
    }

    void setJdtService(IJdtService jdtService) {
        this.jdtService = jdtService;
    }

    public ProjectLoadingState getLoadingState() {
        return loadingState;
    }

    void setLoadingState(ProjectLoadingState loadingState) {
        this.loadingState = loadingState;
    }

    public String getLoadingError() {
        return loadingError;
    }

    void setLoadingError(String loadingError) {
        this.loadingError = loadingError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastAccessedAt() {
        return lastAccessedAt;
    }

    /** Number of sessions currently attached. Zero means eligible for idle eviction. */
    public int getRefCount() {
        return refCount.get();
    }

    void incrementRefCount() {
        refCount.incrementAndGet();
        touch();
    }

    void decrementRefCount() {
        refCount.updateAndGet(count -> Math.max(0, count - 1));
        touch();
    }

    void touch() {
        this.lastAccessedAt = Instant.now();
    }

    /**
     * Runs {@code action} while holding this project's exclusive lock, so
     * concurrent requests from different sessions attached to the same
     * project serialize (matters most for mutating operations: refactorings,
     * disk-sync repair, and reload). Concurrent read-only queries across
     * sessions on the same project is a documented future optimization, not
     * implemented here — see multiplexing.md.
     */
    public <T> T runExclusive(Supplier<T> action) {
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /** Releases the workspace project backing {@code jdtService}, if loaded. Idempotent. */
    void dispose() {
        IJdtService service = jdtService;
        if (service != null) {
            service.dispose();
        }
    }
}
