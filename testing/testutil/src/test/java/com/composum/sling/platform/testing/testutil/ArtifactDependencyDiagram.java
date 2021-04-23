package com.composum.sling.platform.testing.testutil;

import org.apache.commons.collections4.MultiMapUtils;
import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.collections4.iterators.NodeListIterator;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.commons.collections4.IteratorUtils.asIterable;
import static org.apache.commons.io.filefilter.FileFilterUtils.nameFileFilter;
import static org.apache.commons.io.filefilter.FileFilterUtils.notFileFilter;
import static org.apache.commons.lang3.StringUtils.defaultIfBlank;

/**
 * Creates a diagram with a dependency of artefacts.
 */
public class ArtifactDependencyDiagram {

    private final MultiValuedMap<String, String> deps = MultiMapUtils.newListValuedHashMap();
    private final Set<String> artifacts = new TreeSet<>();
    private final Path outputFile;
    private final DocumentBuilder builder;
    private final XPath xpathEnvironment;

    public static void main(String[] args) throws Exception {
        ArtifactDependencyDiagram runner = new ArtifactDependencyDiagram(args);
        runner.createDiagram();
    }

    public ArtifactDependencyDiagram(String[] args) throws Exception {
        outputFile = Paths.get(args[0]);
        System.out.println("Writing to " + outputFile.toAbsolutePath());
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        builder = factory.newDocumentBuilder();
        XPathFactory xPathfactory = XPathFactory.newInstance();
        xpathEnvironment = xPathfactory.newXPath();
        for (String dir : Arrays.asList(args).subList(1, args.length)) {
            processDirectory(dir);
        }
    }

    private void processDirectory(String dirPath) throws IOException, SAXException, XPathExpressionException {
        File dir = new File(dirPath);
        if (!dir.canRead()) {
            throw new IllegalArgumentException("Cannot read: " + dir.getAbsolutePath());
        }
        System.out.println("Scanning directory " + dir.getAbsolutePath());
        for (File pom : FileUtils.listFiles(dir, nameFileFilter("pom.xml"), notFileFilter(nameFileFilter("target")))) {
            Document doc = builder.parse(pom);
            String artifactId = getXpathAsString(doc, "/project/artifactId");
            String groupId = defaultIfBlank(getXpathAsString(doc, "/project/groupId"),
                    getXpathAsString(doc, "/project/parent/groupId"));
            String version = defaultIfBlank(getXpathAsString(doc, "/project/version"),
                    getXpathAsString(doc, "/project/parent/version"));
            String name = getXpathAsString(doc, "/project/name");
            String packaging = defaultIfBlank(getXpathAsString(doc, "/project/packaging"), "jar");
            String artifactKey = groupId + ":" + artifactId;
            if (deps.containsKey(artifactKey))
                throw new IllegalArgumentException("Bug: Artifact already parsed: " + artifactKey);
            System.out.println(pom + "\t" + artifactKey + "\n" + name + "\t" + packaging + "\t" + version);
            if (!"pom".equals(packaging)) {
                artifacts.add(artifactKey);
            }

            XPathExpression dependencyExpr = xpathEnvironment.compile("/project/dependencies//artifactId|/project/build/plugins//artifactId|/project/build/plugins//dependencies/dependency/name");
            NodeList dependencyList = (NodeList) dependencyExpr.evaluate(doc, XPathConstants.NODESET);
            for (Node artifactIdNode : asIterable(new NodeListIterator(dependencyList))) {
                String depId = artifactIdNode.getTextContent().trim();
                String depGroup = null;
                String type = null;
                for (Node artifactIdSibling : asIterable(new NodeListIterator(artifactIdNode.getParentNode()))) {
                    if ("groupId".equals(artifactIdSibling.getNodeName())) {
                        depGroup = artifactIdSibling.getTextContent().trim();
                    }
                    if ("group".equals(artifactIdSibling.getNodeName()) && depGroup == null) { // package dependencies
                        depGroup = artifactIdSibling.getTextContent().trim().replace("/", ".");
                    }
                    if ("type".equals(artifactIdSibling.getNodeName())) {
                        type = artifactIdSibling.getTextContent().trim();
                    }
                }
                depGroup = depGroup.replaceAll(Pattern.quote("${project.groupId}"), groupId);
                if (depGroup.contains("$")) {
                    throw new IllegalArgumentException("Unknown group id: " + depGroup);
                }
                // System.out.println("\t" + depGroup + ":" + depId + ":" + type);
                if (!StringUtils.isAnyBlank(artifactId, groupId, depId, depGroup)) {
                    deps.put(artifactKey, depGroup + ":" + depId);
                }
            }
        }
    }

    private Map<String, XPathExpression> exprCache = new HashMap<>();

    private String getXpathAsString(Document doc, String xpath) throws XPathExpressionException {
        XPathExpression expr = exprCache.computeIfAbsent(xpath, (path) -> {
            try {
                return xpathEnvironment.compile(path);
            } catch (XPathExpressionException e) {
                throw new RuntimeException(e);
            }
        });
        return StringUtils.trim((String) expr.evaluate(doc, XPathConstants.STRING));
    }

    private void createDiagram() throws FileNotFoundException {
        List<Map.Entry<String, String>> filteredEntries = new ArrayList<>();
        for (Map.Entry<String, String> entry : deps.entries()) {
            if (deps.keySet().contains(entry.getValue())) {
                filteredEntries.add(entry);
            }
        }
        filteredEntries = filteredEntries.stream()
                .distinct()
                .sorted(Map.Entry.comparingByValue())
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toList());
        Set<String> allKeys = Stream.concat(
                filteredEntries.stream().map(Map.Entry::getKey),
                filteredEntries.stream().map(Map.Entry::getValue)
        ).collect(Collectors.toSet());

        // String prefix = StringUtils.getCommonPrefix(allKeys.toArray(new String[0]));
        // String artPrefix = StringUtils.getCommonPrefix(allKeys.stream().map(k -> StringUtils.substringAfter(k, ":")).toArray((l) -> new String[l]));
        // System.out.println("Common prefixes :  " + prefix + "\t" + artPrefix);

        System.out.println("\n\ndigraph componentree {");
        System.out.println("node [shape=\"box\",style=\"rounded\"];");
        for (Map.Entry<String, String> entry : filteredEntries) {
            System.out.println('"' + entry.getKey() + "\" -> \"" + entry.getValue() + "\" ;");
        }
        for (String artifact : artifacts) {
            System.out.println('"' + artifact + "\" ;");
        }
        System.out.println("}");

//        if (Files.exists(outputFile)) {
//            throw new IllegalArgumentException("Output file exists: " + outputFile.toAbsolutePath());
//        }
        try (PrintWriter out = new PrintWriter(outputFile.toFile())) {
            out.println("digraph componentree {");
            out.println("node [shape=\"box\",style=\"rounded\"];");
            for (Map.Entry<String, String> entry : filteredEntries) {
                out.println('"' + entry.getKey() + "\" -> \"" + entry.getValue() + "\" ;");
            }
            for (String artifact : artifacts) {
                out.println('"' + artifact + "\" ;");
            }
            out.println("}");
            System.out.println("Wrote diagram to " + outputFile.toAbsolutePath());
            System.out.println("Draw e.g. with:\n");
            String outName = outputFile.getFileName().toString();
            String outPng = outName.replaceAll("\\..*", ".png");
            System.out.println("ccomps -x " + outName + " | tred | dot | gvpack | neato -Tpng -n2 -o " + outPng + " ; open " + outPng);
        }
    }

}
