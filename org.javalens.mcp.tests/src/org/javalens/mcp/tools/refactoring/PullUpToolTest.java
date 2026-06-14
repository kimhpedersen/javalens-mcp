package org.javalens.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.PullUpTool;
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
 * Pins pull_up: HierChild.liftable() is pulled into HierBase — the base file
 * gains the method, the child file loses it, both via returned edits only.
 */
class PullUpToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private PullUpTool tool;
    private EnvelopeHarness envelope;
    private String childPath;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("java25-maven");
        tool = new PullUpTool(() -> service);
        envelope = new EnvelopeHarness(service);
        childPath = helper.getFixturePath("java25-maven")
            .resolve("src/main/java/com/example/hier/HierChild.java").toString();
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    private ObjectNode liftableArgs() {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", childPath);
        args.put("line", 6);    // 0-based; file line 7 = "    public int liftable() {"
        args.put("column", 15); // the "liftable" identifier
        return args;
    }

    @Test
    @DisplayName("pulling liftable() up edits both HierBase (gains it) and HierChild (loses it)")
    @SuppressWarnings("unchecked")
    void pullUp_movesMethodToSuperclass() {
        ToolResponse r = tool.execute(liftableArgs());
        assertTrue(r.isSuccess(), "expected success; error: "
            + (r.getError() != null ? r.getError().getCode() + " " + r.getError().getMessage() : "n/a"));

        Map<String, Object> data = getData(r);
        assertEquals("liftable", data.get("memberName"));
        assertEquals("com.example.hier.HierChild", data.get("fromType"));
        assertEquals("com.example.hier.HierBase", data.get("toType"));

        Map<String, List<Map<String, Object>>> editsByFile =
            (Map<String, List<Map<String, Object>>>) data.get("editsByFile");
        assertEquals(2, editsByFile.size(),
            "both HierBase and HierChild must receive edits; got: " + editsByFile.keySet());

        assertEquals(2, ((Number) data.get("totalEdits")).intValue());
        assertEquals(2, ((Number) data.get("filesAffected")).intValue());

        // HierBase gains the pulled-up method, tab-indented (generated), appended.
        assertEquals("\n\tpublic int liftable() {\n\t    return 42;\n\t}\n",
            textFor(editsByFile, "HierBase.java", "newText").replace("\r\n", "\n"));
        // HierChild loses it: the original 4-space method is the deleted oldText; the
        // replacement newText is empty.
        assertEquals("\n    public int liftable() {\n        return 42;\n    }\n",
            textFor(editsByFile, "HierChild.java", "oldText").replace("\r\n", "\n"));
        assertEquals("", textFor(editsByFile, "HierChild.java", "newText"));
    }

    private String textFor(Map<String, List<Map<String, Object>>> editsByFile,
                           String fileSuffix, String key) {
        return editsByFile.entrySet().stream()
            .filter(e -> e.getKey().endsWith(fileSuffix))
            .flatMap(e -> e.getValue().stream())
            .map(e -> String.valueOf(e.get(key)))
            .reduce("", String::concat);
    }

    @Test
    @DisplayName("a member of a class without a project superclass is refused")
    void noProjectSuperclass_refused() {
        // HierBase extends Object only.
        String basePath = helper.getFixturePath("java25-maven")
            .resolve("src/main/java/com/example/hier/HierBase.java").toString();
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", basePath);
        args.put("line", 5);    // 0-based; "    public int baseValue() {"
        args.put("column", 15);
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(),
            "pulling from a class whose superclass is Object must be refused; got: " + r.getData());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, r.getError().getCode());
        assertEquals("Invalid parameter 'member': Declaring type has no project superclass to pull into",
            r.getError().getMessage());
    }

    @Test
    @DisplayName("non-member position is refused; missing params rejected")
    void invalidInputs_rejected() {
        ObjectNode wrongPos = mapper.createObjectNode();
        wrongPos.put("filePath", childPath);
        wrongPos.put("line", 0);
        wrongPos.put("column", 0);
        ToolResponse wrongPosResp = tool.execute(wrongPos);
        assertFalse(wrongPosResp.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, wrongPosResp.getError().getCode());
        assertEquals("Invalid parameter 'position': No method or field at position",
            wrongPosResp.getError().getMessage());

        ObjectNode noFile = mapper.createObjectNode();
        noFile.put("line", 6);
        noFile.put("column", 15);
        ToolResponse noFileResp = tool.execute(noFile);
        assertFalse(noFileResp.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, noFileResp.getError().getCode());
        assertEquals("Invalid parameter 'filePath': Required", noFileResp.getError().getMessage());
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: pulling liftable() up adds it to HierBase and removes it from HierChild")
    void envelope_pullUp_movesMethodToSuperclass() {
        ObjectNode args = envelope.args();
        args.put("filePath", childPath);
        args.put("line", 6);    // 0-based; "    public int liftable() {"
        args.put("column", 15);
        JsonNode payload = envelope.assertEnvelopeFidelity("pull_up", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "pull_up failed through the envelope: " + payload);
        JsonNode data = payload.get("data");
        assertEquals("liftable", data.get("memberName").asText());
        assertEquals("com.example.hier.HierChild", data.get("fromType").asText());
        assertEquals("com.example.hier.HierBase", data.get("toType").asText());
        JsonNode editsByFile = data.get("editsByFile");
        assertEquals(2, editsByFile.size(),
            "both HierBase and HierChild must receive edits through the envelope; got: " + editsByFile);
        StringBuilder baseNew = new StringBuilder();
        StringBuilder childOld = new StringBuilder();
        StringBuilder childNew = new StringBuilder();
        java.util.Iterator<String> files = editsByFile.fieldNames();
        while (files.hasNext()) {
            String file = files.next();
            for (JsonNode edit : editsByFile.get(file)) {
                if (file.endsWith("HierBase.java")) baseNew.append(edit.path("newText").asText());
                else if (file.endsWith("HierChild.java")) {
                    childOld.append(edit.path("oldText").asText());
                    childNew.append(edit.path("newText").asText());
                }
            }
        }
        assertTrue(baseNew.toString().contains("liftable"),
            "HierBase must gain liftable() through the envelope; got: " + baseNew);
        assertTrue(childOld.toString().contains("liftable") && !childNew.toString().contains("liftable"),
            "HierChild must lose liftable() through the envelope; old: " + childOld + " new: " + childNew);
    }
}
