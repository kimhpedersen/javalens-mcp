package org.javalens.mcp.tools.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindInstanceofChecksTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindInstanceofChecksToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private FindInstanceofChecksTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindInstanceofChecksTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }
    @SuppressWarnings("unchecked")
    private List<?> getChecks(Map<String, Object> d) { return (List<?>) d.get("locations"); }

    @Test @DisplayName("finds instanceof checks")
    void findsInstanceofChecks() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        List<?> checks = getChecks(data);
        assertFalse(checks.isEmpty(), "Calculator has known instanceof checks in fixtures");
        assertEquals(checks.size(), ((Number) data.get("totalCount")).intValue(),
            "totalCount must equal locations list size; got: " + data);
        assertEquals("com.example.Calculator", data.get("typeName"));
    }

    @Test @DisplayName("respects maxResults")
    void respectsMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxResults", 1);
        // Calculator has exactly 2 instanceof checks; maxResults=1 caps to exactly 1.
        assertEquals(1, getChecks(getData(tool.execute(args))).size());
    }

    @Test @DisplayName("missing typeName is rejected with INVALID_PARAMETER")
    void requiresTypeName() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertFalse(r.isSuccess());
        assertEquals("INVALID_PARAMETER", r.getError().getCode());
        assertTrue(r.getError().getMessage().toLowerCase().contains("required"),
            "message must explain typeName is required; got: " + r.getError().getMessage());
    }

    @Test @DisplayName("unknown type is rejected with SYMBOL_NOT_FOUND naming the type")
    void handlesUnknownType() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.nonexistent.X");
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        assertEquals("SYMBOL_NOT_FOUND", r.getError().getCode());
        assertTrue(r.getError().getMessage().contains("com.nonexistent.X"),
            "message must name the unresolved type; got: " + r.getError().getMessage());
    }

    @Test @DisplayName("negative maxResults is rejected with INVALID_PARAMETER")
    void negativeMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxResults", -1);
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        assertEquals("INVALID_PARAMETER", r.getError().getCode());
        assertTrue(r.getError().getMessage().contains(">= 0"),
            "message must explain the bound; got: " + r.getError().getMessage());
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("instanceof checks for Calculator: 2 in fixtures (performCasts + checkTypes)")
    void calculator_findsExactInstanceofCount() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        // SearchPatterns has TWO `instanceof Calculator` checks: one in performCasts (line 79)
        // and one in checkTypes (line 100). No other instanceof Calculator anywhere.
        assertEquals(2, ((Number) getData(r).get("totalCount")).intValue(),
            "Expected exactly 2 instanceof Calculator checks; got: "
                + getData(r).get("totalCount") + " (" + getChecks(getData(r)) + ")");
    }

    // ========== Behavior-matrix coverage ==========

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> checksOf(ToolResponse r) {
        return (List<Map<String, Object>>) getData(r).get("locations");
    }

    @Test
    @DisplayName("Both instanceof Calculator entries have exact column/length/context on lines {78,99}")
    void checkEntry_includesFullLocation() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> checks = checksOf(r);
        assertEquals(2, checks.size(), "exactly two instanceof Calculator checks; got: " + checks);
        java.util.Set<Integer> lines = new java.util.TreeSet<>();
        for (Map<String, Object> c : checks) {
            lines.add(((Number) c.get("line")).intValue());
            // Both sites are `if (obj instanceof Calculator) {` at 8-space indent: the type
            // name "Calculator" starts at 0-based column 27, length 10.
            assertTrue(((String) c.get("filePath")).replace('\\', '/').endsWith("SearchPatterns.java"),
                "instanceof Calculator must come from SearchPatterns.java; got: " + c);
            assertEquals(27, ((Number) c.get("column")).intValue(), "type 0-based column; got: " + c);
            assertEquals(10, ((Number) c.get("length")).intValue(), "\"Calculator\".length(); got: " + c);
            assertEquals("if (obj instanceof Calculator) {", c.get("context"),
                "exact trimmed source line; got: " + c.get("context"));
        }
        assertEquals(java.util.Set.of(78, 99), lines, "the two 0-based lines; got: " + lines);
    }

    @Test
    @DisplayName("Suggestion field present when checks list is non-empty")
    void suggestion_presentOnNonEmpty() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertFalse(checksOf(r).isEmpty(), "Precondition: Calculator has instanceof checks");
        assertNotNull(data.get("advice"),
            "advice must be present when checks exist; data keys: " + data.keySet());
    }

    @Test
    @DisplayName("Suggestion absent and list empty for type with no instanceof checks (isolation)")
    void suggestion_absentWhenEmpty_animalNeverChecked() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Animal");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "Animal type must resolve; got: " +
                (r.getError() != null ? r.getError().getMessage() : "n/a"));
        Map<String, Object> data = getData(r);
        assertEquals(0, checksOf(r).size(),
            "Animal is never checked via instanceof; got: " + checksOf(r));
        assertNull(data.get("advice"),
            "advice must be absent when no checks exist; got: " + data.get("advice"));
    }

    @Test
    @DisplayName("INSTANCEOF_TYPE_REFERENCE distinction: casts and plain refs NOT counted")
    void instanceofDistinction_excludesCastAndPlain() {
        // Calculator has 1 cast and 2 instanceof. The instanceof tool returns ONLY the 2
        // instanceof matches — confirmed by exactInstanceofCount. Here strengthen via line
        // numbers: 0-based lines 78 (performCasts) and 99 (checkTypes).
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        java.util.Set<Integer> lines = new java.util.HashSet<>();
        for (Map<String, Object> c : checksOf(r)) {
            lines.add(((Number) c.get("line")).intValue());
        }
        assertEquals(java.util.Set.of(78, 99), lines,
            "instanceof Calculator must be on 0-based lines {78, 99}; got: " + lines);
    }

    @Test
    @DisplayName("maxResults caps and sets meta.truncated=true")
    void maxResults_capsAndSetsTruncated() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxResults", 1);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertEquals(1, checksOf(r).size(),
            "maxResults=1 must cap instanceof checks to exactly 1");
        org.javalens.mcp.models.ResponseMeta meta = r.getMeta();
        assertNotNull(meta);
        assertEquals(Boolean.TRUE, meta.getTruncated());
    }

    @Test
    @DisplayName("Large maxResults: meta.truncated=false")
    void maxResults_large_noTruncation() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxResults", 1000);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        org.javalens.mcp.models.ResponseMeta meta = r.getMeta();
        assertNotNull(meta);
        assertEquals(Boolean.FALSE, meta.getTruncated());
    }

    @Test
    @DisplayName("totalCount == locations.size()")
    void totalCount_equalsListSize() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        int total = ((Number) getData(r).get("totalCount")).intValue();
        assertEquals(total, checksOf(r).size());
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: exactly two instanceof Calculator checks on lines {78, 99}")
    void envelope_calculatorInstanceof_exactLines() {
        ObjectNode args = envelope.args();
        args.put("typeName", "com.example.Calculator");
        args.put("maxResults", 100);
        JsonNode payload = envelope.assertEnvelopeFidelity("find_instanceof_checks", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "find_instanceof_checks failed through the envelope: " + payload);
        JsonNode data = payload.get("data");
        assertEquals(2, data.get("totalCount").asInt(), "exactly two checks through the envelope");
        java.util.Set<Integer> lines = new java.util.TreeSet<>();
        for (JsonNode c : data.get("locations")) lines.add(c.get("line").asInt());
        assertEquals(java.util.Set.of(78, 99), lines,
            "the two instanceof 0-based lines must survive the envelope");
    }
}
