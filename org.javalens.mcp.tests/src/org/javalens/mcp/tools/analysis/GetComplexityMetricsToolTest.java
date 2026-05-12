package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetComplexityMetricsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GetComplexityMetricsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private GetComplexityMetricsTool tool;
    private ObjectMapper objectMapper;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetComplexityMetricsTool(() -> service);
        objectMapper = new ObjectMapper();
        calculatorPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("calculates metrics comprehensively")
    void calculatesMetricsComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        // File metrics
        @SuppressWarnings("unchecked")
        Map<String, Object> file = (Map<String, Object>) data.get("file");
        assertNotNull(file.get("path"));
        assertTrue((Integer) file.get("physicalLOC") > 0);

        // Summary
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) data.get("summary");
        assertNotNull(summary.get("totalCyclomaticComplexity"));
        assertNotNull(summary.get("totalCognitiveComplexity"));
        assertNotNull(summary.get("methodCount"));

        // Risk assessment
        @SuppressWarnings("unchecked")
        Map<String, Object> risk = (Map<String, Object>) data.get("riskAssessment");
        assertNotNull(risk.get("highRiskMethods"));
        assertNotNull(risk.get("lowRiskMethods"));

        // Method details included by default
        assertNotNull(data.get("methods"));
    }

    @Test @DisplayName("respects includeDetails option")
    void respectsIncludeDetailsOption() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("includeDetails", false);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        assertNull(getData(r).get("methods"));
    }

    @Test @DisplayName("requires filePath")
    void requiresFilePath() {
        assertFalse(tool.execute(objectMapper.createObjectNode()).isSuccess());
    }

    @Test @DisplayName("handles invalid inputs")
    void handlesInvalidInputs() {
        ObjectNode badPath = objectMapper.createObjectNode();
        badPath.put("filePath", "/nonexistent/File.java");
        assertFalse(tool.execute(badPath).isSuccess());

        ObjectNode emptyPath = objectMapper.createObjectNode();
        emptyPath.put("filePath", "");
        assertFalse(tool.execute(emptyPath).isSuccess());
    }

    // ========== Semantic-grade tests (CC boundaries from ComplexityBoundaries) ==========

    @Test
    @DisplayName("ComplexityBoundaries: cc01=1, cc05=5, cc06=6, cc10=10, cc11=11 cyclomatic complexity")
    void complexityBoundaries_cyclomaticAtKnownValues() {
        String boundariesPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/ComplexityBoundaries.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", boundariesPath);
        args.put("includeDetails", true);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> methods = (List<Map<String, Object>>) getData(r).get("methods");
        Map<String, Integer> ccByName = new java.util.HashMap<>();
        for (Map<String, Object> m : methods) {
            String name = (String) m.get("name");
            Object cc = m.get("cyclomaticComplexity");
            if (cc instanceof Number n) {
                ccByName.put(name, n.intValue());
            }
        }

        assertEquals(1, (int) ccByName.getOrDefault("cc01", -1),
            "cc01 (no decisions) must have CC=1; got: " + ccByName);
        assertEquals(5, (int) ccByName.getOrDefault("cc05", -1),
            "cc05 (4 if statements) must have CC=5; got: " + ccByName);
        assertEquals(6, (int) ccByName.getOrDefault("cc06", -1),
            "cc06 (5 if statements) must have CC=6; got: " + ccByName);
        assertEquals(10, (int) ccByName.getOrDefault("cc10", -1),
            "cc10 (9 if statements) must have CC=10; got: " + ccByName);
        assertEquals(11, (int) ccByName.getOrDefault("cc11", -1),
            "cc11 (10 if statements) must have CC=11; got: " + ccByName);
    }
}
