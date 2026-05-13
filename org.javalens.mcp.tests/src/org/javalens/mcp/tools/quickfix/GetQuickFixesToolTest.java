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

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("RefactoringTarget line 3 (unused `import java.util.ArrayList;`): offers a remove_import fix")
    void unusedImport_offersRemoveImportFix() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", projectPath.resolve("src/main/java/com/example/RefactoringTarget.java").toString());
        // RefactoringTarget.java 1-based line 4 `import java.util.ArrayList;` -> 0-based 3.
        // ProjectImporter enables COMPILER_PB_UNUSED_IMPORT=WARNING so JDT surfaces this
        // as an IProblem and the tool's documented UnusedImport -> remove_import fix path
        // is reachable.
        args.put("line", 3);

        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        int problemCount = ((Number) data.get("problemCount")).intValue();
        assertTrue(problemCount > 0,
            "RefactoringTarget line 3 is an unused import; with the unused-import "
                + "compiler option enabled JDT must report at least one IProblem. Data: " + data);

        List<Map<String, Object>> fixes = getFixes(data);
        boolean hasRemoveImport = fixes.stream()
            .map(f -> (String) f.get("fixId"))
            .filter(java.util.Objects::nonNull)
            .anyMatch(id -> id.startsWith("remove_import:"));
        assertTrue(hasRemoveImport,
            "get_quick_fixes promises a remove_import fix for UnusedImport problems; got fixes: "
                + fixes);

        // The remove_import fix must carry the IMPORT category and a label.
        Map<String, Object> removeImportFix = fixes.stream()
            .filter(f -> {
                String id = (String) f.get("fixId");
                return id != null && id.startsWith("remove_import:");
            })
            .findFirst()
            .orElseThrow();
        assertNotNull(removeImportFix.get("label"));
        assertEquals("IMPORT", removeImportFix.get("category"),
            "remove_import fixes must be categorized as IMPORT; got: " + removeImportFix);
    }
}
