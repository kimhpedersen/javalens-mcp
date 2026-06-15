package org.javalens.mcp.tools.quickfix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.ApplyQuickFixTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ApplyQuickFixTool.
 * Tests applying quick fixes like adding imports, throws declarations.
 */
class ApplyQuickFixToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ApplyQuickFixTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String calculatorPath;
    private String searchPatternsPath;

    @BeforeEach
    void setUp() throws Exception {
        // Use loadProjectCopy since we might modify files
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new ApplyQuickFixTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getTempDirectory().resolve("simple-maven");
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
        searchPatternsPath = projectPath.resolve("src/main/java/com/example/SearchPatterns.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getEdits(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("edits");
    }

    @Test
    @DisplayName("add_import: Calculator (no existing imports) emits one insert edit with `import java.util.Date;`")
    void addImport_calculatorNoImports_emitsImportInsert() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("fixId", "add_import:java.util.Date");

        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("add_import:java.util.Date", data.get("fixId"));
        assertEquals("add_import", data.get("fixType"));

        // Calculator declares a package but no imports; the tool must produce exactly one
        // insert edit after the package declaration that introduces the import.
        List<Map<String, Object>> edits = getEdits(data);
        assertEquals(1, edits.size(),
            "add_import on a file with no existing imports must produce exactly 1 edit; got: " + edits);

        Map<String, Object> edit = edits.get(0);
        assertEquals("insert", edit.get("type"),
            "Adding an import is an insertion; got: " + edit);
        // The insert lands at the start of 0-based line 2 (the `/**` javadoc), right after
        // the package line and the blank line that follow it. The raw byte offset varies
        // with the checkout's line-ending normalization (CRLF vs LF), so it is asserted
        // relationally; the line number is determinate.
        assertEquals(2, ((Number) edit.get("line")).intValue(),
            "import insert lands at 0-based line 2; got: " + edit);
        assertTrue(((Number) edit.get("offset")).intValue() > 0, "offset is into the source; got: " + edit);

        assertEquals("\nimport java.util.Date;\n",
            ((String) edit.get("newText")).replace("\r\n", "\n"));
    }

    @Test
    @DisplayName("remove_import: SearchPatterns index 0 (java.io.IOException at 0-based line 2) emits one delete edit")
    void removeImport_searchPatternsIndexZero_emitsDeleteAtImportLine() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", searchPatternsPath);
        args.put("fixId", "remove_import:0");

        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess(),
            "remove_import:0 must succeed for a file with at least one import");
        Map<String, Object> data = getData(response);
        assertEquals("remove_import", data.get("fixType"));

        // SearchPatterns.java imports java.io.IOException at index 0 (0-based line 2).
        List<Map<String, Object>> edits = getEdits(data);
        assertEquals(1, edits.size(),
            "remove_import must produce exactly 1 edit; got: " + edits);

        Map<String, Object> edit = edits.get(0);
        assertEquals("delete", edit.get("type"),
            "Removing an import is a deletion; got: " + edit);
        assertEquals(2, ((Number) edit.get("startLine")).intValue(),
            "java.io.IOException is at 0-based line 2 in SearchPatterns.java; got: " + edit);
        assertNotNull(edit.get("startOffset"));
        assertNotNull(edit.get("endOffset"));
        assertTrue(
            ((Number) edit.get("endOffset")).intValue() > ((Number) edit.get("startOffset")).intValue(),
            "delete edit must have endOffset > startOffset; got: " + edit);
    }

    @Test
    @DisplayName("add_throws: Calculator.add (no existing throws) inserts ` throws java.io.IOException`")
    void addThrows_calculatorAdd_insertsThrowsClause() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("fixId", "add_throws:java.io.IOException");
        // Calculator.add() declaration is at 0-based line 14 (1-based line 15).
        args.put("line", 14);

        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess(),
            "add_throws at a real method position must succeed");
        Map<String, Object> data = getData(response);
        assertEquals("add_throws", data.get("fixType"));

        // Calculator.add has no existing throws clause. The tool must insert ` throws X`
        // right after the closing paren of the parameter list.
        List<Map<String, Object>> edits = getEdits(data);
        assertEquals(1, edits.size(),
            "add_throws must produce exactly 1 edit; got: " + edits);

        Map<String, Object> edit = edits.get(0);
        assertEquals("insert", edit.get("type"),
            "Adding a throws clause is an insertion; got: " + edit);
        assertEquals(" throws java.io.IOException", edit.get("newText"),
            "First-throws insertion must produce ` throws X` (with leading space); got: " + edit);
    }

    @Test
    @DisplayName("add_throws: line not on a method returns invalid_parameter error")
    void addThrows_noMethodAtLine_rejected() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("fixId", "add_throws:java.io.IOException");
        // 0-based line 0 is the `package com.example;` line — not a method.
        args.put("line", 0);

        ToolResponse response = tool.execute(args);
        assertFalse(response.isSuccess(),
            "add_throws on a non-method position must fail with an error response");
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, response.getError().getCode());
        assertEquals("Invalid parameter 'fixId': No method found at line 0", response.getError().getMessage());
    }

    @Test
    @DisplayName("surround_try_catch: wraps `lastResult = a + b;` in Calculator.add body with try-catch")
    void surroundTryCatch_wrapsStatementInTryCatch() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("fixId", "surround_try_catch:java.io.IOException");
        // `lastResult = a + b;` is at 0-based line 15 (1-based line 16) inside Calculator.add.
        args.put("line", 15);

        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess(),
            "surround_try_catch on a real statement line must succeed");
        Map<String, Object> data = getData(response);
        assertEquals("surround_try_catch", data.get("fixType"));

        List<Map<String, Object>> edits = getEdits(data);
        assertEquals(1, edits.size(),
            "surround_try_catch must produce exactly 1 edit; got: " + edits);

        Map<String, Object> edit = edits.get(0);
        assertEquals("replace", edit.get("type"),
            "Wrapping is a replacement of the original statement; got: " + edit);

        // Exact JDT-rendered wrapped block (CRLF-normalized).
        assertEquals(
            "try {\n"
            + "            lastResult = a + b;\n"
            + "        } catch (java.io.IOException e) {\n"
            + "            // TODO: handle exception\n"
            + "            e.printStackTrace();\n"
            + "        }",
            ((String) edit.get("newText")).replace("\r\n", "\n"));
    }

    @Test
    @DisplayName("surround_try_catch: line with no statement returns invalid_parameter error")
    void surroundTryCatch_noStatementAtLine_rejected() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("fixId", "surround_try_catch:java.io.IOException");
        // 0-based line 0 is the package declaration — not a Statement.
        args.put("line", 0);

        ToolResponse response = tool.execute(args);
        assertFalse(response.isSuccess(),
            "surround_try_catch on a non-statement line must fail with an error response");
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, response.getError().getCode());
        assertEquals("Invalid parameter 'fixId': No statement found at line 0", response.getError().getMessage());
    }

    @Test
    @DisplayName("requires filePath parameter")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("fixId", "add_import:java.util.List");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, response.getError().getCode());
        assertEquals("Invalid parameter 'filePath': Required", response.getError().getMessage());
    }

    @Test
    @DisplayName("requires fixId parameter")
    void requiresFixId() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, response.getError().getCode());
        assertEquals("Invalid parameter 'fixId': Required", response.getError().getMessage());
    }

    @Test
    @DisplayName("handles invalid fixId format")
    void handlesInvalidFixIdFormat() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("fixId", "invalid_fix_id");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, response.getError().getCode());
        assertEquals("Invalid parameter 'fixId': Invalid format. Expected type:param",
            response.getError().getMessage());
    }

    @Test
    @DisplayName("handles unknown fix type and non-existent file")
    void handlesErrorCases() {
        // Unknown fix type: the switch default throws IllegalArgument' "Unknown fix type: <type>",
        // surfaced as an invalid_parameter error naming the fixId.
        ObjectNode args1 = objectMapper.createObjectNode();
        args1.put("filePath", calculatorPath);
        args1.put("fixId", "unknown_type:value");

        ToolResponse response1 = tool.execute(args1);
        assertFalse(response1.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, response1.getError().getCode());
        assertEquals("Invalid parameter 'fixId': Unknown fix type: unknown_type",
            response1.getError().getMessage());

        // Non-existent file: the compilation unit cannot be resolved -> FILE_NOT_FOUND.
        String missing = projectPath.resolve("NonExistent.java").toString();
        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("filePath", missing);
        args2.put("fixId", "add_import:java.util.List");

        ToolResponse response2 = tool.execute(args2);
        assertFalse(response2.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.FILE_NOT_FOUND, response2.getError().getCode());
        assertEquals("File not found: " + missing, response2.getError().getMessage());
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("fixId without colon is rejected with invalid_parameter")
    void fixIdMissingColon_rejected() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("fixId", "add_import");
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, r.getError().getCode());
        assertEquals("Invalid parameter 'fixId': Invalid format. Expected type:param",
            r.getError().getMessage());
    }

    @Test
    @DisplayName("remove_import with out-of-range index is rejected with the index in the message")
    void removeImport_outOfRangeIndex() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", searchPatternsPath);
        args.put("fixId", "remove_import:9999");
        ToolResponse r = tool.execute(args);
        // applyRemoveImport throws IllegalArgument for an out-of-range index, surfaced
        // as an invalid_parameter error naming the offending index.
        assertFalse(r.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, r.getError().getCode());
        assertEquals("Invalid parameter 'fixId': Import index out of range: 9999",
            r.getError().getMessage());
    }

    @Test
    @DisplayName("Response shape: filePath, fixId, fixType, edits all present")
    void responseShape_includesAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("fixId", "add_import:java.util.Date");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        for (String key : List.of("filePath", "fixId", "fixType", "edits")) {
            assertNotNull(data.get(key), key + " missing in response: " + data);
        }
        assertEquals("add_import", data.get("fixType"));
    }

    @Test
    @DisplayName("Empty fixId param part (`add_import:`) is rejected as invalid format")
    void emptyFixParam_handled() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("fixId", "add_import:");
        ToolResponse r = tool.execute(args);
        // A trailing colon leaves an empty parameter; the tool must reject it rather than
        // emit a malformed `import ;` edit and report success.
        assertFalse(r.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, r.getError().getCode());
        assertEquals("Invalid parameter 'fixId': Invalid format. Expected type:param",
            r.getError().getMessage());
    }

    @Test
    @DisplayName("add_throws on a constructor with this(...) delegation: inserts ` throws java.io.IOException` on that constructor")
    void addThrows_onConstructorWithThisDelegation_addsToTargetConstructor() {
        // ConstructorTarget(String name) at 0-based line 7 delegates to
        // ConstructorTarget(String name, int count) via `this(name, 0)`. A constructor is
        // a MethodDeclaration in JDT, so add_throws finds it and — since it has no existing
        // throws clause — inserts ` throws X` after the parameter list's closing paren. The
        // this() call site does NOT need updating: it is the only edit produced.
        String constructorTargetPath = projectPath
            .resolve("src/main/java/com/example/ConstructorTarget.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", constructorTargetPath);
        args.put("fixId", "add_throws:java.io.IOException");
        args.put("line", 7);  // 0-based line of the 1-arg ConstructorTarget declaration

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "add_throws on a constructor with this() delegation must succeed; got error: "
                + (r.getError() != null ? r.getError().getMessage() : "n/a"));
        Map<String, Object> data = getData(r);
        assertEquals("add_throws", data.get("fixType"));

        List<Map<String, Object>> edits = getEdits(data);
        assertEquals(1, edits.size(),
            "add_throws emits exactly one insert edit for the throws clause; got: " + edits);
        Map<String, Object> edit = edits.get(0);
        assertEquals("insert", edit.get("type"));
        assertEquals(" throws java.io.IOException", edit.get("newText"),
            "First-throws insertion on the constructor must produce ` throws X`; got: " + edit);
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: add_throws on Calculator.add inserts exactly ` throws java.io.IOException`")
    void envelope_addThrows_exactInsertText() {
        ObjectNode args = envelope.args();
        args.put("filePath", calculatorPath);
        args.put("fixId", "add_throws:java.io.IOException");
        args.put("line", 14); // 0-based: Calculator.add() declaration
        JsonNode payload = envelope.assertEnvelopeFidelity("apply_quick_fix", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "apply_quick_fix failed through the envelope: " + payload);
        JsonNode data = payload.get("data");
        assertEquals("add_throws", data.get("fixType").asText());
        JsonNode edits = data.get("edits");
        assertEquals(1, edits.size(), "exactly one edit through the envelope");
        JsonNode edit = edits.get(0);
        assertEquals("insert", edit.get("type").asText(), "add_throws is an insertion through the envelope");
        assertEquals(" throws java.io.IOException", edit.get("newText").asText(),
            "first-throws insertion text ` throws X` must survive the envelope verbatim");
    }
}
