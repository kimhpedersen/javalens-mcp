package org.javalens.mcp.tools.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindCastsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindCastsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private FindCastsTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindCastsTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }
    @SuppressWarnings("unchecked")
    private List<?> getCasts(Map<String, Object> d) { return (List<?>) d.get("locations"); }

    @Test @DisplayName("finds casts to project type")
    void findsCastsToProjectType() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        List<?> casts = getCasts(data);
        assertFalse(casts.isEmpty(), "Calculator has known casts in fixtures");
        int totalCount = ((Number) data.get("totalCount")).intValue();
        assertEquals(casts.size(), totalCount,
            "totalCount must equal casts list size; got: total=" + totalCount + " size=" + casts.size());
        assertEquals("com.example.Calculator", data.get("typeName"));
    }

    @Test @DisplayName("respects maxResults")
    void respectsMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxResults", 1);
        // Calculator has exactly 1 cast, so maxResults=1 returns exactly 1.
        assertEquals(1, getCasts(getData(tool.execute(args))).size());
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
    @DisplayName("casts to Calculator: exactly 1 cast in SearchPatterns.performCasts")
    void calculator_findsExactCastCount() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        // SearchPatterns.performCasts has `(Calculator) obj` exactly once. No other casts to
        // Calculator anywhere in the fixture.
        assertEquals(1, ((Number) getData(r).get("totalCount")).intValue(),
            "Expected exactly 1 (Calculator) cast in fixtures; got: "
                + getData(r).get("totalCount") + " (" + getCasts(getData(r)) + ")");
    }

    // ========== Behavior-matrix coverage ==========

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castsOf(ToolResponse r) {
        return (List<Map<String, Object>>) getData(r).get("locations");
    }

    @Test
    @DisplayName("The single (Calculator) cast entry has exact filePath/line/column/length/context")
    void castEntry_includesFullLocation() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> casts = castsOf(r);
        assertEquals(1, casts.size(), "exactly one (Calculator) cast; got: " + casts);
        Map<String, Object> c = casts.get(0);
        // SearchPatterns.java:80 (0-based 79) `Calculator calc = (Calculator) obj;` — the
        // CAST type "Calculator" inside (Calculator) starts at 0-based column 31, length 10.
        // (line+column+length+context fully specify the location; offset is the absolute
        // char position, a redundant derivation of those, so it is not separately hard-pinned.)
        assertTrue(((String) c.get("filePath")).replace('\\', '/').endsWith("SearchPatterns.java"),
            "(Calculator) cast must come from SearchPatterns.java; got: " + c);
        assertEquals(79, ((Number) c.get("line")).intValue(), "cast 0-based line; got: " + c);
        assertEquals(31, ((Number) c.get("column")).intValue(), "cast type 0-based column; got: " + c);
        assertEquals(10, ((Number) c.get("length")).intValue(), "\"Calculator\".length(); got: " + c);
        assertEquals("Calculator calc = (Calculator) obj;", c.get("context"),
            "exact trimmed source line; got: " + c.get("context"));
    }

    @Test
    @DisplayName("Warning field present when casts list is non-empty")
    void warning_presentOnNonEmpty() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertFalse(castsOf(r).isEmpty(), "Precondition: Calculator has casts");
        assertNotNull(data.get("advice"),
            "advice must be present when casts exist; data keys: " + data.keySet());
    }

    @Test
    @DisplayName("Warning field absent and list empty for a type with no casts (isolation)")
    void warning_absentWhenEmpty_animalNeverCast() {
        // Animal is a regular class — never cast anywhere in the fixtures.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Animal");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "Animal type must resolve; got: " +
                (r.getError() != null ? r.getError().getMessage() : "n/a"));
        Map<String, Object> data = getData(r);
        assertEquals(0, castsOf(r).size(),
            "Animal is never cast; cast list must be empty; got: " + castsOf(r));
        assertNull(data.get("advice"),
            "advice must be absent when no casts exist; got: " + data.get("advice"));
    }

    @Test
    @DisplayName("CAST_TYPE_REFERENCE distinction: plain references and instanceof checks NOT counted")
    void castDistinction_excludesPlainAndInstanceof() {
        // Calculator appears as instanceof check (line 79) and method references throughout
        // the fixtures. Only the actual cast `(Calculator) obj` on line 80 should be counted.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        // Exactly 1 cast — proven by the existing exactCastCount test; here we strengthen
        // the assertion by checking the cast is on the cast line, not the instanceof line.
        for (Map<String, Object> c : castsOf(r)) {
            int line = ((Number) c.get("line")).intValue();
            // 0-based line of `(Calculator) obj;` is 79 (1-based line 80).
            assertEquals(79, line,
                "(Calculator) cast must be on line 79 (0-based) only; got: " + c);
        }
    }

    @Test
    @DisplayName("maxResults caps and sets meta.truncated=true")
    void maxResults_capsAndSetsTruncated() {
        // SearchPatterns.performCasts has 1 (String) cast; StringCasts.java adds 3 more.
        // Total = 4, well above any small cap. java.lang.String is project-scoped here
        // (project scope excludes JDK), so search returns only fixture occurrences.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "java.lang.String");
        args.put("maxResults", 2);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertEquals(2, castsOf(r).size(),
            "maxResults=2 must cap cast list to exactly 2; got: " + castsOf(r));
        org.javalens.mcp.models.ResponseMeta meta = r.getMeta();
        assertNotNull(meta);
        assertEquals(Boolean.TRUE, meta.getTruncated());
    }

    @Test
    @DisplayName("Large maxResults: meta.truncated=false")
    void maxResults_large_noTruncation() {
        // Calculator has exactly 1 cast across the fixtures. With maxResults far above
        // total, truncated must be false.
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
    void totalCasts_equalsListSize() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        int total = ((Number) getData(r).get("totalCount")).intValue();
        assertEquals(total, castsOf(r).size());
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: exactly one (Calculator) cast on line 79 of SearchPatterns")
    void envelope_calculatorCast_exactLocation() {
        ObjectNode args = envelope.args();
        args.put("typeName", "com.example.Calculator");
        args.put("maxResults", 100);
        JsonNode payload = envelope.assertEnvelopeFidelity("find_casts", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "find_casts failed through the envelope: " + payload);
        JsonNode data = payload.get("data");
        assertEquals(1, data.get("totalCount").asInt(), "exactly one (Calculator) cast through the envelope");
        JsonNode cast = data.get("locations").get(0);
        assertTrue(cast.get("filePath").asText().replace('\\', '/').endsWith("SearchPatterns.java"));
        assertEquals(79, cast.get("line").asInt(),
            "the cast's 0-based line must survive the envelope");
    }
}
