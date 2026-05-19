package org.javalens.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javalens.core.IJdtService;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.AnalyzeMethodTool;
import org.javalens.mcp.tools.LoadProjectTool;
import org.javalens.mcp.tools.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reproduces issue #4: load_project succeeds but analyze_method returns PROJECT_NOT_LOADED.
 *
 * Tests that when load_project stores the JdtService, subsequent tools can retrieve
 * it via the Supplier pattern used across all tools.
 */
class LoadProjectPersistenceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ToolRegistry toolRegistry;
    private McpProtocolHandler handler;
    private ObjectMapper objectMapper;
    private volatile IJdtService sharedService;
    private Path projectPath;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        toolRegistry = new ToolRegistry();
        handler = new McpProtocolHandler(toolRegistry);
        sharedService = null;

        // Replicate JavaLensApplication's registration pattern
        toolRegistry.register(new LoadProjectTool(service -> this.sharedService = service));
        toolRegistry.register(new AnalyzeMethodTool(() -> this.sharedService));

        projectPath = helper.getFixturePath("simple-maven");
    }

    @Test
    @DisplayName("analyze_method should not return PROJECT_NOT_LOADED after successful load_project")
    void analyzeMethodPersistsAfterLoadProject() throws Exception {
        // Step 1: load_project via MCP protocol (exactly as a client would)
        String loadRequest = String.format("""
            {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
                "name":"load_project",
                "arguments":{"projectPath":"%s"}
            }}
            """, projectPath.toString().replace("\\", "\\\\"));

        String loadResponse = handler.processMessage(loadRequest);
        assertNotNull(loadResponse);

        JsonNode loadJson = objectMapper.readTree(loadResponse);
        assertNull(loadJson.get("error"), "load_project should not return error");
        String loadText = loadJson.get("result").get("content").get(0).get("text").asText();
        JsonNode loadResult = objectMapper.readTree(loadText);
        assertTrue(loadResult.get("success").asBoolean(), "load_project must succeed");

        // Verify shared service was set
        assertNotNull(sharedService, "shared service should be set after load_project");

        // Step 2: analyze_method on the same handler instance.
        // Position 14:16 (0-based) lands on the `add` method name in
        // `    public int add(int a, int b) {` (Read line 15). Previous test used 10:15
        // which is inside the Javadoc — the tool returned INVALID_PARAMETER ("Position
        // is not on a method") and the not-PROJECT_NOT_LOADED assertion silently passed
        // without proving the shared-service wiring works for a successful invocation.
        Path calcFile = projectPath.resolve("src/main/java/com/example/Calculator.java");
        String analyzeRequest = String.format("""
            {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                "name":"analyze_method",
                "arguments":{"filePath":"%s","line":14,"column":16}
            }}
            """, calcFile.toString().replace("\\", "\\\\"));

        String analyzeResponse = handler.processMessage(analyzeRequest);
        assertNotNull(analyzeResponse);

        JsonNode analyzeJson = objectMapper.readTree(analyzeResponse);
        String analyzeText = analyzeJson.get("result").get("content").get(0).get("text").asText();
        JsonNode analyzeResult = objectMapper.readTree(analyzeText);

        // Strict: analyze_method MUST succeed. Previously the assertion was only
        // "not PROJECT_NOT_LOADED", so any other error code (e.g. INVALID_COORDINATES,
        // FILE_NOT_FOUND) would have silently passed — a regression that broke service
        // wiring AND coincidentally surfaced a different error would have been missed.
        assertTrue(analyzeResult.get("success").asBoolean(),
            "analyze_method must succeed after load_project. Response: " + analyzeText);
        // Belt-and-suspenders: error field absent too.
        assertTrue(!analyzeResult.has("error")
                || analyzeResult.get("error").isNull(),
            "On success, error field must be null/absent. Response: " + analyzeText);
    }

}
