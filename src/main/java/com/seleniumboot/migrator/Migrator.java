package com.seleniumboot.migrator;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Copies a project and applies only transformations that can be performed mechanically. */
public final class Migrator {

    private static final String SELENIUM_BOOT_VERSION = "3.5.0";
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(".git", "target", "build", ".gradle", "node_modules");
    private static final Pattern GRADLE_STRING_DEPENDENCY = Pattern.compile(
            "(?m)^(?!(?:\\s*//|\\s*/\\*|\\s*\\*))(.*?)(?:(\"\"\"|['\"]))\\s*org\\.seleniumhq\\.selenium:selenium-java(?::[^'\"\\r\\n]+)?\\s*\\2");
    private static final Pattern GRADLE_MAP_GROUP_FIRST = Pattern.compile(
            "(?m)^(?!(?:\\s*//|\\s*/\\*|\\s*\\*))(.*?)\\bgroup\\s*(:|=>|=)\\s*(['\"])org\\.seleniumhq\\.selenium\\3\\s*,\\s*name\\s*\\2\\s*(['\"])selenium-java\\4(?:\\s*,\\s*version\\s*\\2\\s*['\"][^'\"]*['\"])?");
    private static final Pattern GRADLE_MAP_NAME_FIRST = Pattern.compile(
            "(?m)^(?!(?:\\s*//|\\s*/\\*|\\s*\\*))(.*?)\\bname\\s*(:|=>|=)\\s*(['\"])selenium-java\\3\\s*,\\s*group\\s*\\2\\s*(['\"])org\\.seleniumhq\\.selenium\\4(?:\\s*,\\s*version\\s*\\2\\s*['\"][^'\"]*['\"])?");
    private static final Pattern CATALOG_STRING_ENTRY = Pattern.compile(
            "^(\\s*[\\w.-]+\\s*=\\s*)([\"'])org\\.seleniumhq\\.selenium:selenium-java(?::[^\"']*)?\\2(\\s*#.*)?$");
    private static final Pattern CATALOG_MODULE_ENTRY = Pattern.compile(
            "^(\\s*[\\w.-]+\\s*=\\s*)\\{[^}]*\\bmodule\\s*=\\s*[\"']org\\.seleniumhq\\.selenium:selenium-java[\"'][^}]*\\}(\\s*#.*)?$");
    private static final Pattern CATALOG_GROUP_NAME_ENTRY = Pattern.compile(
            "^(\\s*[\\w.-]+\\s*=\\s*)\\{(?=[^}]*\\bgroup\\s*=\\s*[\"']org\\.seleniumhq\\.selenium[\"'])"
                    + "(?=[^}]*\\bname\\s*=\\s*[\"']selenium-java[\"'])[^}]*\\}(\\s*#.*)?$");
    private static final Pattern GRADLE_MULTI_ARG = Pattern.compile(
            "(?m)^(?!(?:\\s*//|\\s*/\\*|\\s*\\*))(.*?)(['\"])org\\.seleniumhq\\.selenium\\2\\s*,\\s*(['\"])selenium-java\\3(?:\\s*,\\s*['\"][^'\"]*['\"])?");

    public record Result(Path output, List<String> applied, List<String> notes, Report remaining) { }

