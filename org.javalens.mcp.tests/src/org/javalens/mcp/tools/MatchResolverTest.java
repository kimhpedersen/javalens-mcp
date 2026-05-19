package org.javalens.mcp.tools;

import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class MatchResolverTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
    }

    // ========== fromElement branches ==========

    @Test
    @DisplayName("fromElement(IType) returns the type's CU")
    void fromElement_iType_returnsCu() {
        IType calculator = service.findType("com.example.Calculator");
        assertNotNull(calculator);
        ICompilationUnit cu = MatchResolver.fromElement(calculator);
        assertNotNull(cu, "IType.getCompilationUnit() must return a CU for source types");
        assertEquals("Calculator.java", cu.getElementName());
    }

    @Test
    @DisplayName("fromElement(IMethod) returns the enclosing CU")
    void fromElement_iMethod_returnsCu() throws Exception {
        IType calculator = service.findType("com.example.Calculator");
        IMethod[] methods = calculator.getMethods();
        assertFalse(methods.length == 0, "Calculator must declare at least one method");
        ICompilationUnit cu = MatchResolver.fromElement(methods[0]);
        assertNotNull(cu);
        assertEquals("Calculator.java", cu.getElementName());
    }

    @Test
    @DisplayName("fromElement(null) returns null")
    void fromElement_null_returnsNull() {
        assertNull(MatchResolver.fromElement(null));
    }

    @Test
    @DisplayName("fromElement(arbitrary object) returns null")
    void fromElement_nonJavaElement_returnsNull() {
        assertNull(MatchResolver.fromElement("not-a-java-element"));
    }

    // ========== fromIFile branch ==========

    @Test
    @DisplayName("fromIFile(.java IFile) returns CU")
    void fromIFile_javaFile_returnsCu() {
        IType calculator = service.findType("com.example.Calculator");
        IFile file = (IFile) calculator.getResource();
        assertNotNull(file);
        ICompilationUnit cu = MatchResolver.fromIFile(file);
        assertNotNull(cu, "JavaCore.create(.java IFile) must return a CU");
        assertEquals("Calculator.java", cu.getElementName());
    }

    @Test
    @DisplayName("fromIFile(non-IFile object) returns null")
    void fromIFile_nonIFile_returnsNull() {
        assertNull(MatchResolver.fromIFile("not-a-file"));
    }

    @Test
    @DisplayName("fromIFile(null) returns null")
    void fromIFile_null_returnsNull() {
        assertNull(MatchResolver.fromIFile(null));
    }

    @Test
    @DisplayName("fromIFile(IFile with non-java extension) returns null")
    void fromIFile_nonJavaExtension_returnsNull() {
        // Source filters on `equalsIgnoreCase('java')`. Previously the only non-Java test
        // passed a String — that exercises the type-check branch but NOT the
        // extension-check branch. Pass a real IFile pointing at a non-Java resource.
        // Use simple-maven's pom.xml as a known non-Java file in the workspace.
        org.eclipse.core.resources.IWorkspaceRoot root =
            org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot();
        // Find any project member with a pom.xml — fixtures all do.
        org.eclipse.core.resources.IProject[] projects = root.getProjects();
        IFile pomFile = null;
        for (org.eclipse.core.resources.IProject p : projects) {
            org.eclipse.core.resources.IResource pom = p.findMember("pom.xml");
            if (pom instanceof IFile) {
                pomFile = (IFile) pom;
                break;
            }
        }
        if (pomFile == null) {
            // Skip if no pom.xml accessible — extension-branch coverage requires it.
            return;
        }
        assertEquals("xml", pomFile.getFileExtension(),
            "Expected pom.xml extension to be 'xml' for the branch test");
        assertNull(MatchResolver.fromIFile(pomFile),
            "fromIFile must return null for an IFile whose extension is not 'java'; "
                + "got non-null from: " + pomFile);
    }

    // ========== resolveCu integration via real SearchMatch ==========

    @Test
    @DisplayName("resolveCu(null) returns null — guard branch")
    void resolveCu_null_returnsNull() {
        assertNull(MatchResolver.resolveCu(null));
    }

    @Test
    @DisplayName("resolveCu(type-declaration SearchMatch) resolves CU via fromElement")
    void resolveCu_typeDeclaration_resolvesCu() throws Exception {
        // SearchEngine returns a SearchMatch whose element is the IType for a
        // TYPE+DECLARATIONS search. resolveCu must take the IType branch.
        List<SearchMatch> matches = searchTypeDeclarations("com.example.Calculator");
        assertEquals(1, matches.size(),
            "Calculator must produce exactly one TYPE+DECLARATIONS match; got: " + matches);
        ICompilationUnit cu = MatchResolver.resolveCu(matches.get(0));
        assertNotNull(cu);
        assertEquals("Calculator.java", cu.getElementName());
    }

    @Test
    @DisplayName("resolveCu(method-declaration SearchMatch) resolves CU for source-defined methods")
    void resolveCu_methodDeclaration_resolvesCu() throws Exception {
        // METHOD+DECLARATIONS yields matches whose elements are IMethod
        // instances; binary JDK methods (e.g., java.util.ArrayList.add) return
        // null CU by design, so we look for at least one source match for
        // Calculator.add and verify resolveCu succeeds for it.
        List<SearchMatch> matches = searchMethodDeclarations("add");
        assertFalse(matches.isEmpty(),
            "'add' method declaration must produce at least one match");
        boolean foundSourceMatchWithCu = false;
        for (SearchMatch m : matches) {
            ICompilationUnit cu = MatchResolver.resolveCu(m);
            if (cu != null && "Calculator.java".equals(cu.getElementName())) {
                foundSourceMatchWithCu = true;
                break;
            }
        }
        assertEquals(true, foundSourceMatchWithCu,
            "Calculator.add must produce a source SearchMatch whose CU resolves to Calculator.java");
    }

    @Test
    @DisplayName("resolveCu(type-reference SearchMatch) resolves CU — exercises the TypeReferenceMatch path")
    void resolveCu_typeReference_resolvesCu() throws Exception {
        // TYPE+REFERENCES yields TypeReferenceMatch instances; resolveCu must
        // succeed via either the enclosing-element branch or the localElement
        // branch, depending on JDT internals.
        List<SearchMatch> matches = searchTypeReferences("com.example.Calculator");
        assertFalse(matches.isEmpty(),
            "Calculator type-references must produce at least one match");
        for (SearchMatch m : matches) {
            ICompilationUnit cu = MatchResolver.resolveCu(m);
            assertNotNull(cu,
                "Every TypeReferenceMatch for a source type must resolve to a CU; offender: " + m);
        }
    }

    // ========== search helpers ==========

    private List<SearchMatch> searchTypeDeclarations(String typeName) throws Exception {
        SearchPattern pattern = SearchPattern.createPattern(
            typeName,
            IJavaSearchConstants.TYPE,
            IJavaSearchConstants.DECLARATIONS,
            SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE);
        return runSearch(pattern);
    }

    private List<SearchMatch> searchMethodDeclarations(String methodName) throws Exception {
        SearchPattern pattern = SearchPattern.createPattern(
            methodName,
            IJavaSearchConstants.METHOD,
            IJavaSearchConstants.DECLARATIONS,
            SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE);
        return runSearch(pattern);
    }

    private List<SearchMatch> searchTypeReferences(String typeName) throws Exception {
        SearchPattern pattern = SearchPattern.createPattern(
            typeName,
            IJavaSearchConstants.TYPE,
            IJavaSearchConstants.REFERENCES,
            SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE);
        return runSearch(pattern);
    }

    private List<SearchMatch> runSearch(SearchPattern pattern) throws Exception {
        List<SearchMatch> matches = new ArrayList<>();
        new SearchEngine().search(
            pattern,
            new org.eclipse.jdt.core.search.SearchParticipant[] {
                SearchEngine.getDefaultSearchParticipant() },
            service.getSearchService().getScope(),
            new SearchRequestor() {
                @Override
                public void acceptSearchMatch(SearchMatch match) {
                    matches.add(match);
                }
            },
            new NullProgressMonitor());
        return matches;
    }
}
