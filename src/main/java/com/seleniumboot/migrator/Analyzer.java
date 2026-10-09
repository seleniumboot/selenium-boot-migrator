package com.seleniumboot.migrator;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.seleniumboot.migrator.Finding.Status.AUTO;
import static com.seleniumboot.migrator.Finding.Status.MANUAL;

/** Read-only static analysis: parses Java sources and reports patterns that map onto Selenium Boot. */
public final class Analyzer {

    private static final Set<String> DRIVER_LIFECYCLE_ANNOTATIONS = Set.of(
            "BeforeMethod", "AfterMethod", "BeforeClass", "AfterClass", "BeforeTest", "AfterTest",
            "BeforeSuite", "AfterSuite", "Before", "After", "BeforeEach", "AfterEach", "BeforeAll", "AfterAll");

    private final JavaParser parser = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_25));

    public Report analyze(Path root) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.walk(root)) {
            files = s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        List<Finding> findings = new ArrayList<>();
        LocatorStats locatorStats = new LocatorStats();
        int parsed = 0;
        List<String> unparsable = new ArrayList<>();
        for (Path f : files) {
            var result = parser.parse(f);
            if (result.getResult().isEmpty() || !result.isSuccessful()) {
                unparsable.add(root.relativize(f).toString());
                continue;
            }
            parsed++;
            scan(result.getResult().get(), root.relativize(f).toString(), findings, locatorStats);
        }
        List<String> detectedTechnologies = BuildFileAnalyzer.detect(root);
        return new Report(files.size(), parsed, unparsable, findings, detectedTechnologies,
                recognizedTechnologies(detectedTechnologies), locatorStats.counts());
    }

    /** Analyze a single pasted source string. */
    public Report analyzeSource(String source) {
        var result = parser.parse(source);
        List<Finding> findings = new ArrayList<>();
        LocatorStats locatorStats = new LocatorStats();
        if (result.getResult().isEmpty() || !result.isSuccessful()) {
            return new Report(1, 0, List.of("<pasted>"), findings);
        }
        scan(result.getResult().get(), "<pasted>", findings, locatorStats);
        return new Report(1, 1, List.of(), findings, List.of(), List.of(), locatorStats.counts());
    }

    private void scan(CompilationUnit cu, String file, List<Finding> out, LocatorStats locatorStats) {
        cu.findAll(MethodCallExpr.class).forEach(locatorStats::scan);

        // MIG-010: page-object candidates with a WebDriver constructor
        cu.findAll(ClassOrInterfaceDeclaration.class).stream()
            .filter(c -> !c.isInterface())
            .filter(c -> c.getConstructors().stream().anyMatch(Analyzer::hasWebDriverParameter))
            .forEach(c -> out.add(new Finding("MIG-010", MANUAL, file, line(c), c.getNameAsString(),
                "Review as a page object; Selenium Boot documents extending BasePage.")));
        // MIG-011: @FindBy fields
        cu.findAll(FieldDeclaration.class).forEach(fd -> {
            if (fd.getAnnotations().stream().anyMatch(a -> isAnnotation(a.getNameAsString(), "FindBy"))) {
            fd.getVariables().forEach(v -> out.add(new Finding("MIG-011", MANUAL, file, line(fd),
                v.getNameAsString(),
                "Selenium Boot documents By locator fields, not @FindBy; review before mapping to BasePage.")));
            }
        });
        // MIG-012: PageFactory initialization
        cu.findAll(MethodCallExpr.class).stream()
                .filter(m -> m.getNameAsString().equals("initElements"))
                .filter(m -> m.getScope().map(s -> s.toString().endsWith("PageFactory")).orElse(false))
                .forEach(m -> out.add(new Finding("MIG-012", MANUAL, file, line(m), "PageFactory.initElements(...)",
                        "PageFactory is not documented as a Selenium Boot pattern; review page initialization.")));
        // MIG-001: ThreadLocal<WebDriver> driver factory
        cu.findAll(FieldDeclaration.class).forEach(fd -> {
            String type = fd.getElementType().toString();
            if (type.startsWith("ThreadLocal<") && type.contains("WebDriver")) {
                out.add(new Finding("MIG-001", AUTO, file, line(fd), "ThreadLocal<WebDriver>",
                        "Delete the driver factory; extend BaseTest (per-thread isolation is built in)."));
            }
        });
        // MIG-002: WebDriverManager.*.setup()
        cu.findAll(NameExpr.class).stream()
                .filter(n -> n.getNameAsString().equals("WebDriverManager"))
                .forEach(n -> out.add(new Finding("MIG-002", AUTO, file, line(n), "WebDriverManager",
                        "Delete; Selenium Manager fetches drivers automatically.")));
        // MIG-003: WebDriverWait / ExpectedConditions
        cu.findAll(ExpressionStmt.class).stream()
                .filter(Analyzer::containsLegacyWait)
                .forEach(statement -> out.add(new Finding("MIG-003", MANUAL, file, line(statement),
                        "WebDriverWait / ExpectedConditions",
                        "Use $(locator) auto-wait or getWait(); review the condition by hand.")));
        // MIG-004: retry analyzer / annotation transformer
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(c -> {
            boolean retry = c.getImplementedTypes().stream()
                    .anyMatch(t -> t.getNameAsString().equals("IRetryAnalyzer")
                            || t.getNameAsString().equals("IAnnotationTransformer"));
            if (retry) {
                out.add(new Finding("MIG-004", AUTO, file, line(c), c.getNameAsString(),
                        "Delete; set retry.enabled in selenium-boot.yml or use @Retryable."));
            }
            // MIG-005: screenshot-on-failure listener
            boolean listener = c.getImplementedTypes().stream().anyMatch(t -> t.getNameAsString().equals("ITestListener"));
            boolean shots = c.findAll(NameExpr.class).stream().anyMatch(n -> n.getNameAsString().equals("TakesScreenshot"))
                    || c.toString().contains("TakesScreenshot");
            if (listener && shots) {
                out.add(new Finding("MIG-005", AUTO, file, line(c), c.getNameAsString(),
                        "Delete; failure screenshots are captured automatically."));
            }
        });
        // MIG-014: hard-coded sleeps, MIG-016: implicit waits (flag only)
        cu.findAll(MethodCallExpr.class).forEach(m -> {
            if (m.getNameAsString().equals("sleep") && m.getScope().map(s -> s.toString().equals("Thread")).orElse(false)) {
                out.add(new Finding("MIG-014", MANUAL, file, line(m), "Thread.sleep(...)",
                        "Replace with an auto-waiting locator or getWait()."));
            }
            if (m.getNameAsString().equals("implicitlyWait")) {
                out.add(new Finding("MIG-016", MANUAL, file, line(m), "implicitlyWait(...)",
                        "Remove; mixing implicit and explicit waits causes flakiness."));
            }
        });
        // MIG-015: custom DriverManager (flag only)
        cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(c -> c.getNameAsString().endsWith("DriverManager") || c.getNameAsString().endsWith("DriverFactory"))
                .forEach(c -> out.add(new Finding("MIG-015", MANUAL, file, line(c), c.getNameAsString(),
                        "Custom driver lifecycle: review, then replace with BaseTest.")));
        // MIG-017: driver lifecycle managed in TestNG or JUnit setup/teardown methods
        cu.findAll(MethodDeclaration.class).stream()
                .filter(this::isDriverLifecycleMethod)
            .forEach(method -> {
                boolean lifecycleOnly = method.getBody().orElseThrow().getStatements().stream()
                    .allMatch(this::isLifecycleGlueStatement);
                out.add(new Finding("MIG-017", lifecycleOnly ? AUTO : MANUAL, file, line(method),
                        "Driver setup/teardown in @" + lifecycleAnnotation(method) + " " + method.getNameAsString() + "()",
                lifecycleOnly
                    ? "Delete the lifecycle glue; extend BaseTest. Driver creation, per-thread isolation, and teardown are handled for you."
                    : "Remove the driver setup; keep the rest."));
            });
    }

    private boolean isDriverLifecycleMethod(MethodDeclaration method) {
        if (lifecycleAnnotation(method) == null || method.getBody().isEmpty()) return false;
        var body = method.getBody().get();
        boolean createsDriver = body.findAll(ObjectCreationExpr.class).stream()
                .anyMatch(creation -> creation.getType().getNameAsString().endsWith("Driver"));
        boolean quitsDriver = body.findAll(MethodCallExpr.class).stream()
            .anyMatch(this::isDriverQuit);
        return createsDriver || quitsDriver;
    }

        private boolean isLifecycleGlueStatement(Statement statement) {
        if (!(statement instanceof ExpressionStmt expressionStatement)) return false;
        Expression expression = expressionStatement.getExpression();
        if (expression instanceof MethodCallExpr call) return isDriverQuit(call);
        if (expression instanceof AssignExpr assignment) return isDriverVariable(assignment.getTarget());
        if (expression instanceof VariableDeclarationExpr declaration) {
            return declaration.getVariables().stream().allMatch(variable ->
                variable.getNameAsString().equals("driver")
                    && variable.getInitializer().filter(ObjectCreationExpr.class::isInstance)
                        .map(ObjectCreationExpr.class::cast)
                        .map(creation -> creation.getType().getNameAsString().endsWith("Driver"))
                        .orElse(false));
        }
        return expression instanceof ObjectCreationExpr creation
            && creation.getType().getNameAsString().endsWith("Driver");
        }

        private boolean isDriverQuit(MethodCallExpr call) {
        return call.getNameAsString().equals("quit")
            && call.getScope().filter(this::isDriverVariable).isPresent();
        }

        private boolean isDriverVariable(Expression expression) {
        if (expression instanceof NameExpr name) return name.getNameAsString().equals("driver");
        return expression instanceof FieldAccessExpr field
            && field.getNameAsString().equals("driver")
            && field.getScope() instanceof ThisExpr;
        }

    private String lifecycleAnnotation(MethodDeclaration method) {
        return method.getAnnotations().stream()
                .map(annotation -> annotation.getName().getIdentifier())
                .filter(DRIVER_LIFECYCLE_ANNOTATIONS::contains)
                .findFirst()
                .orElse(null);
    }

    private static boolean containsLegacyWait(ExpressionStmt statement) {
        boolean webDriverWait = statement.findAll(ObjectCreationExpr.class).stream()
                .anyMatch(creation -> creation.getType().getNameAsString().equals("WebDriverWait"));
        boolean expectedConditions = statement.findAll(NameExpr.class).stream()
                .anyMatch(name -> name.getNameAsString().equals("ExpectedConditions"));
        return webDriverWait || expectedConditions;
    }

    private static int line(Node n) {
        return n.getBegin().map(p -> p.line).orElse(0);
    }

    private static boolean hasWebDriverParameter(ConstructorDeclaration constructor) {
        return constructor.getParameters().stream()
                .anyMatch(p -> isWebDriverType(p.getType().asString()));
    }

    private static boolean isWebDriverType(String type) {
        return type.equals("WebDriver") || type.endsWith(".WebDriver");
    }

    private static boolean isAnnotation(String name, String simpleName) {
        return name.equals(simpleName) || name.endsWith("." + simpleName);
    }

    private static List<String> recognizedTechnologies(List<String> detectedTechnologies) {
        boolean testNg = false;
        boolean junit4 = false;
        boolean junit5 = false;
        boolean webDriverManager = false;
        boolean extentReports = false;
        boolean allure = false;
        String seleniumVersion = null;

        for (String detected : detectedTechnologies) {
            if (!detected.startsWith("Dependency: ")) continue;

            String[] coordinates = detected.substring("Dependency: ".length()).split(":", -1);
            if (coordinates.length < 2) continue;

            String groupId = coordinates[0];
            String artifactId = coordinates[1];
            String version = coordinates.length > 2 ? coordinates[2] : "";

            if (groupId.equals("org.seleniumhq.selenium") && artifactId.equals("selenium-java")) {
                seleniumVersion = version;
            } else if (groupId.equals("org.testng") && artifactId.equals("testng")) {
                testNg = true;
            } else if (groupId.equals("junit") && artifactId.equals("junit")) {
                junit4 = true;
            } else if (groupId.equals("org.junit.jupiter")) {
                junit5 = true;
            } else if (groupId.equals("io.github.bonigarcia") && artifactId.equals("webdrivermanager")) {
                webDriverManager = true;
            } else if (groupId.equals("com.aventstack") && artifactId.equals("extentreports")) {
                extentReports = true;
            } else if (groupId.equals("io.qameta.allure")) {
                allure = true;
            }
        }

        List<String> recognized = new ArrayList<>();
        if (seleniumVersion != null) {
            recognized.add(seleniumVersion.isBlank() ? "Selenium" : "Selenium " + seleniumVersion);
        }
        if (testNg) recognized.add("TestNG");
        if (junit4) recognized.add("JUnit 4");
        if (junit5) recognized.add("JUnit 5");
        if (webDriverManager) recognized.add("WebDriverManager");
        if (extentReports) recognized.add("ExtentReports");
        if (allure) recognized.add("Allure");
        return List.copyOf(recognized);
    }
}
