package org.javalens.mcp.session;

import org.javalens.core.IJdtService;
import org.javalens.mcp.ProjectLoadingState;
import org.javalens.mcp.protocol.McpProtocolHandler;
import org.javalens.mcp.tools.ToolRegistry;

import java.time.Instant;

/**
 * State for one MCP client session: its own protocol handshake, and a
 * reference to whichever {@link LoadedProject} it's currently attached to
 * (shared with every other session pointed at the same canonical path).
 *
 * <p>Instances are created and owned by {@link SessionManager}; attaching
 * and detaching a project is owned by {@link ProjectRegistry}. Nothing else
 * should construct one directly.
 */
public final class Session {

    private final String id;
    private final McpProtocolHandler protocolHandler;
    private final Instant createdAt;

    private volatile LoadedProject attachedProject;
    private volatile Instant lastAccessedAt;

    Session(String id, ToolRegistry toolRegistry) {
        this.id = id;
        this.protocolHandler = new McpProtocolHandler(toolRegistry);
        this.createdAt = Instant.now();
        this.lastAccessedAt = this.createdAt;
    }

    public String getId() {
        return id;
    }

    public McpProtocolHandler getProtocolHandler() {
        return protocolHandler;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastAccessedAt() {
        return lastAccessedAt;
    }

    /** Marks the session as accessed now. Called by {@link SessionManager#get} on lookup. */
    void touch() {
        this.lastAccessedAt = Instant.now();
    }

    /** The project this session is currently attached to, or null before the first load_project call. */
    public LoadedProject getAttachedProject() {
        return attachedProject;
    }

    /** Set by {@link ProjectRegistry#attach}/{@link ProjectRegistry#detach}; not for direct use. */
    void attachProject(LoadedProject project) {
        this.attachedProject = project;
    }

    /** The attached project's JDT service, or null if nothing is attached (or still loading). */
    public IJdtService getJdtService() {
        LoadedProject project = attachedProject;
        return project != null ? project.getJdtService() : null;
    }

    /** The attached project's loading state, or NOT_LOADED if nothing is attached. */
    public ProjectLoadingState getLoadingState() {
        LoadedProject project = attachedProject;
        return project != null ? project.getLoadingState() : ProjectLoadingState.NOT_LOADED;
    }

    /** The attached project's loading error, or null if none. */
    public String getLoadingError() {
        LoadedProject project = attachedProject;
        return project != null ? project.getLoadingError() : null;
    }
}
