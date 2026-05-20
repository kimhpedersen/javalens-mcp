package org.javalens.mcp.models;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for {@link ResponseMeta} and its Builder. The class is consumed by
 * every tool that returns paginated/truncated results, so its setters and the
 * lazy-initialization in {@code addSuggestedNextTool} are load-bearing.
 *
 * <p>Previously verified only indirectly via tool-level tests. Pin the contract
 * directly to catch regressions like a setter accidentally clobbering an
 * earlier field, or {@code addSuggestedNextTool}'s null-check getting inverted.
 */
class ResponseMetaTest {

    @Test
    @DisplayName("fresh builder().build() yields a ResponseMeta with all fields null")
    void freshBuilder_allNull() {
        ResponseMeta meta = ResponseMeta.builder().build();
        assertNull(meta.getTotalCount());
        assertNull(meta.getReturnedCount());
        assertNull(meta.getTruncated());
        assertNull(meta.getSuggestedNextTools());
        assertNull(meta.getVerbosity());
    }

    @Test
    @DisplayName("Builder.totalCount / returnedCount / truncated set their corresponding fields")
    void builder_setsCountFields() {
        ResponseMeta meta = ResponseMeta.builder()
            .totalCount(42)
            .returnedCount(7)
            .truncated(true)
            .build();
        assertEquals(42, meta.getTotalCount());
        assertEquals(7, meta.getReturnedCount());
        assertEquals(Boolean.TRUE, meta.getTruncated());
    }

    @Test
    @DisplayName("Builder.suggestedNextTools sets the list verbatim (no defensive copy)")
    void builder_suggestedNextTools_setsList() {
        List<String> tools = List.of("get_symbol_info", "find_references");
        ResponseMeta meta = ResponseMeta.builder()
            .suggestedNextTools(tools)
            .build();
        assertSame(tools, meta.getSuggestedNextTools(),
            "suggestedNextTools setter installs the list reference directly (no defensive copy)");
    }

    @Test
    @DisplayName("Builder.verbosity sets the field verbatim")
    void builder_setsVerbosity() {
        ResponseMeta meta = ResponseMeta.builder().verbosity("compact").build();
        assertEquals("compact", meta.getVerbosity());
    }

    @Test
    @DisplayName("addSuggestedNextTool initializes the list lazily on first call")
    void addSuggestedNextTool_lazyInit() {
        ResponseMeta meta = ResponseMeta.builder()
            .addSuggestedNextTool("first")
            .build();
        assertNotNull(meta.getSuggestedNextTools(),
            "List must be lazily initialized on first add; got null");
        assertEquals(List.of("first"), meta.getSuggestedNextTools());
    }

    @Test
    @DisplayName("addSuggestedNextTool appends to an existing list")
    void addSuggestedNextTool_appends() {
        ResponseMeta meta = ResponseMeta.builder()
            .addSuggestedNextTool("a")
            .addSuggestedNextTool("b")
            .addSuggestedNextTool("c")
            .build();
        assertEquals(List.of("a", "b", "c"), meta.getSuggestedNextTools(),
            "Multiple adds must preserve insertion order");
    }

    @Test
    @DisplayName("Builder is reusable: build() returns the SAME instance each call (documented limitation)")
    void builder_build_returnsSameInstance() {
        // Source: build() returns the builder's internal `meta` field directly,
        // not a copy. Two consecutive build() calls return the same instance.
        // Mutating the builder after build() would affect the already-returned meta.
        // Pin this so a future change to defensive copy would surface intentionally.
        ResponseMeta.Builder b = ResponseMeta.builder().totalCount(1);
        ResponseMeta first = b.build();
        b.totalCount(2);
        ResponseMeta second = b.build();
        assertSame(first, second,
            "Builder.build() returns the same internal ResponseMeta reference; "
                + "post-build mutations affect already-returned instances. "
                + "If this changes (defensive copy), update the contract.");
        assertEquals(2, second.getTotalCount(),
            "After the second mutation, both references see totalCount=2");
    }

    @Test
    @DisplayName("Boolean truncated = false is preserved (not lost as 'null/false' confusion)")
    void truncated_false_isPreserved() {
        // Pin that explicit Boolean.FALSE survives the setter — the getter returns
        // it as Boolean.FALSE, not unboxed-and-reboxed or null.
        ResponseMeta meta = ResponseMeta.builder().truncated(false).build();
        assertNotNull(meta.getTruncated(),
            "truncated=false must yield non-null Boolean (not null)");
        assertFalse(meta.getTruncated());
    }
}
