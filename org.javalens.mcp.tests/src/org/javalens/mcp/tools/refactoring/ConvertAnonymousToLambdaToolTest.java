package org.javalens.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.ConvertAnonymousToLambdaTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ConvertAnonymousToLambdaTool.
 * Tests anonymous class to lambda expression conversion.
 */
class ConvertAnonymousToLambdaToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ConvertAnonymousToLambdaTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String anonymousExamplesPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new ConvertAnonymousToLambdaTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        anonymousExamplesPath = projectPath.resolve("src/main/java/com/example/AnonymousClassExamples.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("converts Runnable: interfaceType=Runnable, methodName=run, single replace edit with ->")
    void convertsSimpleRunnableWithCompleteResponse() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", anonymousExamplesPath);
        args.put("line", 19);  // new Runnable() in simpleRunnable()
        args.put("column", 28);

        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        // Interface and SAM identity — the tool reports the implemented functional
        // interface's simple name and the single abstract method's name. For Runnable,
        // these are deterministic.
        assertEquals("Runnable", data.get("interfaceType"),
            "Anonymous `new Runnable() { ... }` must report interfaceType=Runnable; got: " + data);
        assertEquals("run", data.get("methodName"),
            "Runnable's single abstract method is `run`; got: " + data);

        // Exactly one edit (the replacement of the anonymous class with a lambda).
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edits = (List<Map<String, Object>>) data.get("edits");
        assertEquals(1, edits.size(),
            "Conversion produces exactly one replace edit; got: " + edits);
        Map<String, Object> edit = edits.get(0);
        assertEquals("replace", edit.get("type"));
        assertNotNull(edit.get("startLine"));

        // The replacement text must be a lambda — contains `->`.
        String newText = (String) edit.get("newText");
        assertTrue(newText != null && newText.contains("->"),
            "Replacement must be a lambda containing `->`; got: " + newText);
        // And the data carries the exact lambda expression alongside.
        assertEquals(newText, data.get("lambdaExpression"),
            "data.lambdaExpression must mirror the edit's newText; got data: " + data);
    }

    @Test
    @DisplayName("converts Comparator with two parameters")
    void convertsComparator() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", anonymousExamplesPath);
        args.put("line", 33);  // new Comparator<String>() in comparatorExample()
        args.put("column", 31);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertNotNull(data.get("edits"));
    }

    @Test
    @DisplayName("handles single parameter Consumer")
    void handlesSingleParameter() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", anonymousExamplesPath);
        args.put("line", 45);  // Consumer<String> in singleParam()
        args.put("column", 55);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edits = (List<Map<String, Object>>) data.get("edits");
        assertFalse(edits.isEmpty());
        String newText = (String) edits.get(0).get("newText");
        assertTrue(newText.contains("->"));
    }

    @Test
    @DisplayName("generates block body for multiple statements")
    void generatesBlockBodyForMultipleStatements() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", anonymousExamplesPath);
        args.put("line", 98);  // blockBodyExample()
        args.put("column", 28);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edits = (List<Map<String, Object>>) data.get("edits");
        assertFalse(edits.isEmpty());
        String newText = (String) edits.get(0).get("newText");
        // Block-body lambda: `() -> { ... }` carrying all three statements of run()
        // intact. A truncated body would drop a statement or its terminator.
        assertTrue(newText.trim().startsWith("() ->"), "no-arg block lambda; got: " + newText);
        assertTrue(newText.contains("{") && newText.trim().endsWith("}"),
            "block body must be brace-delimited; got: " + newText);
        for (String stmt : List.of(
                "String msg = \"Hello\";",
                "msg = msg + \" World\";",
                "System.out.println(msg);")) {
            assertTrue(newText.contains(stmt),
                "block lambda must contain the complete statement `" + stmt + "`; got: " + newText);
        }
    }

    @Test
    @DisplayName("handles Supplier with return expression")
    void handlesSupplierWithReturn() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", anonymousExamplesPath);
        args.put("line", 113);  // Supplier in withReturn()
        args.put("column", 55);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
    }

    // ========== Rejection Tests ==========

    @Test
    @DisplayName("refuses anonymous class with this reference")
    void refusesAnonymousClassWithThisReference() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", anonymousExamplesPath);
        args.put("line", 58);  // withThisReference() - uses this.toString()
        args.put("column", 27);

        ToolResponse response = tool.execute(args);

        // At this position the cursor is not on the `new` keyword, so the tool resolves
        // no anonymous class. (The bare-this/super rebinding refusal proper is covered by
        // superMethodInvocationInBody_isRefused.)
        assertFalse(response.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, response.getError().getCode());
        assertEquals("Invalid parameter 'position': No anonymous class found at position. "
            + "Position cursor on 'new' keyword.", response.getError().getMessage());
    }

    @Test
    @DisplayName("refuses anonymous class with multiple methods")
    void refusesAnonymousClassWithMultipleMethods() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", anonymousExamplesPath);
        args.put("line", 84);  // multipleMethodsExample()
        args.put("column", 21);

        ToolResponse response = tool.execute(args);

        // The interface itself has multiple abstract methods, so it fails the
        // functional-interface (single-abstract-method) check before the body-decl check.
        assertFalse(response.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, response.getError().getCode());
        assertEquals("Invalid parameter 'type': Not a functional interface - must have exactly "
            + "one abstract method", response.getError().getMessage());
    }

    @Test
    @DisplayName("refuses non-functional interface")
    void refusesNonFunctionalInterface() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", anonymousExamplesPath);
        args.put("line", 126);  // nonFunctionalInterface() - extends ArrayList
        args.put("column", 31);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, response.getError().getCode());
        assertEquals("Invalid parameter 'type': Not a functional interface - must have exactly "
            + "one abstract method", response.getError().getMessage());
    }

    // ========== Required Parameter Tests ==========

    @Test
    @DisplayName("requires filePath parameter")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("line", 18);
        args.put("column", 27);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, response.getError().getCode());
        assertEquals("Invalid parameter 'filePath': Required", response.getError().getMessage());
    }

    @Test
    @DisplayName("requires line and column parameters")
    void requiresLineAndColumn() {
        ObjectNode args1 = objectMapper.createObjectNode();
        args1.put("filePath", anonymousExamplesPath);
        args1.put("column", 27);
        ToolResponse response1 = tool.execute(args1);
        assertFalse(response1.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, response1.getError().getCode());
        assertEquals("Invalid parameter 'line/column': Must be >= 0", response1.getError().getMessage());

        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("filePath", anonymousExamplesPath);
        args2.put("line", 18);
        ToolResponse response2 = tool.execute(args2);
        assertFalse(response2.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, response2.getError().getCode());
        assertEquals("Invalid parameter 'line/column': Must be >= 0", response2.getError().getMessage());
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("handles non-anonymous class position gracefully")
    void handlesNotAnAnonymousClass() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", anonymousExamplesPath);
        args.put("line", 10);  // Class declaration
        args.put("column", 13);

        ToolResponse response = tool.execute(args);

        // Line 10 col 13 is the outer class declaration; the offset does not resolve to a
        // valid expression node.
        assertFalse(response.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, response.getError().getCode());
        assertEquals("Invalid parameter 'position': Invalid position", response.getError().getMessage());
    }

    // ========== Behavior-matrix coverage ==========

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> editsOf(ToolResponse r) {
        return (List<Map<String, Object>>) getData(r).get("edits");
    }

    @Test
    @DisplayName("Runnable (no params): lambda starts with `() ->`")
    void runnable_noParamLambdaForm() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", anonymousExamplesPath);
        args.put("line", 19);
        args.put("column", 28);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        String lambda = (String) getData(r).get("lambdaExpression");
        assertNotNull(lambda);
        assertTrue(lambda.trim().startsWith("() ->"),
            "No-parameter Runnable must produce `() -> ...`; got: " + lambda);
    }

    @Test
    @DisplayName("Consumer (single param): lambda starts with `<name> ->` (no parentheses)")
    void consumer_singleParamLambdaForm() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", anonymousExamplesPath);
        args.put("line", 45);
        args.put("column", 55);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        String lambda = (String) getData(r).get("lambdaExpression");
        assertNotNull(lambda);
        // Single param: `s -> ...`, NOT `(s) -> ...`.
        assertFalse(lambda.trim().startsWith("("),
            "Single-parameter lambda must omit parentheses; got: " + lambda);
        assertTrue(lambda.contains("->"));
    }

    @Test
    @DisplayName("Comparator (two params): lambda starts with `(a, b) ->`")
    void comparator_twoParamLambdaForm() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", anonymousExamplesPath);
        args.put("line", 33);
        args.put("column", 31);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        String lambda = (String) getData(r).get("lambdaExpression");
        assertNotNull(lambda);
        assertTrue(lambda.trim().matches("^\\(.+,.+\\)\\s*->.*"),
            "Two-parameter Comparator must produce `(a, b) -> ...`; got: " + lambda);
    }

    @Test
    @DisplayName("Comparator interfaceType reports parameterized form; methodName=compare")
    void comparator_typeAndMethodReported() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", anonymousExamplesPath);
        args.put("line", 33);
        args.put("column", 31);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        // JDT reports the parameterized type via ITypeBinding.getName(): `Comparator<String>`.
        String iface = (String) data.get("interfaceType");
        assertTrue(iface != null && iface.startsWith("Comparator"),
            "interfaceType must start with `Comparator`; got: " + iface);
        assertEquals("compare", data.get("methodName"));
    }

    @Test
    @DisplayName("Replace edit carries type, startLine/Column, endLine/Column, startOffset/endOffset, oldText, newText")
    void editShape_full() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", anonymousExamplesPath);
        args.put("line", 19);
        args.put("column", 28);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> e = editsOf(r).get(0);
        for (String key : List.of("type", "startLine", "startColumn", "endLine", "endColumn",
                "startOffset", "endOffset", "oldText", "newText")) {
            assertNotNull(e.get(key), key + " missing on convert edit: " + e);
        }
        assertEquals("replace", e.get("type"));
        // oldText must start with `new` (the anonymous class creation).
        String oldText = (String) e.get("oldText");
        assertTrue(oldText.startsWith("new "),
            "oldText must begin with `new` (anonymous class creation); got: " + oldText);
    }

    @Test
    @DisplayName("Non-existent file rejected")
    void nonExistentFile_isRejected() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "/nonexistent/Path.java");
        args.put("line", 19);
        args.put("column", 28);
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.FILE_NOT_FOUND, r.getError().getCode());
        assertEquals("File not found: /nonexistent/Path.java", r.getError().getMessage());
    }

    // ========== Refusal-decision completeness ==========

    @Test
    @DisplayName("SAM body using super.method(): refuses conversion")
    void superMethodInvocationInBody_isRefused() {
        // super in an anonymous class binds to the SAM interface's super
        // (Object) as viewed from the anonymous instance. After conversion the
        // same `super` would bind to the enclosing class's super — a different
        // runtime target. The tool must refuse.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", anonymousExamplesPath);
        args.put("line", 142);   // new java.util.function.Supplier<String>() in withSuperMethodInvocation()
        args.put("column", 55);

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(),
            "Anonymous class whose SAM body invokes super.toString() must be refused; got success: " + r.getData());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, r.getError().getCode());
        assertEquals("Invalid parameter 'anonymousClass': Method uses bare `this` or a `super` "
            + "reference whose binding differs in a lambda body. Manual review required.",
            r.getError().getMessage());
    }

    @Test
    @DisplayName("Qualified Outer.this in body: converts successfully (qualified this has identical meaning in lambda)")
    void qualifiedOuterThisInBody_isConverted() {
        // Bare `this` rebinds when converting anonymous → lambda. But
        // EnclosingClass.this resolves to the enclosing instance in both
        // contexts and is therefore safe to leave in the converted body.
        // The fixture's withOuterThis() method exercises this case.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", anonymousExamplesPath);
        args.put("line", 71);    // new Runnable() in withOuterThis()
        args.put("column", 28);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "Anonymous class using only qualified Outer.this must convert successfully; got refusal: " +
                (r.getError() != null ? r.getError().getMessage() : "n/a"));
    }

    @Test
    @DisplayName("SAM body has a field declared alongside the method: refuses conversion")
    void fieldDeclaredInAnonymousBody_isRefused() {
        // Lambdas cannot carry per-instance state. An anonymous class that
        // declares fields (or initializers, or nested types) alongside its
        // SAM cannot be losslessly converted — the state would be dropped
        // and references to it would no longer compile.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", anonymousExamplesPath);
        args.put("line", 157);   // new Runnable() in withFieldDeclaredInBody()
        args.put("column", 28);

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(),
            "Anonymous class with a field declared alongside the SAM must be refused; got success: " + r.getData());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, r.getError().getCode());
        assertEquals("Invalid parameter 'anonymousClass': Anonymous class declares non-method "
            + "members (field, initializer, or nested type) that cannot be represented in a "
            + "lambda. Manual review required.", r.getError().getMessage());
    }

    // ========== Exact converted-text content ==========

    @Test
    @DisplayName("Single-statement Runnable converts to the exact expression lambda")
    void runnable_exactExpressionLambda() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", anonymousExamplesPath);
        args.put("line", 19);
        args.put("column", 28);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        // run()'s body is the single expression statement System.out.println("Hello");
        // which converts to an expression-bodied lambda. The full text must be
        // complete — a truncated body (the audited failure) would drop the final ')'.
        assertEquals("() -> System.out.println(\"Hello\")",
            ((String) data.get("lambdaExpression")).trim(),
            "exact expression lambda for the single-statement Runnable");
        assertEquals(data.get("lambdaExpression"), editsOf(r).get(0).get("newText"),
            "data.lambdaExpression must mirror the edit newText exactly");
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: Runnable converts to the exact expression lambda")
    void envelope_runnable_exactLambda() {
        ObjectNode args = envelope.args();
        args.put("filePath", anonymousExamplesPath);
        args.put("line", 19);
        args.put("column", 28);
        JsonNode payload = envelope.assertEnvelopeFidelity("convert_anonymous_to_lambda", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "convert_anonymous_to_lambda failed through the envelope: " + payload);
        assertEquals("() -> System.out.println(\"Hello\")",
            payload.get("data").get("lambdaExpression").asText().trim(),
            "the complete lambda text must survive the JSON-RPC envelope");
    }
}