    private final JavaParser parser = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));

    public Result migrate(Path project, Path output) throws IOException {
        Path source = project.toAbsolutePath().normalize();
        Path destination = output.toAbsolutePath().normalize();
        if (!Files.isDirectory(source)) {
            throw new IllegalArgumentException("not a directory: " + source);
        }
        if (destination.equals(source) || destination.startsWith(source)) {
            throw new IllegalArgumentException("output must not be the source directory or inside it: " + destination);
        }
        if (Files.exists(destination)) {
            throw new IllegalArgumentException("output already exists: " + destination);
        }
        Report sourceAnalysis = new Analyzer().analyze(source);

        Path parent = destination.getParent();
        if (parent != null) Files.createDirectories(parent);
        copyProject(source, destination);

        List<String> applied = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Set<String> removedTypes = transformJava(destination, applied);
        List<Finding> danglingReferences = findDanglingReferences(destination, removedTypes);
        List<Path> buildFiles = BuildFileAnalyzer.findBuildFiles(destination);
        if (buildFiles.isEmpty()) {
            notes.add("No pom.xml, build.gradle or build.gradle.kts found; add io.github.seleniumboot:selenium-boot:" + SELENIUM_BOOT_VERSION + " manually.");
        } else {
            boolean catalogMigrated = false;
            for (Path catalog : BuildFileAnalyzer.findVersionCatalogs(destination)) {
                if (migrateCatalog(catalog)) {
                    catalogMigrated = true;
                    applied.add(destination.relativize(catalog).toString().replace('\\', '/')
                            + ": replaced selenium-java catalog entry with io.github.seleniumboot:selenium-boot:"
                            + SELENIUM_BOOT_VERSION + " (alias unchanged, so existing libs.* references keep working)");
                }
            }
            for (Path buildFile : buildFiles) {
                String relPath = destination.relativize(buildFile).toString().replace('\\', '/');
                String name = buildFile.getFileName().toString();
                if (name.equals("pom.xml")) {
                    if (!migratePom(buildFile)) {
                        notes.add(relPath + ": no org.seleniumhq.selenium:selenium-java dependency found to replace.");
                    } else {
                        applied.add(relPath + ": replaced selenium-java with io.github.seleniumboot:selenium-boot:" + SELENIUM_BOOT_VERSION);
                    }
                } else if (name.equals("build.gradle") || name.equals("build.gradle.kts")) {
                    if (!migrateGradle(buildFile)) {
                        if (!catalogMigrated) notes.add(relPath + ": no org.seleniumhq.selenium:selenium-java dependency found to replace.");
                    } else {
                        applied.add(relPath + ": replaced selenium-java with io.github.seleniumboot:selenium-boot:" + SELENIUM_BOOT_VERSION);
                    }
                }
            }
        }
        Report outputAnalysis = new Analyzer().analyze(destination);
        Report remaining = includeSourceManualFindings(sourceAnalysis, outputAnalysis, danglingReferences);
        MigrationReport.write(destination, remaining);
        return new Result(destination, List.copyOf(applied), List.copyOf(notes), remaining);
    }

    private static Report includeSourceManualFindings(Report source, Report output, List<Finding> additionalFindings) {
        List<Finding> findings = new ArrayList<>(source.findings().stream()
                .filter(finding -> finding.status() == Finding.Status.MANUAL).toList());
        output.findings().stream().filter(finding -> !findings.contains(finding)).forEach(findings::add);
        additionalFindings.stream().filter(finding -> !findings.contains(finding)).forEach(findings::add);
        Set<String> unparsable = new LinkedHashSet<>(source.unparsable());
        unparsable.addAll(output.unparsable());
        return new Report(output.filesFound(), output.filesParsed(), List.copyOf(unparsable), List.copyOf(findings),
                source.detectedTechnologies(), source.recognizedTechnologies(), source.locatorCounts());
    }

    private static void copyProject(Path source, Path destination) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(source) && EXCLUDED_DIRECTORIES.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(destination.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, destination.resolve(source.relativize(file)),
                    StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private Set<String> transformJava(Path root, List<String> applied) throws IOException {
        List<Path> sources;
        try (Stream<Path> files = Files.walk(root)) {
            sources = files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
        Set<String> removedTypes = new LinkedHashSet<>();
        for (Path file : sources) {
            var parsed = parser.parse(file);
            if (parsed.getResult().isEmpty() || !parsed.isSuccessful()) continue;
            CompilationUnit unit = parsed.getResult().get();
            LexicalPreservingPrinter.setup(unit);
            boolean changed = removeAutoTypes(unit, applied, removedTypes, root.relativize(file).toString());
            changed |= removeWebDriverManagerSetup(unit, applied, root.relativize(file).toString());
            if (changed) changed |= removeUnusedExplicitImports(unit);
            if (changed && unit.getTypes().isEmpty()) {
                Files.delete(file);
            } else if (changed) {
                Files.writeString(file, LexicalPreservingPrinter.print(unit));
            }
        }
        return removedTypes;
    }

    private static boolean removeAutoTypes(CompilationUnit unit, List<String> applied, Set<String> removedTypes, String file) {
        boolean changed = false;
        for (var type : new ArrayList<>(unit.getTypes())) {
            if (!(type instanceof ClassOrInterfaceDeclaration declaration)) continue;
            boolean driverFactory = declaration.getNameAsString().endsWith("DriverFactory")
                    && declaration.findAll(FieldDeclaration.class).stream().anyMatch(Migrator::isThreadLocalDriver);
            boolean retry = declaration.getImplementedTypes().stream()
                    .anyMatch(implemented -> implemented.getNameAsString().equals("IRetryAnalyzer")
                            || implemented.getNameAsString().equals("IAnnotationTransformer"));
            boolean screenshotListener = declaration.getImplementedTypes().stream()
                    .anyMatch(implemented -> implemented.getNameAsString().equals("ITestListener"))
                    && (declaration.toString().contains("TakesScreenshot")
                    || declaration.toString().contains("getScreenshotAs"));
            if (driverFactory || retry || screenshotListener) {
                String packageName = unit.getPackageDeclaration()
                    .map(packageDeclaration -> packageDeclaration.getNameAsString() + ".").orElse("");
                removedTypes.add(packageName + declaration.getNameAsString());
                declaration.remove();
                applied.add(file + ": removed " + declaration.getNameAsString());
                changed = true;
            }
        }
        return changed;
    }

    private List<Finding> findDanglingReferences(Path root, Set<String> removedTypes) throws IOException {
        if (removedTypes.isEmpty()) return List.of();
        List<Finding> findings = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                var parsed = parser.parse(file);
                if (parsed.getResult().isEmpty() || !parsed.isSuccessful()) continue;
                CompilationUnit unit = parsed.getResult().get();
                for (String removedType : removedTypes) {
                    NodeReference reference = findTypeReference(unit, removedType);
                    if (reference != null) {
                        findings.add(new Finding("MIG-017", Finding.Status.MANUAL,
                                root.relativize(file).toString(), reference.line(), removedType,
                                "This file still references a class removed during migration; update the caller before compiling."));
                    }
                }
            }
        }
        return findings;
    }

    private static NodeReference findTypeReference(CompilationUnit unit, String targetType) {
        String simpleName = targetType.substring(targetType.lastIndexOf('.') + 1);
        String packageName = targetType.contains(".")
                ? targetType.substring(0, targetType.lastIndexOf('.')) : "";
        boolean samePackage = unit.getPackageDeclaration()
                .map(declaration -> declaration.getNameAsString().equals(packageName)).orElse(packageName.isEmpty());
        boolean imported = unit.getImports().stream().anyMatch(declaration -> {
            String importedName = declaration.getNameAsString();
            return (!declaration.isAsterisk() && (importedName.equals(targetType)
                    || declaration.isStatic() && importedName.startsWith(targetType + ".")))
                    || declaration.isAsterisk() && (importedName.equals(packageName)
                    || declaration.isStatic() && importedName.equals(targetType));
        });

        for (var declaration : unit.getImports()) {
            String importedName = declaration.getNameAsString();
            if (importedName.equals(targetType) || declaration.isStatic()
                    && (importedName.startsWith(targetType + ".")
                    || declaration.isAsterisk() && importedName.equals(targetType))) {
                return new NodeReference(declaration.getBegin().map(position -> position.line).orElse(0));
            }
        }
        for (ClassOrInterfaceType type : unit.findAll(ClassOrInterfaceType.class)) {
            if (matchesTypeReference(type.toString(), targetType, simpleName, samePackage, imported)) {
                return new NodeReference(type.getBegin().map(position -> position.line).orElse(0));
            }
        }
        for (AnnotationExpr annotation : unit.findAll(AnnotationExpr.class)) {
            if (matchesTypeReference(annotation.getNameAsString(), targetType, simpleName, samePackage, imported)) {
                return new NodeReference(annotation.getBegin().map(position -> position.line).orElse(0));
            }
        }
        for (MethodCallExpr call : unit.findAll(MethodCallExpr.class)) {
            if (call.getScope().map(scope -> matchesTypeReference(scope.toString(), targetType,
                    simpleName, samePackage, imported)).orElse(false)) {
                return new NodeReference(call.getBegin().map(position -> position.line).orElse(0));
            }
        }
        for (FieldAccessExpr access : unit.findAll(FieldAccessExpr.class)) {
            if (matchesTypeReference(access.getScope().toString(), targetType, simpleName, samePackage, imported)) {
                return new NodeReference(access.getBegin().map(position -> position.line).orElse(0));
            }
        }
        return null;
    }

    private static boolean matchesTypeReference(String reference, String targetType, String simpleName,
                                                boolean samePackage, boolean imported) {
        return reference.equals(targetType) || reference.equals(simpleName) && (samePackage || imported);
    }

    private record NodeReference(int line) { }

    private static boolean removeUnusedExplicitImports(CompilationUnit unit) {
        boolean changed = false;
        for (var declaration : new ArrayList<>(unit.getImports())) {
            if (declaration.isAsterisk() || declaration.isStatic()) continue;
            String simpleName = declaration.getName().getIdentifier();
            boolean used = unit.findAll(ClassOrInterfaceType.class).stream()
                    .anyMatch(type -> type.getNameAsString().equals(simpleName))
                    || unit.findAll(NameExpr.class).stream().anyMatch(name -> name.getNameAsString().equals(simpleName))
                    || unit.findAll(AnnotationExpr.class).stream()
                    .anyMatch(annotation -> annotation.getName().getIdentifier().equals(simpleName));
            if (!used) {
                declaration.remove();
                changed = true;
            }
        }
        return changed;
    }

    private static boolean isThreadLocalDriver(FieldDeclaration field) {
        String type = field.getElementType().toString();
        return type.startsWith("ThreadLocal<") && type.contains("WebDriver");
    }

    private static boolean removeWebDriverManagerSetup(CompilationUnit unit, List<String> applied, String file) {
        boolean changed = false;
        for (MethodCallExpr call : unit.findAll(MethodCallExpr.class)) {
            if (!call.getNameAsString().equals("setup")
                    || call.getScope().map(scope -> !scope.toString().contains("WebDriverManager")).orElse(true)) {
                continue;
            }
            var statement = call.findAncestor(ExpressionStmt.class);
            if (statement.isPresent() && statement.get().getExpression() == call) {
                statement.get().remove();
                applied.add(file + ": removed WebDriverManager setup call");
                changed = true;
            }
        }
        if (changed) {
            unit.getImports().removeIf(importDeclaration ->
                    importDeclaration.getNameAsString().endsWith("WebDriverManager"));
        }
        return changed;
    }

    private static boolean migratePom(Path pom) throws IOException {
        if (!Files.isRegularFile(pom)) return false;
        try {
            DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance();
            builderFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            builderFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document = builderFactory.newDocumentBuilder().parse(pom.toFile());
            NodeList dependencies = document.getElementsByTagName("dependency");
            boolean changed = false;
            for (int index = 0; index < dependencies.getLength(); index++) {
                Element dependency = (Element) dependencies.item(index);
                String groupId = childText(dependency, "groupId");
                String artifactId = childText(dependency, "artifactId");
                if (groupId.equals("org.seleniumhq.selenium") && artifactId.equals("selenium-java")) {
                    setChildText(dependency, "groupId", "io.github.seleniumboot");
                    setChildText(dependency, "artifactId", "selenium-boot");
                    setChildText(dependency, "version", SELENIUM_BOOT_VERSION);
                    changed = true;
                }
            }
            if (!changed) return false;
            var transformerFactory = TransformerFactory.newInstance();
            transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            var transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.transform(new DOMSource(document), new StreamResult(pom.toFile()));
            return true;
        } catch (Exception exception) {
            if (exception instanceof IOException ioException) throw ioException;
            throw new IOException("could not update " + pom + ": " + exception.getMessage(), exception);
        }
    }

    private static String childText(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            if (children.item(index) instanceof Element child && child.getTagName().equals(name)) {
                return child.getTextContent().trim();
            }
        }
        return "";
    }

    private static void setChildText(Element parent, String name, String value) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            if (children.item(index) instanceof Element child && child.getTagName().equals(name)) {
                child.setTextContent(value);
                return;
            }
        }
        Element child = parent.getOwnerDocument().createElement(name);
        child.setTextContent(value);
        parent.appendChild(child);
    }

    /** Rewrites single-line {@code selenium-java} entries in a version catalog; the alias is kept. */
    private static boolean migrateCatalog(Path catalog) throws IOException {
        String original = Files.readString(catalog);
        String entry = "{ module = \"io.github.seleniumboot:selenium-boot\", version = \"" + SELENIUM_BOOT_VERSION + "\" }";
        StringBuilder out = new StringBuilder();
        boolean changed = false;
        for (String line : original.split("\n", -1)) {
            String updated = line;
            if (!line.stripLeading().startsWith("#")) {
                for (Pattern pattern : List.of(CATALOG_STRING_ENTRY, CATALOG_MODULE_ENTRY, CATALOG_GROUP_NAME_ENTRY)) {
                    java.util.regex.Matcher m = pattern.matcher(line);
                    if (m.matches()) {
                        String comment = m.group(m.groupCount());
                        updated = m.group(1) + entry + (comment != null ? comment : "");
                        break;
                    }
                }
            }
            changed |= !updated.equals(line);
            out.append(updated).append('\n');
        }
        if (!changed) return false;
        out.setLength(out.length() - 1);
        Files.writeString(catalog, out.toString());
        return true;
    }

    private static boolean migrateGradle(Path buildFile) throws IOException {
        if (!Files.isRegularFile(buildFile)) return false;
        try {
            String original = Files.readString(buildFile);
            String updated = GRADLE_STRING_DEPENDENCY.matcher(original).replaceAll(
                    mr -> mr.group(1) + mr.group(2) + "io.github.seleniumboot:selenium-boot:" + SELENIUM_BOOT_VERSION + mr.group(2));
            updated = GRADLE_MAP_GROUP_FIRST.matcher(updated).replaceAll(
                    mr -> mr.group(1) + "group" + mr.group(2) + " " + mr.group(3) + "io.github.seleniumboot" + mr.group(3)
                            + ", name" + mr.group(2) + " " + mr.group(4) + "selenium-boot" + mr.group(4)
                            + ", version" + mr.group(2) + " " + mr.group(3) + SELENIUM_BOOT_VERSION + mr.group(3));
            updated = GRADLE_MAP_NAME_FIRST.matcher(updated).replaceAll(
                    mr -> mr.group(1) + "name" + mr.group(2) + " " + mr.group(3) + "selenium-boot" + mr.group(3)
                            + ", group" + mr.group(2) + " " + mr.group(4) + "io.github.seleniumboot" + mr.group(4)
                            + ", version" + mr.group(2) + " " + mr.group(4) + SELENIUM_BOOT_VERSION + mr.group(4));
            updated = GRADLE_MULTI_ARG.matcher(updated).replaceAll(
                    mr -> mr.group(1) + mr.group(2) + "io.github.seleniumboot" + mr.group(2)
                            + ", " + mr.group(3) + "selenium-boot" + mr.group(3)
                            + ", " + mr.group(2) + SELENIUM_BOOT_VERSION + mr.group(2));

            if (original.equals(updated)) return false;
            Files.writeString(buildFile, updated);
            return true;
        } catch (Exception exception) {
            if (exception instanceof IOException ioException) throw ioException;
            throw new IOException("could not update " + buildFile + ": " + exception.getMessage(), exception);
        }
    }
}