package org.javalens.mcp.session;

import org.javalens.core.IJdtService;
import org.javalens.mcp.ProjectLoadingState;
import org.javalens.mcp.tools.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link Session}'s own bookkeeping: initial (unattached)
 * state and delegation to whichever {@link LoadedProject} it's attached to.
 * Attach/detach lifecycle itself belongs to {@link ProjectRegistryTest};
 * project-level locking belongs to {@link LoadedProjectTest}.
 */
class SessionTest {

    private Session newSession() {
        return new Session("test-session", new ToolRegistry());
    }

    @Test
    @DisplayName("A new session starts unattached: no project, NOT_LOADED, no error")
    void initialState_isUnattached() {
        Session session = newSession();

        assertEquals("test-session", session.getId());
        assertNull(session.getAttachedProject());
        assertNull(session.getJdtService());
        assertEquals(ProjectLoadingState.NOT_LOADED, session.getLoadingState());
        assertNull(session.getLoadingError());
        assertNotNull(session.getProtocolHandler());
        assertNotNull(session.getCreatedAt());
        assertEquals(session.getCreatedAt(), session.getLastAccessedAt(),
            "lastAccessedAt should equal createdAt before any touch()");
    }

    @Test
    @DisplayName("Each session gets its own McpProtocolHandler instance")
    void eachSession_ownsDistinctProtocolHandler() {
        Session a = newSession();
        Session b = new Session("other", new ToolRegistry());

        assertNotNull(a.getProtocolHandler());
        assertNotNull(b.getProtocolHandler());
        assertNotSame(a.getProtocolHandler(), b.getProtocolHandler(),
            "Sessions must not share a protocol handler (handshake state is per-client)");
    }

    @Test
    @DisplayName("getJdtService/getLoadingState/getLoadingError delegate to the attached project")
    void attachedProject_delegatesState() {
        Session session = newSession();
        LoadedProject project = new LoadedProject(Path.of("/tmp/does-not-matter"));
        FakeJdtService service = new FakeJdtService();
        project.setJdtService(service);
        project.setLoadingState(ProjectLoadingState.LOADED);
        project.setLoadingError("irrelevant once loaded");

        session.attachProject(project);

        assertEquals(project, session.getAttachedProject());
        assertEquals(service, session.getJdtService());
        assertEquals(ProjectLoadingState.LOADED, session.getLoadingState());
        assertEquals("irrelevant once loaded", session.getLoadingError());
    }

    @Test
    @DisplayName("attachProject(null) returns the session to the unattached defaults")
    void attachProject_null_returnsToUnattached() {
        Session session = newSession();
        LoadedProject project = new LoadedProject(Path.of("/tmp/does-not-matter"));
        project.setLoadingState(ProjectLoadingState.LOADED);
        session.attachProject(project);

        session.attachProject(null);

        assertNull(session.getAttachedProject());
        assertNull(session.getJdtService());
        assertEquals(ProjectLoadingState.NOT_LOADED, session.getLoadingState());
        assertNull(session.getLoadingError());
    }

    /** Minimal IJdtService — no logic, just a distinct instance to assert identity against. */
    static class FakeJdtService implements IJdtService {
        boolean disposeCalled = false;

        @Override public void dispose() { disposeCalled = true; }
        @Override public org.javalens.core.IPathUtils getPathUtils() { return null; }
        @Override public java.nio.file.Path getProjectRoot() { return null; }
        @Override public int getTimeoutSeconds() { return 30; }
        @Override public <T> T executeWithTimeout(java.util.concurrent.Callable<T> op, String name) { return null; }
        @Override public org.javalens.core.search.SearchService getSearchService() { return null; }
        @Override public org.javalens.core.graph.ProjectGraphService getProjectGraphService() { return null; }
        @Override public java.util.List<java.nio.file.Path> ensureFresh() { return java.util.List.of(); }
        @Override public org.javalens.core.sync.DiskSyncMode getDiskSyncMode() { return org.javalens.core.sync.DiskSyncMode.STRICT; }
        @Override public org.eclipse.jdt.core.IJavaProject getJavaProject() { return null; }
        @Override public org.eclipse.jdt.core.ICompilationUnit getCompilationUnit(java.nio.file.Path p) { return null; }
        @Override public org.eclipse.jdt.core.IJavaElement getElementAtPosition(java.nio.file.Path p, int l, int c) { return null; }
        @Override public org.eclipse.jdt.core.IType getTypeAtPosition(java.nio.file.Path p, int l, int c) { return null; }
        @Override public org.eclipse.jdt.core.IType findType(String n) { return null; }
        @Override public String getContextLine(org.eclipse.jdt.core.ICompilationUnit cu, int o) { return ""; }
        @Override public int getOffset(org.eclipse.jdt.core.ICompilationUnit cu, int l, int c) { return 0; }
        @Override public int getLineNumber(org.eclipse.jdt.core.ICompilationUnit cu, int o) { return 0; }
        @Override public int getColumnNumber(org.eclipse.jdt.core.ICompilationUnit cu, int o) { return 0; }
        @Override public java.util.List<java.nio.file.Path> getAllJavaFiles() { return java.util.List.of(); }
    }
}
