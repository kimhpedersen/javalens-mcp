package org.javalens.mcp.fixtures;

import org.javalens.core.IJdtService;
import org.javalens.mcp.JavaLensApplication;
import org.javalens.mcp.session.ProjectRegistry;
import org.javalens.mcp.session.Session;
import org.javalens.mcp.session.SessionContext;
import org.javalens.mcp.session.SessionManager;
import org.javalens.mcp.tools.ToolRegistry;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reflectively drives {@link JavaLensApplication#registerTools()} — the real
 * production wiring — without going through {@code start()} (which would block
 * reading stdin), and binds a {@link SessionContext} session so the
 * {@code this::currentJdtService}-style delegations {@code registerTools()} wires
 * up resolve exactly like a real transport would, instead of reaching into a
 * {@code jdtService} field that no longer exists since project state moved to
 * {@link Session}/{@link ProjectRegistry} (see multiplexing.md).
 *
 * <p>One shared {@link ProjectRegistry}/{@link SessionManager} backs every call
 * so each test wiring a fresh registry doesn't also spin up its own idle-sweep
 * background thread; only the {@link Session} (and the {@link ToolRegistry}
 * populated by {@code registerTools()}) is fresh per call.
 */
public final class TestRegistryBuilder {

    private static final ProjectRegistry PROJECT_REGISTRY = new ProjectRegistry();
    private static final SessionManager SESSION_MANAGER =
        new SessionManager(new ToolRegistry(), PROJECT_REGISTRY);

    private TestRegistryBuilder() {
    }

    /**
     * Builds a fresh {@link ToolRegistry} from the real {@code registerTools()}
     * wiring and binds a new session as current for the calling thread. If
     * {@code service} is non-null the session is attached to it (as if
     * {@code load_project} had already succeeded); if null the session is left
     * unattached, matching a project that hasn't been loaded yet.
     *
     * <p>Suits the common case of one registry active per test class/method. A
     * caller juggling two registries on the same thread (e.g. a serviceless one
     * and a service-backed one, both alive across a class's {@code @BeforeAll})
     * should use {@link #build} instead and rebind explicitly before each use —
     * {@link SessionContext} is a single ThreadLocal, so whichever session was
     * bound most recently is the one every tool sees.
     */
    public static ToolRegistry buildRegistry(IJdtService service) {
        return build(service).registry();
    }

    /**
     * Same wiring as {@link #buildRegistry}, but also returns the {@link Built}
     * session so a caller holding onto multiple registries on one thread can
     * re-bind the right one immediately before exercising it.
     */
    public static Built build(IJdtService service) {
        try {
            JavaLensApplication app = new JavaLensApplication();

            ToolRegistry registry = new ToolRegistry();
            setField(app, "toolRegistry", registry);
            setField(app, "projectRegistry", PROJECT_REGISTRY);

            Method registerTools = JavaLensApplication.class.getDeclaredMethod("registerTools");
            registerTools.setAccessible(true);
            registerTools.invoke(app);

            Session session = SESSION_MANAGER.create();
            if (service != null) {
                PROJECT_REGISTRY.registerLoaded(session, service);
            }
            SessionContext.bind(session);

            return new Built(registry, session);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                "Failed to wire test registry through the real registerTools()", e);
        }
    }

    /** A registry paired with the session it was wired against, so it can be re-bound on demand. */
    public record Built(ToolRegistry registry, Session session) {
        /** Binds this registry's session as current for the calling thread. */
        public void bind() {
            SessionContext.bind(session);
        }
    }

    private static void setField(JavaLensApplication app, String name, Object value)
            throws ReflectiveOperationException {
        Field field = JavaLensApplication.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(app, value);
    }
}
