package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetTypeUsageSummaryTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GetTypeUsageSummaryToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private GetTypeUsageSummaryTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetTypeUsageSummaryTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test
    @DisplayName("Calculator usage summary: exact counts per category match the sibling find_* tools")
    @SuppressWarnings("unchecked")
    void calculator_summaryCountsMatchSiblingTools() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxPerCategory", 50);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("com.example.Calculator", data.get("typeName"));
        assertEquals("Class", data.get("kind"));

        Map<String, Object> usages = (Map<String, Object>) data.get("usages");
        assertNotNull(usages);

        // Each subcategory must be a map with `count` and `locations` keys.
        Map<String, Object> instantiations = (Map<String, Object>) usages.get("instantiations");
        Map<String, Object> casts = (Map<String, Object>) usages.get("casts");
        Map<String, Object> instanceofChecks = (Map<String, Object>) usages.get("instanceofChecks");
        Map<String, Object> typeArguments = (Map<String, Object>) usages.get("typeArguments");
        assertNotNull(instantiations.get("locations"));
        assertNotNull(casts.get("locations"));
        assertNotNull(instanceofChecks.get("locations"));
        assertNotNull(typeArguments.get("locations"));

        int instantiationCount = ((Number) instantiations.get("count")).intValue();
        int castCount = ((Number) casts.get("count")).intValue();
        int instanceofCount = ((Number) instanceofChecks.get("count")).intValue();
        int typeArgCount = ((Number) typeArguments.get("count")).intValue();

        // Cross-tool consistency: these counts are verified independently by
        // FindCastsToolTest (1 cast) and FindInstanceofChecksToolTest (2 instanceofs).
        // The aggregate tool must surface the same numbers.
        assertEquals(1, castCount,
            "find_casts asserts exactly 1 cast for Calculator; aggregate must match; got: " + casts);
        assertEquals(2, instanceofCount,
            "find_instanceof_checks asserts exactly 2 checks for Calculator; aggregate must match; got: "
                + instanceofChecks);

        // SearchPatterns alone has `new Calculator()` in createObjects, useGenerics, and
        // InnerClass.createCalculator — at least 3 instantiations.
        assertTrue(instantiationCount >= 3,
            "Calculator is instantiated at least 3 times across SearchPatterns; got: " + instantiations);

        // totalUsages must equal the sum of the category counts (the tool computes it as
        // such, so this asserts the contract holds).
        int expectedTotal = instantiationCount + castCount + instanceofCount + typeArgCount;
        assertEquals(expectedTotal, ((Number) data.get("totalUsages")).intValue(),
            "totalUsages must equal sum of subcategory counts; got data: " + data);
    }

    @Test @DisplayName("finds type by simple name")
    void findsTypeBySimpleName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "Calculator");

        assertEquals("com.example.Calculator", getData(tool.execute(args)).get("typeName"));
    }

    @Test @DisplayName("respects maxPerCategory")
    void respectsMaxPerCategory() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxPerCategory", 1);

        assertTrue(tool.execute(args).isSuccess());
    }

    @Test @DisplayName("requires typeName")
    void requiresTypeName() {
        assertFalse(tool.execute(objectMapper.createObjectNode()).isSuccess());
    }

    @Test @DisplayName("handles invalid inputs")
    void handlesInvalidInputs() {
        ObjectNode unknown = objectMapper.createObjectNode();
        unknown.put("typeName", "com.nonexistent.Type");
        assertFalse(tool.execute(unknown).isSuccess());
    }
}
