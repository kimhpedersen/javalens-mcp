package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.AnalyzeControlFlowTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins analyze_control_flow on a JEP 513 flexible constructor body: the
 * statements that appear before the explicit {@code super()} call (here an
 * {@code if} guard that throws) must be analyzed like any other body content,
 * not skipped because they precede the constructor delegation.
 */
class AnalyzeControlFlowFlexibleCtorTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private AnalyzeControlFlowTool tool;
    private String flexibleCtorPath;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("java25-maven");
        tool = new AnalyzeControlFlowTool(() -> service);
        flexibleCtorPath = helper.getFixturePath("java25-maven")
            .resolve("src/main/java/com/example/FlexibleCtorDemo.java").toString();
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test
    @DisplayName("pre-super if/throw in a flexible constructor body is counted")
    @SuppressWarnings("unchecked")
    void flexibleCtor_preSuperStatementsCounted() {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", flexibleCtorPath);
        args.put("line", 11);   // 0-based; file line 12 = "public FlexibleCtorDemo(int v) {"
        args.put("column", 11); // the constructor name

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "expected success; got: " + r.getData());

        Map<String, Object> data = getData(r);
        assertEquals("FlexibleCtorDemo", data.get("method"),
            "the enclosing element is the constructor; got: " + data);
        assertEquals(1, ((Number) data.get("branches")).intValue(),
            "the pre-super if guard is one branch; got: " + data);

        List<Map<String, Object>> throwPoints = (List<Map<String, Object>>) data.get("throwPoints");
        assertEquals(1, throwPoints.size(),
            "the pre-super throw must be counted as a throw point; got: " + throwPoints);
    }
}
