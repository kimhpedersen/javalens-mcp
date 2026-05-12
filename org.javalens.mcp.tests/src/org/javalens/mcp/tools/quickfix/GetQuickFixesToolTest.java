package org.javalens.mcp.tools.quickfix;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetQuickFixesTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for GetQuickFixesTool.
 * Tests getting available quick fixes for problems at positions.
 */
class GetQuickFixesToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GetQuickFixesTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetQuickFixesTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getFixes(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("fixes");
    }

    @Test
    @DisplayName("Calculator line 5 (class declaration, no problems): problemCount=0 and fixes is empty")
    void cleanLine_problemAndFixesAreExactlyEmpty() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 5);

        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        assertEquals(5, data.get("line"));
        // Calculator.java compiles cleanly and line 5 is the class declaration; the tool
        // must report exactly zero problems and zero fixes — not just "non-null".
        assertEquals(0, ((Number) data.get("problemCount")).intValue(),
            "Calculator line 5 has no problems; got: " + data.get("problemCount"));
        @SuppressWarnings("unchecked")
        List<?> problems = (List<?>) data.get("problems");
        assertEquals(0, problems.size(),
            "problems list must be exactly empty when problemCount=0; got: " + problems);
        List<Map<String, Object>> fixes = getFixes(data);
        assertEquals(0, fixes.size(),
            "fixes list must be exactly empty when no problems are at the line; got: " + fixes);
    }

    @Test
    @DisplayName("works with optional column parameter")
    void worksWithColumnParameter() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 5);
        args.put("column", 10);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertNotNull(data.get("fixes"));
    }

    @Test
    @DisplayName("requires filePath parameter")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("line", 0);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertNotNull(response.getError());
    }

    @Test
    @DisplayName("requires line parameter")
    void requiresLine() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertNotNull(response.getError());
    }

    @Test
    @DisplayName("handles non-existent file")
    void handlesNonExistentFile() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", projectPath.resolve("NonExistent.java").toString());
        args.put("line", 0);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertNotNull(response.getError());
    }

    // ========== Coverage gap (deferred) ==========
    //
    // The tool's getDescription promises fixes for four IProblem kinds:
    // UndefinedType, UnusedImport, UnhandledException, ImportNotFound. None of those
    // can be triggered against the existing fixtures with default JDT settings:
    //
    // - UndefinedType / UndefinedName / ImportNotFound / UnhandledException are compile
    //   errors; a file containing them would break javac and the Maven build.
    // - UnusedImport: empirically, our JDT setup leaves this at "ignore", so reconcile
    //   does not surface it as an IProblem (verified — problemCount stays 0 on
    //   RefactoringTarget's known-unused imports).
    //
    // Exercising the fix-generation path therefore requires either (a) configuring the
    // test project's JDT compiler options to flag unused-imports as warnings, or
    // (b) using ICompilationUnit working-copy editing to inject in-memory problems that
    // never reach javac. Both are scoped as their own follow-up — they're tool/test
    // infrastructure changes, not coverage-writing.
}
