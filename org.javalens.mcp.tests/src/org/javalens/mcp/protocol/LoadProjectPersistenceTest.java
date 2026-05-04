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

        // Step 2: analyze_method on the same handler instance
        Path calcFile = projectPath.resolve("src/main/java/com/example/Calculator.java");
        String analyzeRequest = String.format("""
            {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                "name":"analyze_method",
                "arguments":{"filePath":"%s","line":10,"column":15}
            }}
            """, calcFile.toString().replace("\\", "\\\\"));

        String analyzeResponse = handler.processMessage(analyzeRequest);
        assertNotNull(analyzeResponse);

        JsonNode analyzeJson = objectMapper.readTree(analyzeResponse);
        String analyzeText = analyzeJson.get("result").get("content").get(0).get("text").asText();
        JsonNode analyzeResult = objectMapper.readTree(analyzeText);

        // The critical assertion: PROJECT_NOT_LOADED must not appear
        if (analyzeResult.has("error") && analyzeResult.get("error").has("code")) {
            String errorCode = analyzeResult.get("error").get("code").asText();
            assertNotEquals("PROJECT_NOT_LOADED", errorCode,
                "analyze_method should not return PROJECT_NOT_LOADED after load_project succeeded. " +
                "Full response: " + analyzeText);
        }
    }

}
