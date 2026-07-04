package org.javalens.mcp.session;

/**
 * Ambient "current session" for the thread executing an MCP request.
 *
 * <p>Stdio mode binds exactly one {@link Session} for the whole process
 * lifetime — there's only ever one client, one thread reading stdin. A
 * future HTTP transport would {@link #bind}/{@link #clear} per request on a
 * pooled thread instead. Either way, tool code never needs to know which
 * transport it's running under — it only ever asks {@link #current()}.
 */
public final class SessionContext {

    private static final ThreadLocal<Session> CURRENT = new ThreadLocal<>();

    private SessionContext() {
    }

    /** The session bound to the calling thread, or null if none is bound. */
    public static Session current() {
        return CURRENT.get();
    }

    /** Binds {@code session} as current for the calling thread. */
    public static void bind(Session session) {
        CURRENT.set(session);
    }

    /** Clears whatever session is bound to the calling thread. */
    public static void clear() {
        CURRENT.remove();
    }
}
