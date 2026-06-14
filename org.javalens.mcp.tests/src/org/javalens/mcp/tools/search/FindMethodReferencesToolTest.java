package org.javalens.mcp.tools.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindMethodReferencesTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindMethodReferencesToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private FindMethodReferencesTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;
    private String searchPatternsPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindMethodReferencesTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
        searchPatternsPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/SearchPatterns.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test
    @DisplayName("valid method position returns success with methodName, declaringType, methodReferences list")
    void validMethodPosition_returnsExpectedShape() {
        // Position on Calculator.add — a real method declaration.
        String calculatorPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Calculator.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);   // 1-based line 15: `public int add(int a, int b)`
        args.put("column", 15); // on "add"

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "Position on Calculator.add must succeed; got error: "
                + (r.getError() != null ? r.getError().getMessage() : "n/a"));
        Map<String, Object> data = getData(r);
        assertEquals("add", data.get("methodName"));
        assertEquals("com.example.Calculator", data.get("declaringType"));
        assertNotNull(data.get("locations"),
            "methodReferences must always be a list (possibly empty), not null");
        assertNotNull(data.get("totalCount"));
    }

    @Test @DisplayName("missing filePath is rejected with INVALID_PARAMETER")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("line", 10);
        args.put("column", 5);
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        assertEquals("INVALID_PARAMETER", r.getError().getCode());
        assertTrue(r.getError().getMessage().toLowerCase().contains("required"),
            "message must explain filePath is required; got: " + r.getError().getMessage());
    }

    @Test @DisplayName("missing line is rejected with INVALID_PARAMETER")
    void requiresLine() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", searchPatternsPath);
        args.put("column", 5);
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        assertEquals("INVALID_PARAMETER", r.getError().getCode());
        assertTrue(r.getError().getMessage().toLowerCase().contains("required"),
            "message must explain line/column are required; got: " + r.getError().getMessage());
    }

    @Test @DisplayName("missing column is rejected with INVALID_PARAMETER")
    void requiresColumn() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", searchPatternsPath);
        args.put("line", 10);
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        assertEquals("INVALID_PARAMETER", r.getError().getCode());
        assertTrue(r.getError().getMessage().toLowerCase().contains("required"),
            "message must explain line/column are required; got: " + r.getError().getMessage());
    }

    @Test @DisplayName("non-existent file resolves no element: SYMBOL_NOT_FOUND")
    void handlesNonExistentFile() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "/nonexistent/File.java");
        args.put("line", 10);
        args.put("column", 5);
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        // No fileNotFound path: a missing file yields no element at the position.
        assertEquals("SYMBOL_NOT_FOUND", r.getError().getCode());
    }

    @Test @DisplayName("negative maxResults is rejected with INVALID_PARAMETER")
    void negativeMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", searchPatternsPath);
        args.put("line", 10);
        args.put("column", 5);
        args.put("maxResults", -1);
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        assertEquals("INVALID_PARAMETER", r.getError().getCode());
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("MethodRefTarget.formatId: exactly one method reference at MethodRefUser line 11")
    void methodRefTarget_formatId_hasOneReference() {
        String targetPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/MethodRefTarget.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", targetPath);
        // 1-based line 9 `public static String formatId(int id) {` -> 0-based 8;
        // identifier "formatId" begins at column 25.
        args.put("line", 8);
        args.put("column", 25);
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("formatId", data.get("methodName"));
        assertEquals("com.example.MethodRefTarget", data.get("declaringType"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> refs = (List<Map<String, Object>>) data.get("locations");
        assertEquals(1, ((Number) data.get("totalCount")).intValue(),
            "MethodRefUser uses `MethodRefTarget::formatId` exactly once; got: "
                + data.get("totalCount") + " (" + refs + ")");

        // The single match must come from MethodRefUser.java.
        Map<String, Object> ref = refs.get(0);
        String filePath = (String) ref.get("filePath");
        assertNotNull(filePath);
        String normalized = filePath.replace('\\', '/');
        assertTrue(normalized.endsWith("MethodRefUser.java"),
            "Method reference must be located in MethodRefUser.java; got: " + filePath);

        // `MethodRefTarget::formatId` sits on 0-based line 15; the tool reports the
        // referenced method name `formatId`, which starts at column 57 (length 8).
        // A coordinate off-by-one fails here.
        assertEquals(15, ((Number) ref.get("line")).intValue(),
            "method-reference line must be 0-based 15; got: " + ref);
        assertEquals(57, ((Number) ref.get("column")).intValue(),
            "method-reference column must be 57 (the `formatId` name in MethodRefTarget::formatId); got: " + ref);
    }

    @Test
    @DisplayName("Calculator.add has no method-reference usages in fixtures (isolation)")
    void calculatorAdd_hasNoMethodReferences() {
        String calcPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Calculator.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calcPath);
        // Calculator.add() declared at line 14 (0-based 13). Position on "add" identifier.
        args.put("line", 13);
        args.put("column", 15);
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        // No project code uses `Calculator::add` as a method reference. SearchPatterns
        // uses other JDK method references (String::valueOf, ArrayList::new, etc.) but
        // not Calculator::add.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> refs = (List<Map<String, Object>>) data.get("locations");
        assertNotNull(refs);
        assertEquals(0, refs.size(),
            "Calculator.add is never used as a method reference; got: " + refs);
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
    @DisplayName("Bound instance method ref: MethodRefTarget.greet used via `instance::greet`")
    @SuppressWarnings("unchecked")
    void boundInstanceMethodRef_isFound() throws Exception {
        String tgt = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/MethodRefTarget.java").toString();
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(tgt));
        int idx = source.indexOf("public String greet");
        idx = source.indexOf("greet", idx);
        int line = (int) source.substring(0, idx).chars().filter(c -> c == '\n').count();
        int lineStart = source.lastIndexOf('\n', idx) + 1;
        int column = idx - lineStart;
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", tgt);
        args.put("line", line);
        args.put("column", column);
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("greet", data.get("methodName"));
        List<Map<String, Object>> refs = (List<Map<String, Object>>) data.get("locations");
        // MethodRefUser uses `instance::greet` once.
        assertEquals(1, refs.size(),
            "greet should have exactly one bound method-reference usage; got: " + refs);
    }

    @Test
    @DisplayName("Constructor reference: MethodRefTarget::new is found when positioning on the constructor")
    @SuppressWarnings("unchecked")
    void constructorRef_isFound() throws Exception {
        String tgt = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/MethodRefTarget.java").toString();
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(tgt));
        int idx = source.indexOf("public MethodRefTarget()");
        idx = source.indexOf("MethodRefTarget(", idx);
        int line = (int) source.substring(0, idx).chars().filter(c -> c == '\n').count();
        int lineStart = source.lastIndexOf('\n', idx) + 1;
        int column = idx - lineStart;
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", tgt);
        args.put("line", line);
        args.put("column", column);
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "Position on constructor must resolve; got: " +
                (r.getError() != null ? r.getError().getMessage() : "n/a"));
        List<Map<String, Object>> refs = (List<Map<String, Object>>) getData(r).get("locations");
        // MethodRefUser uses `MethodRefTarget::new` exactly once (a CreationReference).
        assertEquals(1, refs.size(),
            "MethodRefTarget::new must surface as exactly one method reference; got: " + refs);
    }

    @Test
    @DisplayName("Reference info includes filePath, line, column")
    @SuppressWarnings("unchecked")
    void referenceInfo_includesLocation() throws Exception {
        String tgt = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/MethodRefTarget.java").toString();
        ToolResponse r = tool.execute(argsAtIdentifier(tgt, "formatId"));
        assertTrue(r.isSuccess());
        List<Map<String, Object>> refs = (List<Map<String, Object>>) getData(r).get("locations");
        // MethodRefTarget::formatId is consumed exactly once (MethodRefUser line 15, col 57).
        assertEquals(1, refs.size(), "exactly one formatId reference; got: " + refs);
        Map<String, Object> ref = refs.get(0);
        assertTrue(((String) ref.get("filePath")).replace('\\', '/').endsWith("MethodRefUser.java"),
            "formatId reference is in MethodRefUser.java; got: " + ref);
        assertEquals(15, ((Number) ref.get("line")).intValue(), "0-based reference line; got: " + ref);
        assertEquals(57, ((Number) ref.get("column")).intValue(), "0-based reference column; got: " + ref);
    }

    @Test
    @DisplayName("SuperMethodReference (super::method) is found by find_method_references")
    @SuppressWarnings("unchecked")
    void superMethodReference_isFound() {
        // SuperMethodChild.greetReference returns `super::greet` — a SuperMethodReference,
        // distinct from ExpressionMethodReference / TypeMethodReference / CreationReference.
        // Calling find_method_references on SuperMethodParent.greet must surface this site.
        String parentPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/SuperMethodParent.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", parentPath);
        // 0-based line 8: `    public String greet(String name) {` — `greet` at column 18.
        args.put("line", 8);
        args.put("column", 18);
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "Position on SuperMethodParent.greet must resolve; got: " +
                (r.getError() != null ? r.getError().getMessage() : "n/a"));
        List<Map<String, Object>> refs = (List<Map<String, Object>>) getData(r).get("locations");
        // find_method_references finds ONLY method-reference EXPRESSIONS (`::`), per its
        // contract — not invocations. So super::greet (SuperMethodReference, line 21) is
        // reported; super.greet(name) (the SuperMethodInvocation on line 17) is correctly
        // excluded. This pins both the inclusion and the isolation.
        List<Map<String, Object>> childRefs = refs.stream()
            .filter(ref -> ((String) ref.get("filePath")).replace('\\', '/').endsWith("SuperMethodChild.java"))
            .toList();
        assertEquals(1, childRefs.size(), "exactly the super::greet expression; got: " + refs);
        Map<String, Object> superRef = childRefs.get(0);
        assertEquals(21, ((Number) superRef.get("line")).intValue(), "super::greet 0-based line; got: " + superRef);
        assertEquals(22, ((Number) superRef.get("column")).intValue(), "super::greet 0-based column; got: " + superRef);
        assertEquals("return super::greet;", superRef.get("context"), "exact context; got: " + superRef);
    }

    @Test
    @DisplayName("Position on a field (non-method element) is rejected with invalidParameter")
    void positionOnField_rejectedAsNonMethod() {
        // Source line 92-94 rejects when the resolved element is not an IMethod.
        // Calculator.lastResult is a field at 0-based line 6 col 16.
        String calcPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Calculator.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calcPath);
        args.put("line", 6);
        args.put("column", 16);

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(),
            "Field position must be rejected as 'not a method'; got success");
        assertNotNull(r.getError());
        String msg = r.getError().getMessage();
        assertTrue(msg.toLowerCase().contains("method"),
            "Error message must mention method; got: " + msg);
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: formatId reference at MethodRefUser line 15, column 57")
    void envelope_formatId_exactLocation() {
        String targetPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/MethodRefTarget.java").toString();
        ObjectNode args = envelope.args();
        args.put("filePath", targetPath);
        args.put("line", 8);
        args.put("column", 25);
        args.put("maxResults", 100);
        JsonNode payload = envelope.assertEnvelopeFidelity("find_method_references", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "find_method_references failed through the envelope: " + payload);
        JsonNode data = payload.get("data");
        assertEquals(1, data.get("totalCount").asInt());
        JsonNode ref = data.get("locations").get(0);
        assertTrue(ref.get("filePath").asText().replace('\\', '/').endsWith("MethodRefUser.java"));
        assertEquals(15, ref.get("line").asInt(),
            "method-reference line must survive the envelope");
        assertEquals(57, ref.get("column").asInt(),
            "method-reference column must survive the envelope");
    }
}
