package org.javalens.mcp.tools.quickfix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.DiagnoseAndFixTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins diagnose_and_fix (#22): one call returns the file's problems together
 * with the computed top-fix edits as editsByFile, and a clean file returns
 * empty sets. Strictly read-only — nothing is written.
 */
class DiagnoseAndFixToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private DiagnoseAndFixTool tool;
    private EnvelopeHarness envelope;
    private String demoPath;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("java25-maven");
        tool = new DiagnoseAndFixTool(() -> service);
        envelope = new EnvelopeHarness(service);
        demoPath = helper.getFixturePath("java25-maven")
            .resolve("src/main/java/com/example/DiagnoseFixDemo.java").toString();
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test
    @DisplayName("unused import: the problem and its remove_import edit arrive in one response")
    @SuppressWarnings("unchecked")
    void unusedImport_problemAndEditCombined() {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", demoPath);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "expected success; error: "
            + (r.getError() != null ? r.getError().getCode() + " " + r.getError().getMessage() : "n/a"));

        Map<String, Object> data = getData(r);
        assertEquals(1, ((Number) data.get("problemCount")).intValue(),
            "exactly one fixable problem; got: " + data.get("problems"));
        List<Map<String, Object>> problems = (List<Map<String, Object>>) data.get("problems");
        assertEquals(1, problems.size(),
            "exactly the unused-import warning; got: " + problems);
        Map<String, Object> problem = problems.get(0);
        // DiagnoseFixDemo uses List but never Map -> a single unused-import warning.
        assertEquals("The import java.util.Map is never used", problem.get("message"));
        assertEquals("warning", problem.get("severity"));
        assertEquals(3, ((Number) problem.get("line")).intValue(),
            "the Map import is at 0-based line 3; got: " + problem);
        // Map is the second import (index 1; List is index 0), so the top fix removes it.
        assertEquals("remove_import:1", problem.get("fixId"));
        assertEquals("Remove unused import", problem.get("fixLabel"));

        Map<String, List<Map<String, Object>>> editsByFile =
            (Map<String, List<Map<String, Object>>>) data.get("editsByFile");
        assertEquals(1, editsByFile.size(),
            "edits for exactly the diagnosed file; got: " + editsByFile.keySet());
        List<Map<String, Object>> edits = editsByFile.values().iterator().next();
        assertEquals(1, edits.size(), "exactly one remove_import delete edit; got: " + edits);
        Map<String, Object> delete = edits.get(0);
        assertEquals("delete", delete.get("type"));
        assertEquals(3, ((Number) delete.get("startLine")).intValue(),
            "the delete removes the import at 0-based line 3; got: " + delete);
        // The deleted byte range is exactly the Map import statement (plus its trailing
        // newline, which .strip() removes); offsets index the same source the tool read.
        String source;
        try {
            source = java.nio.file.Files.readString(java.nio.file.Path.of(demoPath));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        int start = ((Number) delete.get("startOffset")).intValue();
        int end = ((Number) delete.get("endOffset")).intValue();
        assertEquals("import java.util.Map;", source.substring(start, end).strip(),
            "the deleted range must be exactly the Map import; got [" + start + "," + end + ")");

        assertEquals(1, ((Number) data.get("totalEdits")).intValue());
    }

    @Test
    @DisplayName("a clean file returns empty problems and edits")
    @SuppressWarnings("unchecked")
    void cleanFile_returnsEmptySets() {
        String cleanPath = helper.getFixturePath("java25-maven")
            .resolve("src/main/java/com/example/hier/HierChild.java").toString();
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", cleanPath);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "expected success; got: " + r.getData());

        Map<String, Object> data = getData(r);
        assertEquals(0, ((Number) data.get("problemCount")).intValue(),
            "clean file has no problems; got: " + data.get("problems"));
        assertEquals(0, ((Number) data.get("totalEdits")).intValue());
        assertTrue(((Map<String, ?>) data.get("editsByFile")).isEmpty(),
            "clean file yields an empty edit set");
    }

    @Test
    @DisplayName("missing filePath and unknown file are rejected")
    void invalidInputs_rejected() {
        ToolResponse missingFilePath = tool.execute(mapper.createObjectNode());
        assertFalse(missingFilePath.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, missingFilePath.getError().getCode());
        assertEquals("Invalid parameter 'filePath': Required", missingFilePath.getError().getMessage());

        // An unknown file fails at the diagnostics step, which surfaces FILE_NOT_FOUND.
        ObjectNode unknown = mapper.createObjectNode();
        unknown.put("filePath", "/nonexistent/Nope.java");
        ToolResponse unknownFile = tool.execute(unknown);
        assertFalse(unknownFile.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.FILE_NOT_FOUND, unknownFile.getError().getCode());
        assertEquals("File not found: /nonexistent/Nope.java", unknownFile.getError().getMessage());
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: the unused Map import and its remove_import edit arrive in one response")
    void envelope_unusedImport_problemAndEditCombined() {
        ObjectNode args = envelope.args();
        args.put("filePath", demoPath);
        JsonNode payload = envelope.assertEnvelopeFidelity("diagnose_and_fix", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "diagnose_and_fix failed through the envelope: " + payload);
        JsonNode data = payload.get("data");
        JsonNode problems = data.get("problems");
        assertEquals(1, problems.size(), "exactly the unused-import warning through the envelope");
        JsonNode problem = problems.get(0);
        assertEquals("The import java.util.Map is never used", problem.get("message").asText());
        assertEquals("warning", problem.get("severity").asText());
        assertEquals(3, problem.get("line").asInt());
        assertEquals("remove_import:1", problem.get("fixId").asText());
        assertEquals("Remove unused import", problem.get("fixLabel").asText());
        assertEquals(1, data.get("editsByFile").size(),
            "edits for exactly the diagnosed file through the envelope; got: " + data.get("editsByFile"));
        assertEquals(1, data.get("totalEdits").asInt(), "exactly one edit through the envelope");
    }
}
