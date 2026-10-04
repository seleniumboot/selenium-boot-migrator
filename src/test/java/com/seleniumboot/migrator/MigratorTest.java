package com.seleniumboot.migrator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class MigratorTest {

    @TempDir
    Path temp;

    @Test
    void migratesCopyWithoutChangingOriginalAndCompilesFixture() throws Exception {
        Path project = temp.resolve("project");
        Path output = temp.resolve("migrated");
        write(project.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><dependencies>
                  <dependency><groupId>org.seleniumhq.selenium</groupId><artifactId>selenium-java</artifactId><version>4.21.0</version></dependency>
                </dependencies></project>
                """);
        write(project.resolve("src/main/java/fixture/DriverFactory.java"), """
                package fixture;
                import org.openqa.selenium.WebDriver;
                public class DriverFactory {
                    private static final ThreadLocal<WebDriver> DRIVER = new ThreadLocal<>();
                    public void legacyWait() throws InterruptedException { Thread.sleep(1); }
                }
                """);
        write(project.resolve("src/main/java/fixture/Setup.java"), """
                package fixture;
                import io.github.bonigarcia.wdm.WebDriverManager;
                public class Setup {
                    public void configure() {
                        // Keep this note and the surrounding layout.
                        WebDriverManager.chromedriver().setup();
                    }
                }
                """);
        write(project.resolve("src/main/java/fixture/Manual.java"), """
                package fixture;
                public class Manual {
                    public void waitForPage() throws InterruptedException {
                        Thread.sleep(1);
                    }
                }
                """);
            write(project.resolve("src/main/java/fixture/RetryAnalyzer.java"), """
                package fixture;
                import org.testng.IRetryAnalyzer;
                class RetryAnalyzer implements IRetryAnalyzer { }
                """);
            write(project.resolve("src/main/java/fixture/ScreenshotListener.java"), """
                package fixture;
                import org.testng.ITestListener;
                import org.openqa.selenium.TakesScreenshot;
                class ScreenshotListener implements ITestListener {
                    TakesScreenshot screenshot;
                }
                """);
        write(project.resolve("src/main/java/fixture/MixedFactory.java"), """
                package fixture;
                import org.openqa.selenium.WebDriver;
                class MixedDriverFactory {
                    ThreadLocal<WebDriver> driver;
                }
                class Utility { int value; }
                """);
        write(project.resolve(".git/config"), "git metadata");
        write(project.resolve("target/classes/old.class"), "build output");
        write(project.resolve("node_modules/package/index.js"), "dependency");
        Map<Path, byte[]> original = snapshot(project);

        Migrator.Result result = new Migrator().migrate(project, output);

        assertTrue(Files.isRegularFile(output.resolve("MIGRATION_REPORT.md")));
        String migrationReport = Files.readString(output.resolve("MIGRATION_REPORT.md"));
        assertEquals(MigrationReport.render(result.remaining()), migrationReport);
        assertTrue(migrationReport.contains("# Selenium Boot Migration Report"));
        assertTrue(migrationReport.contains("## Rules applied per file"));
        assertTrue(migrationReport.contains("Estimated migration confidence"));
        assertEquals(original.keySet(), snapshot(project).keySet());
        original.forEach((path, bytes) -> assertArrayEquals(bytes, read(project.resolve(path))));
        assertFalse(Files.exists(output.resolve("src/main/java/fixture/DriverFactory.java")));
        assertFalse(Files.exists(output.resolve("src/main/java/fixture/RetryAnalyzer.java")));
        assertFalse(Files.exists(output.resolve("src/main/java/fixture/ScreenshotListener.java")));
        assertFalse(Files.exists(output.resolve(".git")));
        assertFalse(Files.exists(output.resolve("target")));
        assertFalse(Files.exists(output.resolve("node_modules")));
        String mixed = Files.readString(output.resolve("src/main/java/fixture/MixedFactory.java"));
        assertFalse(mixed.contains("org.openqa.selenium.WebDriver"));
        assertTrue(mixed.contains("class Utility"));
        String setup = Files.readString(output.resolve("src/main/java/fixture/Setup.java"));
        assertFalse(setup.contains("WebDriverManager"));
        assertTrue(setup.contains("// Keep this note and the surrounding layout."));
        String pom = Files.readString(output.resolve("pom.xml"));
        assertTrue(pom.contains("<groupId>io.github.seleniumboot</groupId>"));
        assertTrue(pom.contains("<artifactId>selenium-boot</artifactId>"));
        assertTrue(pom.contains("<version>3.5.0</version>"));
        assertTrue(result.remaining().findings().stream().anyMatch(f -> f.ruleId().equals("MIG-014")));
        assertTrue(result.remaining().findings().stream().anyMatch(f -> f.ruleId().equals("MIG-014")
            && f.file().endsWith("DriverFactory.java")));
        assertTrue(result.applied().stream().anyMatch(change -> change.contains("DriverFactory")));

        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "a JDK compiler is required to validate the fixture");
        Path classes = output.resolve("classes");
        Files.createDirectories(classes);
        try (Stream<Path> sourceFiles = Files.walk(output.resolve("src/main/java"))) {
            var files = sourceFiles.filter(path -> path.toString().endsWith(".java")).toList();
            var compilerArguments = new java.util.ArrayList<String>();
            compilerArguments.add("-d");
            compilerArguments.add(classes.toString());
            files.stream().map(Path::toString).forEach(compilerArguments::add);
            assertEquals(0, compiler.run(null, null, null, compilerArguments.toArray(String[]::new)));
        }
    }

    @Test
    void reportsReferencesToDeletedRetryAndDriverTypes() throws Exception {
        Path project = temp.resolve("referencing-project");
        Path output = temp.resolve("referencing-output");
        write(project.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><dependencies>
              <dependency><groupId>org.seleniumhq.selenium</groupId><artifactId>selenium-java</artifactId></dependency>
            </dependencies></project>
            """);
        write(project.resolve("src/main/java/fixture/DriverFactory.java"), """
            package fixture;
            class DriverFactory { ThreadLocal<WebDriver> driver; }
            """);
        write(project.resolve("src/main/java/fixture/RetryAnalyzer.java"), """
            package fixture;
            class RetryAnalyzer implements IRetryAnalyzer { }
            """);
        write(project.resolve("src/main/java/consumer/Caller.java"), """
            package consumer;
            import fixture.DriverFactory;
            import fixture.RetryAnalyzer;
            class Caller {
                DriverFactory factory;
                RetryAnalyzer retry;
                void configure() { DriverFactory.create(); }
            }
            """);

        Migrator.Result result = new Migrator().migrate(project, output);

        assertEquals(2, result.remaining().findings().stream()
            .filter(finding -> finding.ruleId().equals("MIG-017")
                && finding.file().endsWith("Caller.java"))
            .count());
        assertTrue(Files.readString(output.resolve("pom.xml")).contains("<version>3.5.0</version>"));

        assertTrue(Files.isRegularFile(output.resolve("MIGRATION_REPORT.md")));
        String migrationReport = Files.readString(output.resolve("MIGRATION_REPORT.md"));
        assertEquals(MigrationReport.render(result.remaining()), migrationReport);
        assertTrue(migrationReport.contains("MIG-017"));
        assertTrue(migrationReport.contains("Caller.java"));
        assertFalse(migrationReport.contains("MIG-004"));
        assertFalse(migrationReport.contains("MIG-001"));
    }

    @Test
    void refusesToWriteIntoSourceOrOverwriteExistingOutput() throws Exception {
        Path project = temp.resolve("project");
        Files.createDirectories(project);
        write(project.resolve("source.txt"), "unchanged");

        assertThrows(IllegalArgumentException.class, () -> new Migrator().migrate(project, project));
        assertThrows(IllegalArgumentException.class,
                () -> new Migrator().migrate(project, project.resolve("child")));
        Path existing = temp.resolve("existing");
        Files.createDirectory(existing);
        assertThrows(IllegalArgumentException.class, () -> new Migrator().migrate(project, existing));
        assertEquals("unchanged", Files.readString(project.resolve("source.txt")));
    }

    @Test
    void migratesGradleGroovyProjectWithoutChangingOriginal() throws Exception {
        Path project = temp.resolve("gradle-groovy-project");
        Path output = temp.resolve("gradle-groovy-migrated");
        write(project.resolve("build.gradle"), """
                plugins {
                    id 'java'
                }

                dependencies {
                    implementation 'org.seleniumhq.selenium:selenium-java:4.21.0'
                    testImplementation 'org.testng:testng:7.10.2'
                }
                """);
        write(project.resolve("src/main/java/fixture/DriverFactory.java"), """
                package fixture;
                import org.openqa.selenium.WebDriver;
                public class DriverFactory {
                    private static final ThreadLocal<WebDriver> DRIVER = new ThreadLocal<>();
                }
                """);
        write(project.resolve(".gradle/caches/cache.bin"), "gradle cache");
        write(project.resolve("build/libs/app.jar"), "build jar");
        Map<Path, byte[]> original = snapshot(project);

        Migrator.Result result = new Migrator().migrate(project, output);

        assertEquals(original.keySet(), snapshot(project).keySet());
        original.forEach((path, bytes) -> assertArrayEquals(bytes, read(project.resolve(path))));
        assertFalse(Files.exists(output.resolve(".gradle")));
        assertFalse(Files.exists(output.resolve("build")));
        assertFalse(Files.exists(output.resolve("src/main/java/fixture/DriverFactory.java")));

        String gradle = Files.readString(output.resolve("build.gradle"));
        assertTrue(gradle.contains("implementation 'io.github.seleniumboot:selenium-boot:3.5.0'"));
        assertTrue(gradle.contains("testImplementation 'org.testng:testng:7.10.2'"));
        assertFalse(gradle.contains("org.seleniumhq.selenium:selenium-java"));

        assertTrue(result.applied().contains("build.gradle: replaced selenium-java with io.github.seleniumboot:selenium-boot:3.5.0"));
        assertTrue(result.remaining().detectedTechnologies().contains("Dependency: org.seleniumhq.selenium:selenium-java:4.21.0"));
        assertFalse(result.remaining().detectedTechnologies().contains("Dependency: io.github.seleniumboot:selenium-boot:3.5.0"));
        assertTrue(result.remaining().detectedTechnologies().contains("Dependency: org.testng:testng:7.10.2"));
        assertTrue(result.remaining().detectedTechnologies().contains("Build system: Gradle (Groovy DSL)"));
    }

    @Test
    void migratesGradleKotlinProjectWithoutChangingOriginal() throws Exception {
        Path project = temp.resolve("gradle-kotlin-project");
        Path output = temp.resolve("gradle-kotlin-migrated");
        write(project.resolve("build.gradle.kts"), """
                plugins {
                    java
                }

                dependencies {
                    implementation("org.seleniumhq.selenium:selenium-java:4.21.0")
                    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
                }
                """);
        Map<Path, byte[]> original = snapshot(project);

        Migrator.Result result = new Migrator().migrate(project, output);

        assertEquals(original.keySet(), snapshot(project).keySet());
        original.forEach((path, bytes) -> assertArrayEquals(bytes, read(project.resolve(path))));

        String gradleKts = Files.readString(output.resolve("build.gradle.kts"));
        assertTrue(gradleKts.contains("implementation(\"io.github.seleniumboot:selenium-boot:3.5.0\")"));
        assertTrue(gradleKts.contains("testImplementation(\"org.junit.jupiter:junit-jupiter:5.10.2\")"));
        assertFalse(gradleKts.contains("org.seleniumhq.selenium:selenium-java"));

        assertTrue(result.applied().contains("build.gradle.kts: replaced selenium-java with io.github.seleniumboot:selenium-boot:3.5.0"));
        assertTrue(result.remaining().detectedTechnologies().contains("Dependency: org.seleniumhq.selenium:selenium-java:4.21.0"));
        assertFalse(result.remaining().detectedTechnologies().contains("Dependency: io.github.seleniumboot:selenium-boot:3.5.0"));
        assertTrue(result.remaining().detectedTechnologies().contains("Dependency: org.junit.jupiter:junit-jupiter:5.10.2"));
        assertTrue(result.remaining().detectedTechnologies().contains("Build system: Gradle (Kotlin DSL)"));
    }

    @Test
    void migratesFromGradleFixtures() throws Exception {
        Path groovyFixture = Path.of(MigratorTest.class.getResource("/gradle-groovy").toURI());
        Path groovyOutput = temp.resolve("fixture-groovy-migrated");
        Migrator.Result groovyResult = new Migrator().migrate(groovyFixture, groovyOutput);
        assertTrue(groovyResult.applied().contains("build.gradle: replaced selenium-java with io.github.seleniumboot:selenium-boot:3.5.0"));
        assertTrue(Files.readString(groovyOutput.resolve("build.gradle")).contains("io.github.seleniumboot:selenium-boot:3.5.0"));

        Path kotlinFixture = Path.of(MigratorTest.class.getResource("/gradle-kotlin").toURI());
        Path kotlinOutput = temp.resolve("fixture-kotlin-migrated");
        Migrator.Result kotlinResult = new Migrator().migrate(kotlinFixture, kotlinOutput);
        assertTrue(kotlinResult.applied().contains("build.gradle.kts: replaced selenium-java with io.github.seleniumboot:selenium-boot:3.5.0"));
        assertTrue(Files.readString(kotlinOutput.resolve("build.gradle.kts")).contains("io.github.seleniumboot:selenium-boot:3.5.0"));
    }

    @Test
    void migratesSeleniumJavaInGradleVersionCatalog() throws Exception {
        Path project = temp.resolve("catalog");
        Path output = temp.resolve("catalog-migrated");
        write(project.resolve("build.gradle.kts"), """
                plugins { java }
                dependencies {
                    implementation(libs.selenium.java)
                    implementation(libs.selenium.chrome.driver)
                }
                """);
        write(project.resolve("gradle/libs.versions.toml"), """
                [versions]
                selenium = "4.21.0"

                [libraries]
                # selenium-java = "org.seleniumhq.selenium:selenium-java:1.0"
                selenium-java = { module = "org.seleniumhq.selenium:selenium-java", version.ref = "selenium" } # main
                selenium-chrome-driver = { module = "org.seleniumhq.selenium:selenium-chrome-driver", version.ref = "selenium" }
                junit = "org.junit.jupiter:junit-jupiter:5.10.2"
                """);
        Migrator.Result result = new Migrator().migrate(project, output);

        String toml = Files.readString(output.resolve("gradle/libs.versions.toml"));
        assertTrue(toml.contains("selenium-java = { module = \"io.github.seleniumboot:selenium-boot\", version = \"3.5.0\" } # main"));
        assertTrue(toml.contains("# selenium-java = \"org.seleniumhq.selenium:selenium-java:1.0\""), "comment untouched");
        assertTrue(toml.contains("selenium-chrome-driver = { module = \"org.seleniumhq.selenium:selenium-chrome-driver\""), "other entries untouched");
        assertTrue(toml.contains("junit = \"org.junit.jupiter:junit-jupiter:5.10.2\""));
        assertTrue(result.applied().stream().anyMatch(a -> a.startsWith("gradle/libs.versions.toml: replaced selenium-java catalog entry")));
        assertTrue(result.notes().stream().noneMatch(n -> n.startsWith("build.gradle.kts: no org.seleniumhq")),
                "a catalog hit must not produce a misleading 'no dependency' note");
        assertTrue(Files.readString(project.resolve("gradle/libs.versions.toml")).contains("org.seleniumhq.selenium:selenium-java\", version.ref"),
                "original project untouched");
    }

    @Test
    void migratesCatalogStringAndGroupNameForms() throws Exception {
        Path project = temp.resolve("catalog-forms");
        Path output = temp.resolve("catalog-forms-migrated");
        write(project.resolve("build.gradle"), "dependencies { implementation libs.sel }\n");
        write(project.resolve("gradle/libs.versions.toml"), """
                [libraries]
                sel = "org.seleniumhq.selenium:selenium-java:4.21.0"
                sel2 = { group = "org.seleniumhq.selenium", name = "selenium-java", version = "4.21.0" }
                """);
        new Migrator().migrate(project, output);
        String toml = Files.readString(output.resolve("gradle/libs.versions.toml"));
        assertTrue(toml.contains("sel = { module = \"io.github.seleniumboot:selenium-boot\", version = \"3.5.0\" }"));
        assertTrue(toml.contains("sel2 = { module = \"io.github.seleniumboot:selenium-boot\", version = \"3.5.0\" }"));
        assertFalse(toml.contains("org.seleniumhq.selenium:selenium-java"));
    }

    @Test
    void reportsNoteWhenNoBuildFileExists() throws Exception {
        Path project = temp.resolve("no-build-file");
        Path output = temp.resolve("no-build-file-migrated");
        write(project.resolve("src/test/java/App.java"), "class App { }\n");
        Migrator.Result result = new Migrator().migrate(project, output);
        assertTrue(result.notes().stream().anyMatch(n -> n.startsWith("No pom.xml, build.gradle or build.gradle.kts found")));
    }

    @Test
    void reportsNoteWhenGradleBuildHasNoSeleniumJava() throws Exception {
        Path project = temp.resolve("gradle-no-selenium");
        Path output = temp.resolve("gradle-no-selenium-migrated");
        write(project.resolve("build.gradle"), """
                plugins {
                    id 'java'
                }
                dependencies {
                    testImplementation 'org.testng:testng:7.10.2'
                }
                """);
        Migrator.Result result = new Migrator().migrate(project, output);
        assertTrue(result.notes().contains("build.gradle: no org.seleniumhq.selenium:selenium-java dependency found to replace."));
        assertFalse(result.applied().stream().anyMatch(applied -> applied.contains("build.gradle")));
    }

    @Test
    void supportsVariousGradleSyntaxStyles() throws Exception {
        Path project = temp.resolve("gradle-syntax");
        Path output = temp.resolve("gradle-syntax-migrated");
        write(project.resolve("build.gradle"), """
                dependencies {
                    // Comments should not be touched:
                    // implementation 'org.seleniumhq.selenium:selenium-java:4.21.0'
                    /* testImplementation 'org.seleniumhq.selenium:selenium-java:4.21.0' */
                    * implementation 'org.seleniumhq.selenium:selenium-java:4.21.0'

                    // Double quotes with and without version:
                    implementation "org.seleniumhq.selenium:selenium-java:4.21.0"
                    api "org.seleniumhq.selenium:selenium-java"

                    // Map notation:
                    testImplementation group: 'org.seleniumhq.selenium', name: 'selenium-java', version: '4.21.0'
                    compileOnly name: 'selenium-java', group: 'org.seleniumhq.selenium'

                    // Multi-arg notation:
                    runtimeOnly 'org.seleniumhq.selenium', 'selenium-java', '4.21.0'

                    // Parentheses with exclude block:
                    implementation('org.seleniumhq.selenium:selenium-java:4.21.0') {
                        exclude group: 'org.hamcrest'
                    }
                }
                """);
        Migrator.Result result = new Migrator().migrate(project, output);
        assertTrue(result.applied().contains("build.gradle: replaced selenium-java with io.github.seleniumboot:selenium-boot:3.5.0"));

        String migrated = Files.readString(output.resolve("build.gradle"));
        assertTrue(migrated.contains("// implementation 'org.seleniumhq.selenium:selenium-java:4.21.0'"));
        assertTrue(migrated.contains("/* testImplementation 'org.seleniumhq.selenium:selenium-java:4.21.0' */"));
        assertTrue(migrated.contains("* implementation 'org.seleniumhq.selenium:selenium-java:4.21.0'"));

        assertTrue(migrated.contains("implementation \"io.github.seleniumboot:selenium-boot:3.5.0\""));
        assertTrue(migrated.contains("api \"io.github.seleniumboot:selenium-boot:3.5.0\""));
        assertTrue(migrated.contains("testImplementation group: 'io.github.seleniumboot', name: 'selenium-boot', version: '3.5.0'"));
        assertTrue(migrated.contains("compileOnly name: 'selenium-boot', group: 'io.github.seleniumboot', version: '3.5.0'"));
        assertTrue(migrated.contains("runtimeOnly 'io.github.seleniumboot', 'selenium-boot', '3.5.0'"));
        assertTrue(migrated.contains("implementation('io.github.seleniumboot:selenium-boot:3.5.0') {"));
        assertTrue(migrated.contains("exclude group: 'org.hamcrest'"));
    }

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static Map<Path, byte[]> snapshot(Path root) throws Exception {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).collect(Collectors.toMap(
                    root::relativize, MigratorTest::read));
        }
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }
}