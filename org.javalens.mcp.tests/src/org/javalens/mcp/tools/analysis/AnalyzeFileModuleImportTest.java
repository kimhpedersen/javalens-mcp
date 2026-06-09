package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.AnalyzeFileTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins analyze_file reporting of JEP 511 module imports.
 *
 * <p>The JDT model surfaces {@code import module java.base;} as an on-demand
 * {@code IImportDeclaration} with element name {@code "java.base.*"} — identical
 * in shape to an ordinary wildcard import {@code import java.base.*;}. analyze_file
 * must distinguish them: a module import is flagged {@code module=true}, named by
 * its module ({@code java.base}, not {@code java.base.*}), and is not reported as
 * {@code onDemand}. The fixture's ordinary {@code import java.util.Map;} pins the
 * negative side ({@code module=false}).
 */
class AnalyzeFileModuleImportTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private AnalyzeFileTool tool;
    private String moduleImportDemoPath;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("java25-maven");
        tool = new AnalyzeFileTool(() -> service);
        moduleImportDemoPath = helper.getFixturePath("java25-maven")
            .resolve("src/main/java/com/example/ModuleImportDemo.java").toString();
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    private Map<String, Object> importNamed(List<Map<String, Object>> imports, String name) {
        return imports.stream()
            .filter(i -> name.equals(i.get("name")))
            .findFirst().orElse(null);
    }

    @Test
    @DisplayName("module import flagged module=true, not onDemand; ordinary import flagged module=false")
    @SuppressWarnings("unchecked")
    void moduleImport_flaggedDistinctly() {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", moduleImportDemoPath);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "expected success; got: " + r.getData());

        Map<String, Object> data = getData(r);
        List<Map<String, Object>> imports = (List<Map<String, Object>>) data.get("imports");
        assertEquals(2, ((Number) data.get("importCount")).intValue(),
            "ModuleImportDemo has exactly two imports; got: " + imports);

        Map<String, Object> moduleImp = importNamed(imports, "java.base");
        assertNotNull(moduleImp,
            "module import must be named by its module 'java.base', not 'java.base.*'; got: " + imports);
        assertEquals(Boolean.TRUE, moduleImp.get("module"), "module import must be flagged module=true");
        assertEquals(Boolean.FALSE, moduleImp.get("onDemand"),
            "a module import is not an on-demand wildcard import");
        assertEquals(Boolean.FALSE, moduleImp.get("static"), "a module import is not static");

        Map<String, Object> ordinaryImp = importNamed(imports, "java.util.Map");
        assertNotNull(ordinaryImp, "ordinary import java.util.Map must be present; got: " + imports);
        assertEquals(Boolean.FALSE, ordinaryImp.get("module"), "ordinary import must be flagged module=false");
        assertEquals(Boolean.FALSE, ordinaryImp.get("onDemand"));
        assertEquals(Boolean.FALSE, ordinaryImp.get("static"));
    }
}
