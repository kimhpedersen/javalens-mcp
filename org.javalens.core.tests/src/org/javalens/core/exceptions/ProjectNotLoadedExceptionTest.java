package org.javalens.core.exceptions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pins the three constructors of {@link ProjectNotLoadedException}. The default
 * constructor is the only one currently invoked in production
 * ({@code AbstractTool.getService()} throws via {@code new ProjectNotLoadedException()}),
 * so its canonical message is load-bearing for the AI consumer's "Call
 * load_project first" hint. The message-only and message+cause constructors
 * are public API and tested for completeness.
 */
class ProjectNotLoadedExceptionTest {

    @Test
    @DisplayName("default constructor carries the canonical 'call load_project first' message")
    void defaultConstructor_canonicalMessage() {
        ProjectNotLoadedException ex = new ProjectNotLoadedException();
        assertEquals("No project loaded. Call load_project first.", ex.getMessage(),
            "Default-constructor message is load-bearing; AI consumers may pattern-match on it");
        assertNull(ex.getCause(), "Default constructor must not set a cause");
    }

    @Test
    @DisplayName("message-only constructor preserves the message and leaves cause null")
    void messageConstructor_preservesMessage() {
        ProjectNotLoadedException ex = new ProjectNotLoadedException("custom");
        assertEquals("custom", ex.getMessage());
        assertNull(ex.getCause());
    }

    @Test
    @DisplayName("message+cause constructor preserves both")
    void messageAndCauseConstructor_preservesBoth() {
        Throwable cause = new IllegalStateException("root");
        ProjectNotLoadedException ex = new ProjectNotLoadedException("wrapped", cause);
        assertEquals("wrapped", ex.getMessage());
        assertSame(cause, ex.getCause(),
            "Cause must be the same instance passed to the constructor");
    }

    @Test
    @DisplayName("is a RuntimeException (unchecked) — callers don't need to declare or catch")
    void isRuntimeException() {
        assertNotNull(new ProjectNotLoadedException());
        // Compile-time type check: this assignment compiles only if PNLE extends RuntimeException.
        RuntimeException re = new ProjectNotLoadedException();
        assertNotNull(re);
    }
}
