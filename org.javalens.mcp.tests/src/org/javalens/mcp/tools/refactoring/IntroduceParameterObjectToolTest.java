package org.javalens.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.IntroduceParameterObjectTool;
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
 * Pins introduce_parameter_object: send(host, port, secure) gains a
 * SendParameters bundle, and the in-file caller is rewritten to construct it.
 */
class IntroduceParameterObjectToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private IntroduceParameterObjectTool tool;
    private String targetPath;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("java25-maven");
        tool = new IntroduceParameterObjectTool(() -> service);
        targetPath = helper.getFixturePath("java25-maven")
            .resolve("src/main/java/com/example/ipo/ParamBundleTarget.java").toString();
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test
    @DisplayName("bundling send()'s parameters generates the class and rewrites the caller")
    @SuppressWarnings("unchecked")
    void introduceParameterObject_bundlesAndRewrites() {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", targetPath);
        args.put("line", 6);    // 0-based; "    public String send(String host, int port, boolean secure) {"
        args.put("column", 18); // the "send" identifier

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "expected success; error: "
            + (r.getError() != null ? r.getError().getCode() + " " + r.getError().getMessage() : "n/a"));

        Map<String, Object> data = getData(r);
        assertEquals("send", data.get("methodName"));
        assertEquals("SendParameters", data.get("className"));

        Map<String, List<Map<String, Object>>> editsByFile =
            (Map<String, List<Map<String, Object>>>) data.get("editsByFile");
        String allNewText = editsByFile.values().stream()
            .flatMap(List::stream)
            .map(e -> String.valueOf(e.get("newText")))
            .reduce("", String::concat);
        assertTrue(allNewText.contains("SendParameters"),
            "edits must introduce the SendParameters class/usages; got: " + allNewText);
        assertTrue(allNewText.contains("new SendParameters"),
            "the caller must construct the bundle; got: " + allNewText);
    }

    @Test
    @DisplayName("a method with no parameters is refused")
    void zeroParamMethod_refused() {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", targetPath);
        args.put("line", 10);   // 0-based; "    public String sendDefault() {"
        args.put("column", 18);
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(),
            "a method without parameters must be refused; got: " + r.getData());
    }

    @Test
    @DisplayName("non-method position and missing params are rejected")
    void invalidInputs_rejected() {
        ObjectNode wrongPos = mapper.createObjectNode();
        wrongPos.put("filePath", targetPath);
        wrongPos.put("line", 0);
        wrongPos.put("column", 0);
        assertFalse(tool.execute(wrongPos).isSuccess());

        ObjectNode noFile = mapper.createObjectNode();
        noFile.put("line", 6);
        noFile.put("column", 18);
        assertFalse(tool.execute(noFile).isSuccess());
    }
}
