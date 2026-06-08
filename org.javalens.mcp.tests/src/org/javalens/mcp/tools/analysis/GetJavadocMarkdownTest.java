package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetJavadocTool;
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
 * Pins get_javadoc parsing of JEP 467 markdown documentation comments ({@code ///}),
 * standard since Java 23. The summary and tags must be extracted with the same
 * fidelity as a traditional {@code /** *}{@code /} comment — the {@code ///} line
 * markers must not leak into the parsed text.
 */
class GetJavadocMarkdownTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GetJavadocTool tool;
    private String markdownDocPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("java25-maven");
        tool = new GetJavadocTool(() -> service);
        markdownDocPath = helper.getFixturePath("java25-maven")
            .resolve("src/main/java/com/example/MarkdownDoc.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test
    @DisplayName("markdown (///) Javadoc: summary and tags parse without /// markers leaking")
    void markdownJavadoc_parsesSummaryAndTags() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", markdownDocPath);
        args.put("line", 9);    // 0-based; file line 10 = `public int add(int a, int b)`
        args.put("column", 15); // the `add` identifier

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "expected success; got: " + r.getData());

        Map<String, Object> data = getData(r);
        assertEquals("add", data.get("symbol"));
        assertEquals(Boolean.TRUE, data.get("hasDocumentation"),
            "markdown-documented method must report hasDocumentation=true; got: " + data);

        assertEquals("Adds two numbers and returns the sum.", data.get("summary"),
            "summary must be the markdown text with no /// markers; got: " + data.get("summary"));

        List<Map<String, String>> params = (List<Map<String, String>>) data.get("params");
        assertNotNull(params, "params must be parsed from markdown @param tags; got: " + data);
        assertEquals(2, params.size(), "expected @param a and @param b; got: " + params);
        assertEquals("a", params.get(0).get("name"));
        assertEquals("the first addend", params.get(0).get("description"));
        assertEquals("b", params.get(1).get("name"));
        assertEquals("the second addend", params.get(1).get("description"));

        String returns = (String) data.get("returns");
        assertNotNull(returns, "returns must be parsed from the markdown @return tag; got: " + data);
        assertTrue(returns.contains("sum"), "returns must carry the @return text; got: " + returns);
    }
}
