package org.javalens.mcp.fixtures;

import org.javalens.mcp.models.ErrorInfo;
import org.javalens.mcp.models.ToolResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Contract test for {@link SemanticAssertions} — the shared assertion-helper module used
 * across tool tests to enforce exact-content assertions against deterministic fixtures.
 * A regression here would cascade through dozens of seemingly-unrelated test failures
 * before being traced back to the helper.
 */
class SemanticAssertionsTest {

    @Test
    @DisplayName("assertSuccessData returns the data map for a success response")
    void assertSuccessData_success_returnsData() {
        Map<String, Object> payload = Map.of("foo", "bar", "n", 42);
        ToolResponse ok = ToolResponse.success(payload);
        Map<String, Object> data = SemanticAssertions.assertSuccessData(ok);
        assertSame(payload, data, "Helper must return the same instance, not a copy");
        assertEquals("bar", data.get("foo"));
        assertEquals(42, data.get("n"));
    }

    @Test
    @DisplayName("assertSuccessData throws AssertionError for an error response, with code in the message")
    void assertSuccessData_error_throws() {
        ToolResponse err = ToolResponse.error(new ErrorInfo("E_TEST", "test failure", "hint"));
        AssertionError thrown = assertThrows(AssertionError.class,
            () -> SemanticAssertions.assertSuccessData(err));
        // Failure message must include the error code so the test author can diagnose.
        assertTrue(thrown.getMessage().contains("E_TEST"),
            "Assertion failure must surface the error code; got: " + thrown.getMessage());
    }

    @Test
    @DisplayName("getList returns empty list for a missing key (defensive, not NPE)")
    void getList_missingKey_returnsEmptyList() {
        Map<String, Object> data = Map.of();
        List<Map<String, Object>> result = SemanticAssertions.getList(data, "nope");
        assertTrue(result.isEmpty(),
            "Missing key must yield empty list, not null and not NPE");
    }

    @Test
    @DisplayName("getList returns the underlying list for a present key")
    void getList_presentKey_returnsList() {
        List<Map<String, Object>> backing = List.of(Map.of("k", "v"));
        Map<String, Object> data = Map.of("items", backing);
        List<Map<String, Object>> result = SemanticAssertions.getList(data, "items");
        assertEquals(1, result.size());
        assertEquals("v", result.get(0).get("k"));
    }

    @Test
    @DisplayName("fieldSet collects field values across items into a set (dedupes)")
    void fieldSet_collectsAcrossItems_dedupes() {
        List<Map<String, Object>> items = List.of(
            Map.of("name", "A"),
            Map.of("name", "B"),
            Map.of("name", "A")  // duplicate
        );
        Set<String> names = SemanticAssertions.fieldSet(items, "name");
        assertEquals(Set.of("A", "B"), names,
            "fieldSet must dedupe values per Set semantics");
    }

    @Test
    @DisplayName("assertFieldSet succeeds when expected matches actual exactly")
    void assertFieldSet_matchingSet_succeeds() {
        List<Map<String, Object>> items = List.of(
            Map.of("kind", "class"),
            Map.of("kind", "interface")
        );
        SemanticAssertions.assertFieldSet(items, "kind", Set.of("class", "interface"));
    }

    @Test
    @DisplayName("assertFieldSet fails when sets differ, surfaces field name in message")
    void assertFieldSet_mismatchedSet_fails() {
        List<Map<String, Object>> items = List.of(Map.of("kind", "class"));
        AssertionError thrown = assertThrows(AssertionError.class,
            () -> SemanticAssertions.assertFieldSet(items, "kind", Set.of("class", "enum")));
        assertTrue(thrown.getMessage().contains("kind"),
            "Failure must name the offending field; got: " + thrown.getMessage());
    }

    @Test
    @DisplayName("assertQualifiedNames delegates to assertFieldSet on 'qualifiedName'")
    void assertQualifiedNames_delegates() {
        List<Map<String, Object>> items = List.of(
            Map.of("qualifiedName", "com.example.A"),
            Map.of("qualifiedName", "com.example.B")
        );
        SemanticAssertions.assertQualifiedNames(items,
            Set.of("com.example.A", "com.example.B"));

        AssertionError thrown = assertThrows(AssertionError.class,
            () -> SemanticAssertions.assertQualifiedNames(items, Set.of("com.example.X")));
        assertTrue(thrown.getMessage().contains("qualifiedName"),
            "Failure message must mention 'qualifiedName'; got: " + thrown.getMessage());
    }

    @Test
    @DisplayName("assertCount succeeds for matching int field; fails with field name in message")
    void assertCount_intField() {
        Map<String, Object> data = Map.of("totalCount", 5);
        SemanticAssertions.assertCount(data, "totalCount", 5);

        AssertionError thrown = assertThrows(AssertionError.class,
            () -> SemanticAssertions.assertCount(data, "totalCount", 99));
        assertTrue(thrown.getMessage().contains("totalCount"),
            "Failure must name the count field; got: " + thrown.getMessage());
    }

    @Test
    @DisplayName("data() of a ToolResponse with a non-Map payload throws ClassCastException (documented limitation)")
    void data_nonMapPayload_throws() {
        // The helper is generic-cast to Map<String, Object>. If a tool ever returns a
        // non-Map (e.g. a String or List), data() throws ClassCastException. This is
        // documented behavior — every JavaLens tool returns Map<String, Object>. Pin
        // the failure mode so a future test author understands why their non-Map test
        // crashes here.
        ToolResponse listPayload = ToolResponse.success(List.of("not", "a", "map"));
        try {
            SemanticAssertions.data(listPayload);
            // Note: the cast is unchecked (erased generics), so the cast itself does NOT
            // throw — it's lazy. The CCE fires only when a Map operation is invoked on
            // the result. Calling .get(...) forces materialization.
            Map<String, Object> data = SemanticAssertions.data(listPayload);
            data.get("anything");
            fail("Expected ClassCastException accessing non-Map payload as Map");
        } catch (ClassCastException expected) {
            // documented behavior
        }
    }
}
