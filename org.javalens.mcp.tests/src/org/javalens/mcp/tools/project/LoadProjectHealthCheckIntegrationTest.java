package org.javalens.mcp.tools.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.mcp.JavaLensApplication;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.Tool;
import org.javalens.mcp.tools.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the load_project -> health_check state seam against the REAL
 * {@code JavaLensApplication.registerTools()} wiring. The earlier protocol
 * tests used replica registrations (a test-local callback that only stores
 * the service), so they structurally could not catch the production wiring
 * bug where loading a project via the tool left health_check reporting "not
 * loaded" (the loading state was flipped only by the JAVA_PROJECT_PATH
 * auto-load path). This test exercises the actual registered tools.
 */
class LoadProjectHealthCheckIntegrationTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ToolRegistry registry;
    private ObjectMapper objectMapper;
    private Path projectPath;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");

        // Build the app and run its REAL registerTools() so the load_project
        // tool carries its production callback (the place the bug lived).
        JavaLensApplication app = new JavaLensApplication();
        Field registryField = JavaLensApplication.class.getDeclaredField("toolRegistry");
        registryField.setAccessible(true);
        ToolRegistry r = new ToolRegistry();
        registryField.set(app, r);
        Method registerTools = JavaLensApplication.class.getDeclaredMethod("registerTools");
        registerTools.setAccessible(true);
        registerTools.invoke(app);
        registry = r;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    private ToolResponse call(String toolName, ObjectNode args) {
        Tool tool = registry.getTool(toolName).orElseThrow();
        return tool.execute(args);
    }

    @Test
    @DisplayName("health_check reports not-loaded before any load")
    void healthCheck_beforeLoad_notLoaded() {
        ToolResponse health = call("health_check", objectMapper.createObjectNode());
        assertTrue(health.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> project = (Map<String, Object>) getData(health).get("project");
        assertEquals(false, project.get("loaded"));
    }

    @Test
    @DisplayName("health_check reports LOADED after a successful load_project tool call")
    void healthCheck_afterToolLoad_loaded() {
        ObjectNode loadArgs = objectMapper.createObjectNode();
        loadArgs.put("projectPath", projectPath.toString());
        ToolResponse load = call("load_project", loadArgs);
        assertTrue(load.isSuccess(), () -> "load_project failed: " + load.getError());

        ToolResponse health = call("health_check", objectMapper.createObjectNode());
        assertTrue(health.isSuccess());
        Map<String, Object> data = getData(health);
        @SuppressWarnings("unchecked")
        Map<String, Object> project = (Map<String, Object>) data.get("project");

        assertEquals(true, project.get("loaded"),
            "loading a project via the tool must flip health_check to loaded (issue #30 thread)");
        assertEquals("Ready", data.get("status"));
        assertEquals("loaded", project.get("status"));
        assertEquals("strict", data.get("diskSync"), "the live service's disk-sync mode is reported");
    }
}
