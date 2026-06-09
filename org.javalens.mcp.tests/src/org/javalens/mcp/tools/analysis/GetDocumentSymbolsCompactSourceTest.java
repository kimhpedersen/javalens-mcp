package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetDocumentSymbolsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins get_document_symbols on a JEP 512 compact source file: the implicitly
 * declared class and its members must appear in the symbol tree. The tool
 * enumerates via the JDT model ({@code cu.getTypes()}), which surfaces the
 * implicit class, so this characterizes that behavior with content assertions.
 */
class GetDocumentSymbolsCompactSourceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GetDocumentSymbolsTool tool;
    private String compactMainPath;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("java25-maven");
        tool = new GetDocumentSymbolsTool(() -> service);
        compactMainPath = helper.getFixturePath("java25-maven")
            .resolve("src/main/java/CompactMain.java").toString();
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test
    @DisplayName("compact source file: implicit class and its members appear in the symbol tree")
    @SuppressWarnings("unchecked")
    void compactSource_implicitClassInSymbolTree() {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", compactMainPath);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "expected success; got: " + r.getData());

        Map<String, Object> data = getData(r);
        List<Map<String, Object>> symbols = (List<Map<String, Object>>) data.get("symbols");
        assertEquals(1, symbols.size(), "exactly the implicit class at top level; got: " + symbols);

        Map<String, Object> implicit = symbols.get(0);
        assertEquals("CompactMain", implicit.get("name"),
            "implicit class symbol is named after the file; got: " + implicit);

        List<Map<String, Object>> children = (List<Map<String, Object>>) implicit.get("children");
        assertNotNull(children, "implicit class must expose its members as children; got: " + implicit);
        List<String> childNames = children.stream().map(c -> (String) c.get("name")).toList();
        assertTrue(childNames.contains("greeting")
                && childNames.contains("main")
                && childNames.contains("message"),
            "children must include greeting, main, message; got: " + childNames);
    }
}
