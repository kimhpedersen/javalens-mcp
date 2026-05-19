package org.javalens.core;

import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.javalens.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins ElementKindResolver: every emitted kind is lowercase / camelCase and never
 * mixes capitalization with TypeKindResolver. Regression catches the B-6 pattern
 * where element-kind ("Method", "Field") and type-kind ("class", "interface") were
 * emitted into the same field with inconsistent casing.
 */
class ElementKindResolverTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
    }

    @Test
    @DisplayName("Method element resolves to lowercase 'method'")
    void method_isLowercase() throws Exception {
        IType calculator = service.findType("com.example.Calculator");
        assertNotNull(calculator);
        IMethod add = null;
        for (IMethod m : calculator.getMethods()) {
            if ("add".equals(m.getElementName())) {
                add = m;
                break;
            }
        }
        assertNotNull(add, "Calculator.add must exist");
        assertEquals("method", ElementKindResolver.kindOf(add));
    }

    @Test
    @DisplayName("Constructor element resolves to lowercase 'constructor'")
    void constructor_isLowercase() throws Exception {
        // HelloWorld declares a constructor.
        IType helloWorld = service.findType("com.example.HelloWorld");
        assertNotNull(helloWorld);
        IMethod ctor = null;
        for (IMethod m : helloWorld.getMethods()) {
            if (m.isConstructor()) {
                ctor = m;
                break;
            }
        }
        assertNotNull(ctor, "HelloWorld must declare a constructor");
        assertEquals("constructor", ElementKindResolver.kindOf(ctor));
    }

    @Test
    @DisplayName("Regular field resolves to lowercase 'field'")
    void regularField_isLowercase() throws Exception {
        IType calculator = service.findType("com.example.Calculator");
        IField lastResult = calculator.getField("lastResult");
        assertNotNull(lastResult);
        assertTrue(lastResult.exists());
        assertEquals("field", ElementKindResolver.kindOf(lastResult));
    }

    @Test
    @DisplayName("Static final field is classified as 'constant' (not 'field')")
    void staticFinalField_isConstant() throws Exception {
        // RefactoringTarget.MAX_SIZE is static final.
        IType refactoringTarget = service.findType("com.example.RefactoringTarget");
        IField maxSize = refactoringTarget.getField("MAX_SIZE");
        assertNotNull(maxSize);
        assertTrue(maxSize.exists(), "RefactoringTarget.MAX_SIZE must exist");
        assertEquals("constant", ElementKindResolver.kindOf(maxSize),
            "Static final field must classify as constant; got: " + ElementKindResolver.kindOf(maxSize));
    }

    @Test
    @DisplayName("Enum constant is classified as 'enumConstant'")
    void enumConstant_isEnumConstantKind() throws Exception {
        // TypeKindsFixture.Color has enum constants.
        IType color = service.findType("com.example.TypeKindsFixture.Color");
        if (color == null) {
            // Try with the $-form.
            color = service.findType("com.example.TypeKindsFixture$Color");
        }
        assertNotNull(color, "TypeKindsFixture.Color enum must exist");
        IField[] fields = color.getFields();
        IField first = null;
        for (IField f : fields) {
            if (f.isEnumConstant()) {
                first = f;
                break;
            }
        }
        assertNotNull(first, "TypeKindsFixture.Color must have at least one enum constant");
        assertEquals("enumConstant", ElementKindResolver.kindOf(first));
    }

    @Test
    @DisplayName("Type elements delegate to TypeKindResolver (class/interface/enum/record/annotation)")
    void type_delegatesToTypeKindResolver() throws Exception {
        IType calc = service.findType("com.example.Calculator");
        assertEquals("class", ElementKindResolver.kindOf(calc));
        IType shape = service.findType("com.example.IShape");
        assertEquals("interface", ElementKindResolver.kindOf(shape));
    }

    @Test
    @DisplayName("All emitted values are lowercase / camelCase (no leading uppercase letter)")
    void allValuesAreLowercase() throws Exception {
        // Sample the full enumeration of elements we can resolve; assert none start uppercase.
        IType calc = service.findType("com.example.Calculator");
        String[] samples = {
            ElementKindResolver.kindOf(calc),
            ElementKindResolver.kindOf(calc.getMethods()[0]),
            ElementKindResolver.kindOf(calc.getField("lastResult")),
            ElementKindResolver.fieldKindOf(calc.getField("lastResult"))
        };
        for (String s : samples) {
            assertNotNull(s);
            char c = s.charAt(0);
            assertTrue(Character.isLowerCase(c),
                "kind value must start lowercase; got: '" + s + "' (first char: '" + c + "')");
        }
    }
}
