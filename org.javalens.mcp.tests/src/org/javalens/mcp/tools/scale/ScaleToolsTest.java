package org.javalens.mcp.tools.scale;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.ScaleFixtureGenerator;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindImplementationsTool;
import org.javalens.mcp.tools.FindReferencesTool;
import org.javalens.mcp.tools.FindUnusedCodeTool;
import org.javalens.mcp.tools.RenameSymbolTool;
import org.javalens.mcp.tools.SearchSymbolsTool;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Curated scale-sensitivity tests for tools known to degrade under JDT index pressure.
 *
 * <p>The fixture has 1 hub class declaring {@code public int counter} plus 1000 leaf
 * classes spread across 100 packages, each leaf reading and writing {@code Hub.counter}
 * via a {@code touch(Hub h)} method. Every leaf also implements a project-wide
 * {@code Marker} interface, giving {@code find_implementations} a 1000-element result
 * to surface. Tests assert specific result counts (not "result is non-null") so any
 * scale-driven regression in JDT binding resolution surfaces as a hard failure.
 */
class ScaleToolsTest {

    private static JdtServiceImpl service;
    private static Path scaleRoot;
    private static ObjectMapper objectMapper;

    @BeforeAll
    static void setUpFixture() throws Exception {
        scaleRoot = ScaleFixtureGenerator.getOrCreate();
        service = new JdtServiceImpl();
        service.loadProject(scaleRoot);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    private static String hubPath() {
        return scaleRoot.resolve("src/main/java/com/example/scale/hub/Hub.java").toString();
    }

    private static String markerPath() {
        return scaleRoot.resolve("src/main/java/com/example/scale/Marker.java").toString();
    }

    @Test
    @DisplayName("rename_symbol on Hub.counter produces an edit per read AND per write across all 1000 leaf classes")
    @SuppressWarnings("unchecked")
    void renameSymbol_atScale_producesEditPerLeafReference() {
        RenameSymbolTool tool = new RenameSymbolTool(() -> service);
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", hubPath());
        args.put("line", 3);    // 0-based; file line 4 is `    public int counter;`
        args.put("column", 15); // on `counter`
        args.put("newName", "tally");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "rename_symbol must succeed at scale; got: "
            + (r.getError() != null ? r.getError().getMessage() : "n/a"));

        Map<String, Object> d = data(r);
        Number total = (Number) d.get("totalEdits");
        assertNotNull(total, "totalEdits must be reported; got: " + d);
        // 1 declaration in Hub.java + (1 read + 1 write) per leaf = 1 + 2 * 1000 = 2001.
        // Some implementations may count read-then-write as separate edits; assert
        // at least 2001 to pin the cross-file coverage without over-specifying.
        assertTrue(total.intValue() >= 2001,
            "Expected at least 2001 edits across Hub + 1000 leafs; got: " + total);
    }

    @Test
    @DisplayName("find_references on Hub.counter returns at least 2000 cross-package usages")
    @SuppressWarnings("unchecked")
    void findReferences_atScale_returnsAllLeafUsages() {
        FindReferencesTool tool = new FindReferencesTool(() -> service);
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", hubPath());
        args.put("line", 3);
        args.put("column", 15);
        args.put("maxResults", 1000); // tool's safety cap; default 100 truncates

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "find_references must succeed at scale; got: "
            + (r.getError() != null ? r.getError().getMessage() : "n/a"));

        Map<String, Object> d = data(r);
        List<Map<String, Object>> refs = (List<Map<String, Object>>) d.get("locations");
        assertNotNull(refs, "locations list must be present");
        Number total = (Number) d.get("totalCount");
        assertNotNull(total, "totalCount must be present");
        // Every leaf has `h.counter = h.counter + 1` — one read + one write = 2 references.
        // 2 * 1000 = 2000 expected from leaves; Hub's `return counter;` adds one more = 2001.
        assertEquals(2001, total.intValue(),
            "Expected 2001 total references: 1 Hub.read + 2 * 1000 leafs");
        // The locations list is bounded by maxResults (capped at 1000). totalCount is
        // the unbounded count and is the real scale assertion.
        assertEquals(1000, refs.size(),
            "Locations list must fill the maxResults=1000 cap; got: " + refs.size());
    }

    @Test
    @DisplayName("find_implementations on Marker returns all 1000 leaf classes")
    @SuppressWarnings("unchecked")
    void findImplementations_atScale_returnsAllLeafImplementers() {
        FindImplementationsTool tool = new FindImplementationsTool(() -> service);
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", markerPath());
        args.put("line", 2);    // 0-based; file line 3 is `public interface Marker {`
        args.put("column", 17); // on `Marker`
        args.put("maxResults", 1000); // tool's safety cap; default 100 truncates leaves

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "find_implementations must succeed at scale; got: "
            + (r.getError() != null ? r.getError().getMessage() : "n/a"));

        Map<String, Object> d = data(r);
        List<Map<String, Object>> impls = (List<Map<String, Object>>) d.get("implementations");
        assertNotNull(impls, "implementations list must be present; got: " + d);
        assertEquals(ScaleFixtureGenerator.LEAF_CLASS_COUNT, impls.size(),
            "Marker is implemented by every leaf class; expected exactly "
                + ScaleFixtureGenerator.LEAF_CLASS_COUNT + " implementers");
    }

    @Test
    @DisplayName("search_symbols with `Class*` matches at least 1000 leaf class symbols")
    @SuppressWarnings("unchecked")
    void searchSymbols_atScale_findsAllLeafClasses() {
        SearchSymbolsTool tool = new SearchSymbolsTool(() -> service);
        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "Class*");
        args.put("kind", "class");
        args.put("maxResults", 1000); // tool's safety cap; default 50

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "search_symbols must succeed at scale; got: "
            + (r.getError() != null ? r.getError().getMessage() : "n/a"));

        Map<String, Object> d = data(r);
        List<Map<String, Object>> syms = (List<Map<String, Object>>) d.get("results");
        assertNotNull(syms, "results list must be present");
        long classMatches = syms.stream()
            .map(s -> (String) s.get("name"))
            .filter(n -> n != null && n.startsWith("Class"))
            .count();
        assertEquals(ScaleFixtureGenerator.LEAF_CLASS_COUNT, classMatches,
            "Expected exactly " + ScaleFixtureGenerator.LEAF_CLASS_COUNT
                + " `Class*` matches at the 1000 cap");
    }

    @Test
    @DisplayName("find_unused_code on Hub.java reports no unused private members (hub has no unused privates)")
    @SuppressWarnings("unchecked")
    void findUnusedCode_atScale_completesAndReportsNoneOnHub() {
        FindUnusedCodeTool tool = new FindUnusedCodeTool(() -> service);
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", hubPath());

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "find_unused_code must succeed at scale; got: "
            + (r.getError() != null ? r.getError().getMessage() : "n/a"));

        Map<String, Object> d = data(r);
        List<Map<String, Object>> unused = (List<Map<String, Object>>) d.get("unusedItems");
        assertNotNull(unused, "unusedItems list must be present");
        // Hub has only public members. The contract is: report private unused members.
        // None exist; list must be empty.
        assertEquals(0, unused.size(),
            "Hub has no private members; unusedItems must be empty; got: " + unused);
    }
}
