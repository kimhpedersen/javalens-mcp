package org.javalens.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.OrganizeImportsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins organize_imports handling of JEP 511 module imports.
 *
 * <p>A module import ({@code import module java.base;}) brings in a whole
 * module's exported packages; organize_imports cannot know which unqualified
 * types resolve through it, so it must always be preserved verbatim — never
 * dropped as "unused" and never rewritten into an ordinary wildcard import
 * {@code import java.base.*;}. The ordinary {@code import java.util.Map;} in the
 * same file (used by {@code counts()}) must survive too.
 */
class OrganizeImportsModuleImportTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private OrganizeImportsTool tool;
    private String moduleImportDemoPath;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("java25-maven");
        tool = new OrganizeImportsTool(() -> service);
        moduleImportDemoPath = helper.getFixturePath("java25-maven")
            .resolve("src/main/java/com/example/ModuleImportDemo.java").toString();
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test
    @DisplayName("module import is preserved verbatim, not dropped or rewritten as a wildcard")
    void moduleImport_preserved() {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", moduleImportDemoPath);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "expected success; got: " + r.getData());

        Map<String, Object> data = getData(r);
        String block = (String) data.get("organizedImportBlock");
        assertTrue(block.contains("import module java.base;"),
            "module import must be preserved verbatim; got block:\n" + block);
        assertFalse(block.contains("import java.base.*;"),
            "module import must not be rewritten into a wildcard import; got block:\n" + block);
        assertTrue(block.contains("import java.util.Map;"),
            "the used ordinary import must be preserved; got block:\n" + block);
    }
}
