package com.composum.sling.platform.testing.testutil;

import org.apache.commons.collections4.SetUtils;

import org.jetbrains.annotations.NotNull;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Looks for i18n keys that are used but not declared. Not ready yet: the i18n files aren't read so far.
 */
public class LookForMissingI18n {

    protected static final Pattern JSPKEY = Pattern.compile("(?:title|label|value|hint|placeholder|format|alert-danger)=\"([a-zA-Z][^\"]+)\"");

    protected static final Pattern I18DEF = Pattern.compile("sling:key=\"([^\"]+)\"");

    protected static final Pattern JAVAI18N = Pattern.compile("(?:I18N.get|i18n\\(\\).get|i18n)\\([^,]+,\\s*['\"]([^'\"]+)['\"]\\s*\\)");

    protected static final Pattern CPNI18N = Pattern.compile("cpn:i18n\\([^,]+,\\s*['\"]([^'\"]+)['\"]\\s*\\)");

    protected static final Pattern STRING = Pattern.compile("\"(([^\"]+|\\\\\"))\"");

    protected static final Pattern QSTRING = Pattern.compile("'([^']*)'");

    String relative = ".";

    List<String> topdirs = Arrays.asList("public", "pages");

    Set<String> foundKeys = new TreeSet<>();

    Set<String> foundStrings = new TreeSet<>();

    Set<String> foundDefs = new TreeSet<>();

    public static void main(String[] args) {
        System.out.println("Starting at " + new File(".").getAbsolutePath());
        new LookForMissingI18n().run();
        System.out.println("DONE\n\n");
        System.out.println(JSPKEY.pattern());
        System.out.println(I18DEF.pattern());
        System.out.println(JAVAI18N.pattern());
        System.out.println(CPNI18N.pattern());
        System.out.println(STRING.pattern());
        System.out.println(QSTRING.pattern());
    }

    protected void run() {
        topdirs.stream().map(this::topDir).collect(Collectors.toList()); // check dir existence
        topdirs.forEach(this::collectData);
        printMissing();
    }

    protected void printMissing() {
        SetUtils.SetView<String> foundButNotDefined = SetUtils.difference(foundKeys, foundDefs);
        System.out.println("Found but not defined: " + foundButNotDefined.size());
        System.out.println(foundButNotDefined
                .stream().collect(Collectors.joining("\n")));

        System.out.println("\n\n##############################################################################\n");
        SetUtils.SetView<String> defindedButNotFound = SetUtils.difference(SetUtils.difference(foundDefs, foundKeys), foundStrings);
        System.out.println("Defined but not found: " + defindedButNotFound.size());
        System.out.println(defindedButNotFound
                .stream().collect(Collectors.joining("\n")));

//        System.out.println("\n\n##############################################################################\n");
//        System.out.println("Defined and found:");
//        System.out.println(SetUtils.intersection(foundDefs, foundKeys)
//                .stream().collect(Collectors.joining("\n")));
    }

    protected void collectData(String topdir) {
        File dir = topDir(topdir);
        childrenStream(dir).forEach(this::processFile);
    }

    protected void processFile(File file) {
        String fileName = file.getName();
        if (fileName.endsWith(".jsp")) {
            reader(file).lines().filter((line) -> line.contains("cpn:i18n"))
                    .flatMap(extractKeysFunc(CPNI18N))
                    .forEach(foundKeys::add);

            reader(file).lines().filter((line) -> line.contains("\""))
                    .flatMap(extractKeysFunc(JSPKEY))
                    .forEach(foundKeys::add);

            reader(file).lines().filter((line) -> line.contains("\""))
                    .flatMap(extractKeysFunc(STRING))
                    .forEach(foundStrings::add);

            reader(file).lines().filter((line) -> line.contains("\'"))
                    .flatMap(extractKeysFunc(QSTRING))
                    .forEach(foundStrings::add);
        }
        if (fileName.endsWith(".java")) {
            // finds all strings, which is too much, of course...
            reader(file).lines().filter((line) -> line.contains("\""))
                    .flatMap(extractKeysFunc(STRING))
                    .forEach(foundStrings::add);

            reader(file).lines().filter((line) -> line.contains("\""))
                    .flatMap(extractKeysFunc(JAVAI18N))
                    .forEach(foundKeys::add);
        }
        if (fileName.equals("de.xml")) {
            // System.out.println("FILE " + file.getPath());
            reader(file).lines().filter((line) -> line.contains("sling:key"))
                    .flatMap(extractKeysFunc(I18DEF))
                    // .map(this::print)
                    .forEach(foundDefs::add);
        }

        if (fileName.equals(".content.xml")) {
            reader(file).lines().filter((line) -> line.contains("\""))
                    .flatMap(extractKeysFunc(STRING))
                    .forEach(foundStrings::add);
        }

        if (fileName.endsWith(".js") && !fileName.endsWith(".min.js")) {
            System.out.println("FILE " + file.getPath());
            reader(file).lines().filter((line) -> line.contains("\""))
                    .flatMap(extractKeysFunc(STRING))
                    .forEach(foundStrings::add);

            reader(file).lines().filter((line) -> line.contains("\'"))
                    .flatMap(extractKeysFunc(QSTRING))
                    .forEach(foundStrings::add);
        }
    }

    Function<String, Stream<String>> extractKeysFunc(Pattern pattern) {
        return (line) -> extractKeys(pattern, line);
    }

    protected Stream<String> extractKeys(Pattern pattern, String line) {
        List<String> res = new ArrayList<>();
        Matcher m = pattern.matcher(line);
        while (m.find()) {
            res.add(m.group(1));
        }
        return res.stream();
    }

    @NotNull
    protected BufferedReader reader(File file) {
        try {
            return new BufferedReader(new FileReader(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    Stream<File> childrenStream(File dir) {
        return Stream.concat(Stream.of(dir),
                Optional.ofNullable(dir.listFiles()).stream()
                        .map(Arrays::asList)
                        .flatMap(List::stream)
                        .filter((f) -> !f.getName().equals(".git"))
                        .filter((f) -> !f.getName().equals("target"))
                        .filter((f) -> !f.getName().equals("jslibs"))
                        .filter((f) -> !f.getPath().contains("/src/test/"))
                        .flatMap(this::childrenStream));
    }

    protected File topDir(String topdir) {
        File dir = Paths.get(relative, topdir).toFile();
        if (!dir.exists() || !dir.canRead()) {
            throw new IllegalArgumentException("Can't read " + dir.getAbsolutePath());
        }
        return dir;
    }

    protected String print(String line) {
        System.out.println(line);
        return line;
    }
}
