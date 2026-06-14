package org.javalens.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.MoveTypeToNewFileTool;
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
 * Pins move_type_to_new_file: NestHolder.NestedPayload moves into its own
 * top-level file (createdFiles), and NestHolder is edited to drop the nested
 * declaration — via returned text only.
 */
class MoveTypeToNewFileToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private MoveTypeToNewFileTool tool;
    private EnvelopeHarness envelope;
    private String holderPath;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("java25-maven");
        tool = new MoveTypeToNewFileTool(() -> service);
        envelope = new EnvelopeHarness(service);
        holderPath = helper.getFixturePath("java25-maven")
            .resolve("src/main/java/com/example/ipo/NestHolder.java").toString();
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test
    @DisplayName("moving NestedPayload creates its file and removes the nested declaration")
    @SuppressWarnings("unchecked")
    void moveType_createsFileAndRemovesNested() {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", holderPath);
        args.put("line", 6);    // 0-based; "    public static class NestedPayload {"
        args.put("column", 24); // the "NestedPayload" identifier

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "expected success; error: "
            + (r.getError() != null ? r.getError().getCode() + " " + r.getError().getMessage() : "n/a"));

        Map<String, Object> data = getData(r);
        assertEquals("NestedPayload", data.get("typeName"));
        assertEquals("com.example.ipo.NestHolder", data.get("fromType"));

        // Moving NestedPayload to a top-level file in the same package edits exactly
        // one existing file (NestHolder, to drop the nested declaration) with exactly
        // one edit; the `new NestedPayload()` reference in use() stays valid same-package
        // and needs no rewrite. A dropped or extra enclosing edit changes these counts.
        assertEquals(1, ((Number) data.get("filesAffected")).intValue(),
            "only NestHolder is edited; got: " + data);
        assertEquals(1, ((Number) data.get("totalEdits")).intValue(),
            "exactly one edit (removing the nested declaration); got: " + data);

        List<Map<String, String>> createdFiles = (List<Map<String, String>>) data.get("createdFiles");
        assertEquals(1, createdFiles.size(),
            "exactly one new file for the extracted type; got: " + createdFiles);
        String content = createdFiles.get(0).get("content");
        assertTrue(content.contains("class NestedPayload") && content.contains("weight"),
            "new file must declare NestedPayload with its members; got:\n" + content);

        Map<String, List<Map<String, Object>>> editsByFile =
            (Map<String, List<Map<String, Object>>>) data.get("editsByFile");
        String holderNew = editsByFile.entrySet().stream()
            .filter(e -> e.getKey().endsWith("NestHolder.java"))
            .flatMap(e -> e.getValue().stream())
            .map(e -> String.valueOf(e.get("newText")))
            .reduce("", String::concat);
        assertFalse(holderNew.contains("class NestedPayload"),
            "NestHolder must lose the nested declaration; got: " + holderNew);
    }

    @Test
    @DisplayName("a top-level type is refused")
    void topLevelType_refused() {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", holderPath);
        args.put("line", 4);    // 0-based; "public class NestHolder {"
        args.put("column", 13); // the "NestHolder" identifier
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(),
            "a top-level type must be refused; got: " + r.getData());
        assertEquals("INVALID_PARAMETER", r.getError().getCode());
    }

    @Test
    @DisplayName("non-type position and missing params are rejected")
    void invalidInputs_rejected() {
        ObjectNode wrongPos = mapper.createObjectNode();
        wrongPos.put("filePath", holderPath);
        wrongPos.put("line", 0);
        wrongPos.put("column", 0);
        assertFalse(tool.execute(wrongPos).isSuccess());

        ObjectNode noFile = mapper.createObjectNode();
        noFile.put("line", 6);
        noFile.put("column", 24);
        assertFalse(tool.execute(noFile).isSuccess());
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: moving NestedPayload yields one created file and one edit")
    void envelope_moveNestedPayload_exactCounts() {
        ObjectNode args = envelope.args();
        args.put("filePath", holderPath);
        args.put("line", 6);
        args.put("column", 24);
        JsonNode payload = envelope.assertEnvelopeFidelity("move_type_to_new_file", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "move_type_to_new_file failed through the envelope: " + payload);
        JsonNode data = payload.get("data");
        assertEquals("NestedPayload", data.get("typeName").asText());
        assertEquals(1, data.get("filesAffected").asInt(),
            "only NestHolder is edited — count must survive the envelope");
        assertEquals(1, data.get("totalEdits").asInt(),
            "exactly one edit — count must survive the envelope");
        assertEquals(1, data.get("createdFiles").size(),
            "exactly one created file through the envelope");
    }
}
