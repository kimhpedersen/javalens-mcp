package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindTestsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindTestsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private FindTestsTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindTestsTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("finds tests comprehensively")
    void findsTestsComprehensively() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        // Counts
        assertNotNull(data.get("testClassCount"));
        assertNotNull(data.get("testMethodCount"));

        // Test classes structure
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> testClasses = (List<Map<String, Object>>) data.get("testClasses");
        assertNotNull(testClasses);

        if (!testClasses.isEmpty()) {
            Map<String, Object> tc = testClasses.get(0);
            assertNotNull(tc.get("className"));
            assertNotNull(tc.get("filePath"));
            assertNotNull(tc.get("testMethods"));
        }
    }

    @Test @DisplayName("supports filtering options")
    void supportsFilteringOptions() {
        // Pattern filter
        ObjectNode withPattern = objectMapper.createObjectNode();
        withPattern.put("pattern", "*Test");
        assertTrue(tool.execute(withPattern).isSuccess());

        // Include disabled
        ObjectNode withDisabled = objectMapper.createObjectNode();
        withDisabled.put("includeDisabled", true);
        assertTrue(tool.execute(withDisabled).isSuccess());
    }

    @Test @DisplayName("returns success with no parameters")
    void returnsSuccessWithNoParameters() {
        assertTrue(tool.execute(objectMapper.createObjectNode()).isSuccess());
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("SampleTest is found with its @Test-annotated methods (excluding @Disabled by default)")
    void sampleTest_foundWithKnownTestMethods() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> testClasses = (List<Map<String, Object>>) getData(r).get("testClasses");

        Map<String, Object> sampleTest = testClasses.stream()
            .filter(tc -> {
                Object cn = tc.get("className");
                return cn != null && cn.toString().endsWith("SampleTest");
            })
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "SampleTest must be detected; got: " +
                    testClasses.stream().map(tc -> tc.get("className")).toList()));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> testMethods = (List<Map<String, Object>>) sampleTest.get("testMethods");
        java.util.Set<String> methodNames = testMethods.stream()
            .map(tm -> (String) tm.get("name"))
            .collect(java.util.stream.Collectors.toSet());

        // SampleTest declares: testAddition, testSubtraction, testMultiplication,
        // testDivision (disabled), anotherDisabledTest (disabled), testWithCustomDisplayName.
        // Non-test helpers (helperMethod, privateHelper) must NOT appear.
        assertTrue(methodNames.contains("testAddition"));
        assertTrue(methodNames.contains("testSubtraction"));
        assertTrue(methodNames.contains("testMultiplication"));
        assertTrue(methodNames.contains("testWithCustomDisplayName"));
        assertFalse(methodNames.contains("helperMethod"),
            "helperMethod has no @Test annotation; must not appear");
        assertFalse(methodNames.contains("privateHelper"),
            "privateHelper is not a test; must not appear");
    }

    @Test
    @DisplayName("includeDisabled=true surfaces @Disabled test methods")
    void includeDisabled_surfacesDisabledMethods() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("includeDisabled", true);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> testClasses = (List<Map<String, Object>>) getData(r).get("testClasses");
        java.util.Set<String> allMethodNames = testClasses.stream()
            .filter(tc -> {
                Object cn = tc.get("className");
                return cn != null && cn.toString().endsWith("SampleTest");
            })
            .flatMap(tc -> {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> methods = (List<Map<String, Object>>) tc.get("testMethods");
                return methods.stream();
            })
            .map(m -> (String) m.get("name"))
            .collect(java.util.stream.Collectors.toSet());

        assertTrue(allMethodNames.contains("testDivision"),
            "testDivision (@Disabled) must appear when includeDisabled=true; got: " + allMethodNames);
    }
}
