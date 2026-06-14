package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetJavadocTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GetJavadocToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private GetJavadocTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;
    private String calculatorPath;
    private Path projectPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetJavadocTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("Calculator.add: symbol add, kind method, documented")
    void parsesJavadocComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);  // add method with javadoc
        args.put("column", 15);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("add", data.get("symbol"));
        assertEquals("method", data.get("kind"));
        assertEquals(true, data.get("hasDocumentation"));
    }

    @Test @DisplayName("undocumented field lastResult: kind field, hasDocumentation false")
    void handlesUndocumentedSymbol() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 6);    // 0-based; file line 7 is `private int lastResult;` (no Javadoc)
        args.put("column", 16); // on `lastResult` identifier

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("lastResult", data.get("symbol"));
        assertEquals("field", data.get("kind"));
        assertEquals(false, data.get("hasDocumentation"),
            "lastResult has no Javadoc; hasDocumentation must be false");
    }

    @Test @DisplayName("missing filePath/line each yield exact INVALID_PARAMETER")
    void requiresParameters() {
        ObjectNode noFile = objectMapper.createObjectNode();
        noFile.put("line", 14);
        noFile.put("column", 15);
        ToolResponse noFileResp = tool.execute(noFile);
        assertFalse(noFileResp.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, noFileResp.getError().getCode());
        assertEquals("Invalid parameter 'filePath': Required parameter missing",
            noFileResp.getError().getMessage());

        ObjectNode noLine = objectMapper.createObjectNode();
        noLine.put("filePath", calculatorPath);
        noLine.put("column", 15);
        ToolResponse noLineResp = tool.execute(noLine);
        assertFalse(noLineResp.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, noLineResp.getError().getCode());
        assertEquals("Invalid parameter 'line': Must be >= 0", noLineResp.getError().getMessage());
    }

    @Test @DisplayName("package-declaration position -> INVALID_PARAMETER (not a Javadoc-bearing member)")
    void handlesNonMemberPosition() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 0);  // Package declaration
        args.put("column", 0);

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, r.getError().getCode());
        assertEquals("Invalid parameter 'position': Symbol at position does not have Javadoc",
            r.getError().getMessage());
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("Calculator.add javadoc: summary mentions adds, @param documented, @return documented")
    void calculatorAdd_javadocContent() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        // Calculator.add() method declaration at 0-based line 13
        args.put("line", 13);
        args.put("column", 15);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("add", data.get("symbol"));
        assertEquals(true, data.get("hasDocumentation"));

        assertEquals("Adds two numbers.", data.get("summary"),
            "Calculator.add javadoc summary; got: " + getData(r));
    }

    // ========== Behavior-matrix coverage ==========

    private ObjectNode argsAtIdentifier(String filePath, String identifier) throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(filePath));
        int idx = source.indexOf(identifier);
        if (idx < 0) throw new AssertionError("`" + identifier + "` not in " + filePath);
        int line = (int) source.substring(0, idx).chars().filter(c -> c == '\n').count();
        int lineStart = source.lastIndexOf('\n', idx) + 1;
        int column = idx - lineStart;
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", filePath);
        args.put("line", line);
        args.put("column", column);
        return args;
    }

    @Test
    @DisplayName("Calculator.add: @param entries parsed with name and description for a, b")
    @SuppressWarnings("unchecked")
    void calculatorAdd_paramTagsParsed() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 13);
        args.put("column", 15);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        List<Map<String, String>> params = (List<Map<String, String>>) data.get("params");
        assertNotNull(params, "Calculator.add has @param a / @param b; got: " + data);
        assertEquals(2, params.size(),
            "Calculator.add javadoc has exactly two @param entries; got: " + params);

        java.util.Map<String, String> byName = new java.util.HashMap<>();
        for (Map<String, String> p : params) {
            byName.put(p.get("name"), p.get("description"));
        }
        assertEquals(java.util.Set.of("a", "b"), byName.keySet(),
            "@param names must be a and b; got: " + byName);
        assertEquals("first operand", byName.get("a"));
        assertEquals("second operand", byName.get("b"));
    }

    @Test
    @DisplayName("Calculator.add: @return text is 'the sum'")
    void calculatorAdd_returnTagParsed() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 13);
        args.put("column", 15);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertEquals("the sum", getData(r).get("returns"),
            "@return text must be parsed; got: " + getData(r));
    }

    @Test
    @DisplayName("richlyDocumentedMethod: all Javadoc tags parsed (@throws, @see, @since, @author, @version, @deprecated)")
    @SuppressWarnings("unchecked")
    void richlyDocumentedMethod_allTagsParsed() throws Exception {
        String tkf = projectPath.resolve("src/main/java/com/example/TypeKindsFixture.java").toString();
        ToolResponse r = tool.execute(argsAtIdentifier(tkf, "richlyDocumentedMethod"));
        assertTrue(r.isSuccess(),
            "Position on richlyDocumentedMethod must succeed; got: " +
                (r.getError() != null ? r.getError().getMessage() : "n/a"));
        Map<String, Object> data = getData(r);
        assertEquals("richlyDocumentedMethod", data.get("symbol"));
        assertEquals(true, data.get("hasDocumentation"));

        // @throws
        List<Map<String, String>> throwsList = (List<Map<String, String>>) data.get("throws");
        assertNotNull(throwsList, "@throws must be parsed; got: " + data);
        assertEquals(1, throwsList.size());
        assertEquals("java.lang.IllegalArgumentException", throwsList.get(0).get("type"));
        assertTrue(throwsList.get(0).get("description").contains("input is null"),
            "@throws description must be captured; got: " + throwsList);

        // @see
        List<String> see = (List<String>) data.get("see");
        assertNotNull(see, "@see must be parsed; got: " + data);
        assertTrue(see.stream().anyMatch(s -> s.contains("Calculator")),
            "@see must include Calculator reference; got: " + see);

        // @since
        assertEquals("1.0", data.get("since"),
            "@since must be parsed; got: " + data);

        // @author
        List<String> authors = (List<String>) data.get("authors");
        assertNotNull(authors);
        assertTrue(authors.contains("JavaLens fixture"),
            "@author must be parsed; got: " + authors);

        // @version
        assertEquals("2.5", data.get("version"));

        // @deprecated
        assertNotNull(data.get("deprecated"));
        assertTrue(data.get("deprecated").toString().contains("tag-parsing tests"),
            "@deprecated must be parsed; got: " + data.get("deprecated"));

        // @param x 2
        List<Map<String, String>> params = (List<Map<String, String>>) data.get("params");
        assertEquals(2, params.size());

        // @return
        assertNotNull(data.get("returns"));
    }

    @Test
    @DisplayName("Modern Javadoc tags @apiNote / @implSpec / @implNote and @exception are parsed")
    @SuppressWarnings("unchecked")
    void modernJavadocTags_parsed() throws Exception {
        // Contract: "Parsed Javadoc with summary, @param, @return, @throws, etc."
        // The "etc." extends to the JEP 285 standard tags (@apiNote, @implSpec,
        // @implNote) and to @exception (synonym for @throws). Without these,
        // documentation that uses modern Javadoc style is dropped.
        String tag = projectPath.resolve("src/main/java/com/example/JavadocTagFixture.java").toString();
        ToolResponse r = tool.execute(argsAtIdentifier(tag, "documentedMethod"));
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        assertNotNull(data.get("apiNote"),
            "@apiNote must be parsed; got: " + data);
        assertTrue(data.get("apiNote").toString().contains("never pass null"),
            "@apiNote text must be captured; got: " + data.get("apiNote"));

        assertNotNull(data.get("implSpec"),
            "@implSpec must be parsed; got: " + data);
        assertNotNull(data.get("implNote"),
            "@implNote must be parsed; got: " + data);

        // @exception is the older synonym for @throws — must merge into the
        // same `throws` list so consumers don't have to look in two places.
        List<Map<String, String>> throwsList = (List<Map<String, String>>) data.get("throws");
        assertNotNull(throwsList, "@exception must be merged into throws list; got: " + data);
        assertTrue(throwsList.stream().anyMatch(t -> "IOException".equals(t.get("type"))),
            "@exception IOException must appear in throws list; got: " + throwsList);
    }

    @Test
    @DisplayName("Method without Javadoc reports hasDocumentation=false and no parsed tags")
    void methodWithoutJavadoc_hasDocumentationFalse() throws Exception {
        String tkf = projectPath.resolve("src/main/java/com/example/TypeKindsFixture.java").toString();
        ToolResponse r = tool.execute(argsAtIdentifier(tkf, "noJavadocMethod"));
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("noJavadocMethod", data.get("symbol"));
        assertEquals(false, data.get("hasDocumentation"));
        assertNull(data.get("summary"));
        assertNull(data.get("params"));
        assertNull(data.get("returns"));
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: Calculator.add javadoc has @return 'the sum' and two @param entries")
    void envelope_calculatorAdd_javadocTags() {
        ObjectNode args = envelope.args();
        args.put("filePath", calculatorPath);
        args.put("line", 13);
        args.put("column", 15);
        JsonNode payload = envelope.assertEnvelopeFidelity("get_javadoc", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "get_javadoc failed through the envelope: " + payload);
        JsonNode data = payload.get("data");
        assertEquals("add", data.get("symbol").asText());
        assertTrue(data.get("hasDocumentation").asBoolean());
        assertEquals("the sum", data.get("returns").asText(),
            "@return text must survive the envelope");
        JsonNode params = data.get("params");
        assertEquals(2, params.size(), "exactly two @param entries through the envelope");
        java.util.Map<String, String> byName = new java.util.HashMap<>();
        for (JsonNode p : params) byName.put(p.get("name").asText(), p.get("description").asText());
        assertEquals("first operand", byName.get("a"));
        assertEquals("second operand", byName.get("b"));
    }
}
