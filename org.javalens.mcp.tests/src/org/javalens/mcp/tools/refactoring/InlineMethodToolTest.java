package org.javalens.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.InlineMethodTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for InlineMethodTool.
 * Tests method call inlining by replacing call with method body.
 */
class InlineMethodToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private InlineMethodTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String refactoringTargetPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new InlineMethodTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        refactoringTargetPath = projectPath.resolve("src/main/java/com/example/RefactoringTarget.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("inlines method call with complete response including edit details")
    void inlinesMethodCallWithCompleteResponse() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 64);  // int doubled = doubleValue(x);
        args.put("column", 22);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        // Verify method info
        assertEquals("doubleValue", data.get("methodName"));
        String methodClass = (String) data.get("methodClass");
        assertNotNull(methodClass, "methodClass missing");
        assertFalse(methodClass.isBlank(), "methodClass non-blank; got: " + data);

        // Verify edit structure
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edits = (List<Map<String, Object>>) data.get("edits");
        assertNotNull(edits, "edits list missing");
        assertFalse(edits.isEmpty());
        Map<String, Object> edit = edits.get(0);
        String newText = (String) edit.get("newText");
        assertNotNull(newText, "newText missing on first edit");
        assertFalse(newText.isBlank(), "newText non-blank; got: " + edit);

        // The inlined code should contain the multiplication
        assertTrue(newText.contains("x") || newText.contains("*"));
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("inlining doubleValue at processValue: edit text is `(x * 2)` — paren-wrapped for complex (INFIX) expression")
    void inline_doubleValue_emitsExpectedExpression() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 64);   // `int doubled = doubleValue(x);`
        args.put("column", 22);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edits = (List<Map<String, Object>>) getData(r).get("edits");
        assertFalse(edits.isEmpty());

        String newText = (String) edits.get(0).get("newText");
        // doubleValue body is `return value * 2;` — `value * 2` is an INFIX_EXPRESSION
        // which triggers the isComplexExpression paren-wrap branch in buildInlinedCode.
        // With param `value` substituted for arg `x`, the inlined text must be `(x * 2)`.
        // Pin exact text (not just `contains`) so a regression that drops the paren-wrap
        // and emits `x * 2` would break operator precedence at the call site and surface
        // here.
        assertEquals("(x * 2)", newText,
            "Complex (INFIX) return expression must be paren-wrapped; got: " + newText);
    }

    // ========== Required Parameter Tests ==========

    @Test
    @DisplayName("requires filePath parameter")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("line", 64);
        args.put("column", 22);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    @Test
    @DisplayName("requires line and column parameters")
    void requiresLineAndColumn() {
        // Missing line
        ObjectNode args1 = objectMapper.createObjectNode();
        args1.put("filePath", refactoringTargetPath);
        args1.put("column", 22);

        ToolResponse response1 = tool.execute(args1);
        assertFalse(response1.isSuccess());

        // Missing column
        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("filePath", refactoringTargetPath);
        args2.put("line", 64);

        ToolResponse response2 = tool.execute(args2);
        assertFalse(response2.isSuccess());
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("handles non-method call position gracefully")
    void handlesNotAMethodCall() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 15);  // Field declaration
        args.put("column", 19);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    @Test
    @DisplayName("inlining a method whose body contains super.X() at a call site in a SUBCLASS must NOT silently produce semantically-wrong code")
    void inlineMethod_withSuperCallInBody_isSurfaced() {
        // SuperInlineTarget.label() returns `super.toString() + ":labeled"`. The super
        // there points to Object. SuperInlineConsumer extends SuperInlineTarget and
        // invokes this.label() in useIt(). Inlining label() at that call site would
        // textually substitute `super.toString()` into the subclass's scope, where
        // `super` means SuperInlineTarget, not Object. That changes the call's
        // dispatch target — semantically wrong.
        //
        // Acceptable contracts for the tool:
        //   (a) the response is INVALID_PARAMETER / refusal mentioning super
        //   (b) the response carries a warnings entry naming `super`
        //   (c) some other explicit signal in the response
        // Silent inlining is the bug.
        String consumerPath = projectPath
            .resolve("src/main/java/com/example/SuperInlineConsumer.java").toString();
        // SuperInlineConsumer.java 0-based line 13: `        return this.label() + "!";`
        // "label" identifier starts at column 21 (8 indent + "return this." = 20; +1 for 0-based of "l").
        // Locate via source-text scan to avoid column drift.
        String source;
        try {
            source = java.nio.file.Files.readString(java.nio.file.Path.of(consumerPath));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        int idx = source.indexOf("this.label()");
        assertTrue(idx >= 0, "Fixture must contain `this.label()`");
        int labelIdx = source.indexOf("label", idx);
        int lineNum = (int) source.substring(0, labelIdx).chars().filter(c -> c == '\n').count();
        int col = labelIdx - (source.lastIndexOf('\n', labelIdx) + 1);

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", consumerPath);
        args.put("line", lineNum);
        args.put("column", col);

        ToolResponse response = tool.execute(args);

        // The tool must NOT succeed silently with an inlined edit that contains `super`.
        boolean refusedWithError = !response.isSuccess()
            && response.getError() != null
            && response.getError().getMessage() != null
            && response.getError().getMessage().toLowerCase().contains("super");

        boolean surfacedAsWarning = false;
        boolean leakedSuperIntoInlinedCode = false;
        if (response.isSuccess()) {
            Map<String, Object> data = getData(response);
            Object warningsObj = data.get("warnings");
            if (warningsObj instanceof List<?> warnings) {
                surfacedAsWarning = warnings.stream()
                    .anyMatch(w -> w != null && w.toString().toLowerCase().contains("super"));
            }
            // Check whether the inlined code body leaked super textually.
            Object inlined = data.get("inlinedCode");
            if (inlined != null && inlined.toString().contains("super.")) {
                leakedSuperIntoInlinedCode = true;
            }
        }

        // Silent leak is the bug — the inlined code shouldn't contain `super.` in a
        // form that changes meaning. Either a refusal or an explicit warning is
        // acceptable. Bare leak with no signal is not.
        assertTrue(refusedWithError || surfacedAsWarning,
            "Inlining a method with super.X() in its body at a subclass call site must " +
                "either refuse with a `super`-mentioning error OR carry a `super`-mentioning " +
                "warning. Got success=" + response.isSuccess() +
                ", error=" + (response.getError() != null ? response.getError().getMessage() : "n/a") +
                ", leakedSuperIntoInlined=" + leakedSuperIntoInlinedCode +
                ", data=" + (response.isSuccess() ? getData(response).keySet() : "n/a"));
    }

    // ========== Behavior-matrix coverage ==========

    private ObjectNode doubleValueArgs() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 64);
        args.put("column", 22);
        return args;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> editsOf(ToolResponse r) {
        return (List<Map<String, Object>>) getData(r).get("edits");
    }

    @Test
    @DisplayName("methodClass is RefactoringTarget (the declaring type of doubleValue)")
    void methodClass_reported() {
        ToolResponse r = tool.execute(doubleValueArgs());
        assertTrue(r.isSuccess());
        assertEquals("RefactoringTarget", getData(r).get("methodClass"));
    }

    @Test
    @DisplayName("parameterCount=1 for doubleValue(int value)")
    void parameterCount_reported() {
        ToolResponse r = tool.execute(doubleValueArgs());
        assertTrue(r.isSuccess());
        assertEquals(1, ((Number) getData(r).get("parameterCount")).intValue());
    }

    @Test
    @DisplayName("isExpressionContext=true when call is part of a larger expression (assignment RHS)")
    void isExpressionContext_trueForAssignmentRHS() {
        // `int doubled = doubleValue(x);` — the call is the initializer of the variable
        // declaration, an expression context.
        ToolResponse r = tool.execute(doubleValueArgs());
        assertTrue(r.isSuccess());
        assertEquals(Boolean.TRUE, getData(r).get("isExpressionContext"));
    }

    @Test
    @DisplayName("inlinedCode field equals the edit's newText")
    void inlinedCode_matchesEditNewText() {
        ToolResponse r = tool.execute(doubleValueArgs());
        assertTrue(r.isSuccess());
        String inlined = (String) getData(r).get("inlinedCode");
        String editText = (String) editsOf(r).get(0).get("newText");
        assertEquals(inlined, editText,
            "inlinedCode top-level field must equal the produced edit's newText");
    }

    @Test
    @DisplayName("Single replace edit carries start/end line/column, startOffset/endOffset, oldText, newText")
    void replaceEdit_shape() {
        ToolResponse r = tool.execute(doubleValueArgs());
        assertTrue(r.isSuccess());
        List<Map<String, Object>> edits = editsOf(r);
        assertEquals(1, edits.size(), "inline_method emits exactly one edit");
        Map<String, Object> e = edits.get(0);
        for (String key : List.of("type", "startLine", "startColumn", "endLine", "endColumn",
                "startOffset", "endOffset", "oldText", "newText")) {
            assertNotNull(e.get(key), key + " missing on inline edit: " + e);
        }
        assertEquals("replace", e.get("type"));
    }

    @Test
    @DisplayName("Position on a non-existent file is rejected (file not found)")
    void nonExistentFile_isRejected() {
        ObjectNode args = doubleValueArgs();
        args.put("filePath", "/nonexistent/Path.java");
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
    }

    @Test
    @DisplayName("Position on whitespace (no method invocation) is rejected")
    void positionOnWhitespace_isRejected() {
        // Line 0 col 0 — package keyword, definitely not a method call.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 0);
        args.put("column", 0);
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: inlining doubleValue(x) emits exactly `(x * 2)` (paren-wrapped INFIX)")
    void envelope_inlineDoubleValue_exactExpression() {
        ObjectNode args = envelope.args();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 64);   // `int doubled = doubleValue(x);`
        args.put("column", 22);
        JsonNode payload = envelope.payload("inline_method", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "inline_method failed through the envelope: " + payload);
        JsonNode data = payload.get("data");
        assertEquals("doubleValue", data.get("methodName").asText());
        assertEquals("RefactoringTarget", data.get("methodClass").asText());
        JsonNode edits = data.get("edits");
        assertEquals(1, edits.size(), "inline_method emits exactly one edit through the envelope");
        assertEquals("(x * 2)", edits.get(0).get("newText").asText(),
            "the paren-wrapped INFIX inline text must survive the envelope verbatim");
    }
}
