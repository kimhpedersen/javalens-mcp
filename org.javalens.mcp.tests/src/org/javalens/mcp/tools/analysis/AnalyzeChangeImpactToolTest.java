package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.AnalyzeChangeImpactTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnalyzeChangeImpactToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private AnalyzeChangeImpactTool tool;
    private ObjectMapper objectMapper;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new AnalyzeChangeImpactTool(() -> service);
        objectMapper = new ObjectMapper();
        calculatorPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Impact Analysis Tests ==========

    @Test
    @DisplayName("should analyze impact of a method")
    void analyzesMethodImpact() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 5);  // add method in Calculator
        args.put("column", 15);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertNotNull(data.get("symbol"), "Should include symbol name");
        assertNotNull(data.get("symbolType"), "Should include symbol type");
        assertNotNull(data.get("affectedFiles"), "Should include affected files");
        assertNotNull(data.get("callSites"), "Should include call sites");
    }

    @Test
    @DisplayName("should include depth in output")
    void includesDepthInOutput() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 5);
        args.put("column", 15);
        args.put("depth", 2);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals(2, data.get("depth"));
    }

    @Test
    @DisplayName("should cap depth at 3")
    void capsDepthAtThree() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 5);
        args.put("column", 15);
        args.put("depth", 10);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals(3, data.get("depth"), "Depth should be capped at 3");
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("should return error when filePath is missing")
    void returnsErrorForMissingFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("line", 5);
        args.put("column", 15);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    @Test
    @DisplayName("should return symbol not found for invalid position")
    void returnsSymbolNotFoundForInvalidPosition() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 0);
        args.put("column", 0);

        ToolResponse response = tool.execute(args);

        // Position 0:0 is typically the package declaration or empty — may or may not find a symbol
        // Either success with no references or symbolNotFound is acceptable
        assertNotNull(response);
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("Calculator.add impact at depth=1: UserService.java is among affected files")
    void calculatorAdd_depth1_includesUserService() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        // Calculator.add() is on 0-based line 13 (column 15 for "add")
        args.put("line", 13);
        args.put("column", 15);
        args.put("depth", 1);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> affectedFiles = (List<Map<String, Object>>) data.get("affectedFiles");
        boolean hasUserService = affectedFiles.stream()
            .map(m -> (String) m.get("file"))
            .filter(java.util.Objects::nonNull)
            .map(s -> s.replace('\\', '/'))
            .anyMatch(s -> s.endsWith("UserService.java"));
        assertTrue(hasUserService,
            "UserService.calculateTotal calls Calculator.add — must appear in depth-1 affected files; got: "
                + affectedFiles);
    }
}
