package dev.tomcatdev.run;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Generates a dedicated development CATALINA_BASE (plain Java, no IntelliJ dependency):
 * <ul>
 *   <li>conf/* copied from CATALINA_HOME/conf (except server.xml and descriptor folders)</li>
 *   <li>conf/server.xml: taken from the source file (configured / auto-detected), ports patched</li>
 *   <li>conf/&lt;Engine&gt;/&lt;Host&gt;/&lt;name&gt;.xml: the original context.xml merged with mounts for classes, jars and Angular static files</li>
 * </ul>
 * Tomcat reads straight from the source folders, so nothing is copied or packaged.
 */
public final class CatalinaBaseWriter {

    private CatalinaBaseWriter() {}

    /** A webapp to deploy. */
    public record Deployment(
            String contextPath,
            Path docBase,                 // src/main/webapp (null or missing → empty folder)
            List<Path> classDirs,         // target/classes of the WAR module + its jar module dependencies
            List<Path> jars,              // runtime libraries (provided/test scopes excluded)
            Path contextXmlSource,        // original context.xml to merge (nullable)
            Path staticDir,               // Angular dist (nullable)
            String staticMount,           // where static files are mounted in the webapp, default "/"
            List<Overlay> overlays,       // built (filtered) files in target/<finalName> overriding the src copies
            Path localDir,                // per-app configuration folder outside the project (nullable / may not exist)
            Map<String, String> filterValues // values for ${...} templates (empty → no filtering by the plugin)
    ) {
        /** Single-app constructor without local configuration (kept for callers/tests of earlier versions). */
        public Deployment(String contextPath, Path docBase, List<Path> classDirs, List<Path> jars, Path contextXmlSource,
                          Path staticDir, String staticMount, List<Overlay> overlays) {
            this(contextPath, docBase, classDirs, jars, contextXmlSource, staticDir, staticMount, overlays, null, Map.of());
        }

        public String appName() {
            return descriptorName(contextPath);
        }
    }

    /** A file from the exploded WAR used instead of the src/main/webapp copy (e.g. a filtered WEB-INF/web.xml). */
    public record Overlay(Path file, String webAppPath) {}

    public record EngineHost(String engine, String host) {}

    /** Jars not added to WEB-INF/lib because Tomcat already provides them (avoids classloader conflicts). */
    private static final Pattern CONTAINER_JARS = Pattern.compile(
            "^(tomcat-embed-.*|tomcat-annotations-api.*|(javax|jakarta)\\.servlet-api.*|servlet-api.*|"
            + "(javax|jakarta)\\.servlet\\.jsp-api.*|jsp-api.*|(jakarta\\.)?el-api.*|jakarta\\.el-api.*|"
            + "(javax|jakarta)\\.websocket-(client-)?api.*|websocket-api.*)\\.jar$");

    public static boolean isContainerJar(String fileName) {
        return CONTAINER_JARS.matcher(fileName).matches();
    }

    /**
     * Writes the whole CATALINA_BASE.
     *
     * @param serverXmlSource template server.xml (null → CATALINA_HOME/conf/server.xml)
     * @param httpPort        null → keep the port from server.xml
     */
    public static Path write(Path tomcatHome, Path base, Path serverXmlSource, Integer httpPort,
                             Deployment d, Consumer<String> log) throws Exception {
        return write(tomcatHome, base, serverXmlSource, httpPort, List.of(d), true, log).get(0);
    }

    public static Path write(Path tomcatHome, Path base, Path serverXmlSource, Integer httpPort,
                             Deployment d, boolean disableBrowserCache, Consumer<String> log) throws Exception {
        return write(tomcatHome, base, serverXmlSource, httpPort, List.of(d), disableBrowserCache, log).get(0);
    }

    /**
     * @param disableBrowserCache adds Tomcat's built-in ExpiresFilter to the GLOBAL conf/web.xml of the dev CATALINA_BASE:
     *                            browsers always revalidate (304 when unchanged), so rebuilt Angular files show up at once.
     *                            The project's own web.xml is not touched.
     */
    public static List<Path> write(Path tomcatHome, Path base, Path serverXmlSource, Integer httpPort,
                                   List<Deployment> deployments, boolean disableBrowserCache,
                                   Consumer<String> log) throws Exception {
        Path conf = base.resolve("conf");
        deleteRecursively(conf);
        deleteRecursively(base.resolve("filtered"));
        for (String dir : new String[]{"conf", "logs", "temp", "webapps", "work"}) {
            Files.createDirectories(base.resolve(dir));
        }

        // 1) conf/* from CATALINA_HOME (web.xml, global context.xml, catalina.properties, logging.properties…)
        Path homeConf = tomcatHome.resolve("conf");
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(homeConf)) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (Files.isRegularFile(p) && !n.equals("server.xml")) {
                    Files.copy(p, conf.resolve(n), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        // resource sets in the global context.xml that point to missing files would stop every webapp
        Path globalCtx = conf.resolve("context.xml");
        if (Files.isRegularFile(globalCtx)) {
            Document g = parse(globalCtx);
            if (removeMissingResourceSets(g, "conf/context.xml", log)) save(g, globalCtx);
        }

        if (disableBrowserCache && Files.isRegularFile(conf.resolve("web.xml"))) {
            addNoCacheFilter(conf.resolve("web.xml"));
            log.accept("browser cache: disabled for development (ExpiresFilter in the CATALINA_BASE conf/web.xml)");
        }

        // 2) server.xml
        Path serverSrc = serverXmlSource != null ? serverXmlSource : homeConf.resolve("server.xml");
        Document server = parse(serverSrc);
        patchServer(server, httpPort, log);
        removeMissingResourceSets(server, "server.xml", log);
        EngineHost eh = engineHost(server);
        save(server, conf.resolve("server.xml"));
        log.accept("server.xml ← " + serverSrc);

        // 3) one context descriptor per webapp
        Path hostDir = conf.resolve(eh.engine()).resolve(eh.host());
        Files.createDirectories(hostDir);
        // host-level defaults shared by every webapp of the host (merged by Tomcat with each descriptor)
        Path hostDefault = homeConf.resolve(eh.engine()).resolve(eh.host()).resolve("context.xml.default");
        if (Files.isRegularFile(hostDefault)) {
            Files.copy(hostDefault, hostDir.resolve("context.xml.default"), StandardCopyOption.REPLACE_EXISTING);
            log.accept("context.xml.default ← " + hostDefault);
        }
        List<Path> descriptors = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Deployment d : deployments) {
            String name = descriptorName(d.contextPath());
            if (!names.add(name)) {
                throw new IllegalArgumentException("Two deployments use the same context path: " + displayPath(d.contextPath()));
            }
            Path descriptor = hostDir.resolve(name + ".xml");
            Document ctx = buildContext(d, base, log);
            save(ctx, descriptor);
            log.accept("context " + displayPath(d.contextPath()) + " → " + descriptor);
            descriptors.add(descriptor);
        }
        return descriptors;
    }

    // ------------------------------------------------------------------ server.xml

    static void patchServer(Document doc, Integer httpPort, Consumer<String> log) {
        Element server = doc.getDocumentElement();
        // Disable the shutdown port: the IDE stops the process directly, and this avoids 8005 clashes between Tomcats
        server.setAttribute("port", "-1");

        if (httpPort != null) {
            NodeList connectors = doc.getElementsByTagName("Connector");
            for (int i = 0; i < connectors.getLength(); i++) {
                Element c = (Element) connectors.item(i);
                String protocol = c.getAttribute("protocol");
                if (protocol.isEmpty() || protocol.contains("HTTP/1.1") || protocol.contains("Http11")) {
                    if (!"true".equalsIgnoreCase(c.getAttribute("SSLEnabled"))) {
                        c.setAttribute("port", String.valueOf(httpPort));
                        log.accept("HTTP port = " + httpPort);
                        break;
                    }
                }
            }
        }
    }

    /** Adds ExpiresFilter "access plus 0 seconds" → Cache-Control: max-age=0 on responses that set no caching headers. */
    static void addNoCacheFilter(Path webXml) throws Exception {
        Document doc = parse(webXml);
        Element root = doc.getDocumentElement();
        // already there (a conf/web.xml copied from a generated CATALINA_BASE): adding it twice stops every webapp
        NodeList names = doc.getElementsByTagName("filter-name");
        for (int i = 0; i < names.getLength(); i++) {
            if ("tomcatDevNoCache".equals(names.item(i).getTextContent().trim())) return;
        }
        Element filter = doc.createElement("filter");
        filter.appendChild(text(doc, "filter-name", "tomcatDevNoCache"));
        filter.appendChild(text(doc, "filter-class", "org.apache.catalina.filters.ExpiresFilter"));
        Element param = doc.createElement("init-param");
        param.appendChild(text(doc, "param-name", "ExpiresDefault"));
        param.appendChild(text(doc, "param-value", "access plus 0 seconds"));
        filter.appendChild(param);
        Element mapping = doc.createElement("filter-mapping");
        mapping.appendChild(text(doc, "filter-name", "tomcatDevNoCache"));
        mapping.appendChild(text(doc, "url-pattern", "/*"));
        mapping.appendChild(text(doc, "dispatcher", "REQUEST"));
        // the container filter must come before the first filter-mapping in the file (if any)
        NodeList firstMapping = root.getElementsByTagName("filter-mapping");
        if (firstMapping.getLength() > 0) {
            root.insertBefore(filter, firstMapping.item(0));
        } else {
            root.appendChild(filter);
        }
        root.appendChild(mapping);
        save(doc, webXml);
    }

    private static Element text(Document doc, String tag, String value) {
        Element e = doc.createElement(tag);
        e.setTextContent(value);
        return e;
    }

    public static EngineHost engineHost(Document server) {
        NodeList engines = server.getElementsByTagName("Engine");
        String engine = "Catalina", host = "localhost";
        if (engines.getLength() > 0) {
            Element e = (Element) engines.item(0);
            if (!e.getAttribute("name").isEmpty()) engine = e.getAttribute("name");
            String def = e.getAttribute("defaultHost");
            NodeList hosts = e.getElementsByTagName("Host");
            String first = null;
            for (int i = 0; i < hosts.getLength(); i++) {
                String hn = ((Element) hosts.item(i)).getAttribute("name");
                if (first == null) first = hn;
                if (hn.equals(def)) {
                    first = hn;
                    break;
                }
            }
            if (first != null && !first.isEmpty()) host = first;
            else if (!def.isEmpty()) host = def;
        }
        return new EngineHost(engine, host);
    }

    public static EngineHost engineHost(Path serverXml) {
        try {
            return engineHost(parse(serverXml));
        } catch (Exception e) {
            return new EngineHost("Catalina", "localhost");
        }
    }

    // ------------------------------------------------------------------ context

    static Document buildContext(Deployment d, Path base, Consumer<String> log) throws Exception {
        Document doc;
        Map<String, String> values = d.filterValues() == null ? Map.of() : d.filterValues();
        if (d.contextXmlSource() != null && Files.isRegularFile(d.contextXmlSource())) {
            // parse the file itself first: entity includes are resolved relative to it, then filtered like the rest
            String text = toXmlString(parse(d.contextXmlSource()));
            Set<String> keys = PlaceholderFilter.placeholders(text);
            if (!keys.isEmpty() && !values.isEmpty()) {
                text = PlaceholderFilter.apply(text, values);
                Set<String> left = PlaceholderFilter.placeholders(text);
                log.accept("context.xml filtered by the plugin (" + (keys.size() - left.size()) + "/" + keys.size() + " placeholders)"
                           + (left.isEmpty() ? "" : " — ⚠ unresolved: " + left));
            } else if (!keys.isEmpty()) {
                log.accept("⚠ context.xml has unresolved placeholders " + keys + " and no values are available");
            }
            doc = parseString(text);
            if (!"Context".equals(doc.getDocumentElement().getTagName())) {
                throw new IllegalArgumentException("The root element of " + d.contextXmlSource() + " is not <Context>");
            }
            log.accept("original context.xml ← " + d.contextXmlSource());
        } else {
            doc = newDocument();
            doc.appendChild(doc.createElement("Context"));
            log.accept("original context.xml: none (using an empty context)");
        }
        Element ctx = doc.getDocumentElement();

        Path docBase = d.docBase();
        if (docBase == null || !Files.isDirectory(docBase)) {
            docBase = base.resolve("empty-docbase").resolve(descriptorName(d.contextPath()));
            Files.createDirectories(docBase);
            log.accept("docBase: the module has no src/main/webapp → using an empty folder");
        }
        ctx.removeAttribute("path");
        ctx.setAttribute("docBase", abs(docBase));
        ctx.setAttribute("reloadable", "false");

        Element res = firstChild(ctx, "Resources");
        if (res == null) {
            res = doc.createElement("Resources");
            ctx.appendChild(res);
        }
        res.setAttribute("cachingAllowed", "false");
        removeMissingResourceSets(res, "context.xml", log);

        // PreResources: the first declared entry wins when paths overlap.
        Set<String> mountedFiles = new HashSet<>();
        Path filteredRoot = base.resolve("filtered").resolve(d.appName());

        // 1) local configuration outside the project (highest priority)
        Path local = d.localDir();
        if (local != null && Files.isDirectory(local)) {
            log.accept("local config ← " + local);
            Path localWebapp = local.resolve("webapp");
            Path localClasses = local.resolve("classes");
            // templates in the local folder are filtered too; filtered copies are mounted file by file
            mountFilteredTemplates(doc, res, localWebapp, "/", filteredRoot.resolve("local-webapp"), values, mountedFiles, "local", log);
            mountFilteredTemplates(doc, res, localClasses, "/WEB-INF/classes/", filteredRoot.resolve("local-classes"), values, mountedFiles, "local", log);
            if (Files.isDirectory(localWebapp)) {
                res.appendChild(resourceSet(doc, "PreResources", "DirResourceSet", localWebapp, "/"));
                log.accept("local webapp / ← " + localWebapp);
            }
            if (Files.isDirectory(localClasses)) {
                res.appendChild(resourceSet(doc, "PreResources", "DirResourceSet", localClasses, "/WEB-INF/classes"));
                log.accept("local classes /WEB-INF/classes ← " + localClasses);
            }
        }

        // 2) templates from src/main/webapp/WEB-INF filtered by the plugin (fresh, no Maven run needed)
        Path srcWebInf = d.docBase() == null ? null : d.docBase().resolve("WEB-INF");
        if (!values.isEmpty() && srcWebInf != null && Files.isDirectory(srcWebInf)) {
            mountFilteredTemplates(doc, res, srcWebInf, "/WEB-INF/", filteredRoot.resolve("webapp/WEB-INF"), values, mountedFiles, "src", log);
        }

        // 3) built files from target/<finalName> (Maven-filtered or generated), unless already provided above
        if (d.overlays() != null) {
            for (Overlay o : d.overlays()) {
                if (!Files.isRegularFile(o.file()) || mountedFiles.contains(o.webAppPath())) continue;
                res.appendChild(resourceSet(doc, "PreResources", "FileResourceSet", o.file(), o.webAppPath()));
                mountedFiles.add(o.webAppPath());
                log.accept("using built file: " + o.webAppPath() + " ← " + o.file());
            }
        }
        if (d.staticDir() != null) {
            if (Files.isDirectory(d.staticDir())) {
                String mount = normalizeMount(d.staticMount());
                res.appendChild(resourceSet(doc, "PreResources", "DirResourceSet", d.staticDir(), mount));
                log.accept("static " + mount + " ← " + d.staticDir());
            } else {
                log.accept("⚠ Angular dist does not exist yet: " + d.staticDir() + " (build Angular, then restart to mount it)");
            }
        }
        int dirs = 0;
        for (Path cd : d.classDirs()) {
            if (!Files.isDirectory(cd)) {
                log.accept("⚠ skipped (not built yet): " + cd);
                continue;
            }
            res.appendChild(resourceSet(doc, "PreResources", "DirResourceSet", cd, "/WEB-INF/classes"));
            dirs++;
        }
        Set<String> used = new HashSet<>();
        int jars = 0, skipped = 0;
        List<Path> allJars = new ArrayList<>();
        if (local != null && Files.isDirectory(local.resolve("lib"))) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(local.resolve("lib"), "*.jar")) {
                for (Path j : ds) {
                    allJars.add(j);
                    log.accept("local lib ← " + j.getFileName());
                }
            }
        }
        allJars.addAll(d.jars());
        for (Path jar : allJars) {
            String fn = jar.getFileName().toString();
            if (!Files.isRegularFile(jar) || !fn.endsWith(".jar")) continue;
            if (isContainerJar(fn)) {
                skipped++;
                continue;
            }
            String mountName = fn;
            for (int i = 2; !used.add(mountName); i++) mountName = i + "_" + fn;
            res.appendChild(resourceSet(doc, "PostResources", "FileResourceSet", jar, "/WEB-INF/lib/" + mountName));
            jars++;
        }
        log.accept("mount: " + dirs + " classes folders, " + jars + " jars" + (skipped > 0 ? " (" + skipped + " container jars skipped)" : ""));
        return doc;
    }

    /**
     * Resource sets already declared in the source context.xml (often an old hand-written or IDE-generated descriptor)
     * that point to a file or folder that no longer exists, e.g. a jar of an older version in ~/.m2.
     * Tomcat refuses to start the context for these, so they are dropped with a warning.
     * Entries with ${...} or a relative base are kept: Tomcat resolves them itself.
     */
    static boolean removeMissingResourceSets(Document doc, String origin, Consumer<String> log) {
        boolean changed = false;
        NodeList all = doc.getElementsByTagName("Resources");
        List<Element> list = new ArrayList<>();
        for (int i = 0; i < all.getLength(); i++) list.add((Element) all.item(i));
        for (Element r : list) changed |= removeMissingResourceSets(r, origin, log);
        return changed;
    }

    static boolean removeMissingResourceSets(Element res, String origin, Consumer<String> log) {
        List<Element> toRemove = new ArrayList<>();
        for (Node n = res.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element e)) continue;
            String tag = e.getTagName();
            if (!tag.equals("PreResources") && !tag.equals("PostResources")
                && !tag.equals("JarResources") && !tag.equals("ClassResources")) continue;
            String b = e.getAttribute("base");
            if (b.isEmpty() || b.contains("${")) continue;
            try {
                Path p = Paths.get(b);
                if (p.isAbsolute() && !Files.exists(p)) toRemove.add(e);
            } catch (Exception ignored) {
                // not a valid path on this OS: let Tomcat report it
            }
        }
        for (Element e : toRemove) {
            res.removeChild(e);
            log.accept("⚠ ignored " + e.getTagName() + " from " + origin + ", file not found: " + e.getAttribute("base"));
        }
        return !toRemove.isEmpty();
    }

    /**
     * For every filterable file under {@code root} that contains ${...} placeholders resolvable with {@code values}:
     * writes a filtered copy under {@code outRoot} and mounts it at {@code mountPrefix + relative path}.
     * WEB-INF/classes and WEB-INF/lib below a webapp root are skipped.
     */
    private static void mountFilteredTemplates(Document doc, Element res, Path root, String mountPrefix, Path outRoot,
                                               Map<String, String> values, Set<String> mountedFiles, String origin,
                                               Consumer<String> log) throws IOException {
        if (root == null || !Files.isDirectory(root) || values.isEmpty()) return;
        List<Path> files;
        try (var st = Files.walk(root)) {
            files = st.filter(Files::isRegularFile).filter(PlaceholderFilter::isFilterable).sorted().toList();
        }
        for (Path f : files) {
            String rel = root.relativize(f).toString().replace('\\', '/');
            String mount = mountPrefix + rel;
            if (mount.startsWith("/WEB-INF/lib/") || (mountPrefix.equals("/WEB-INF/") && (rel.startsWith("classes/") || rel.startsWith("lib/")))
                || mountedFiles.contains(mount)) continue;
            String text = Files.readString(f, StandardCharsets.UTF_8);
            Set<String> keys = PlaceholderFilter.placeholders(text);
            if (keys.isEmpty()) continue;
            String filtered = PlaceholderFilter.apply(text, values);
            Set<String> left = PlaceholderFilter.placeholders(filtered);
            Path out = outRoot.resolve(rel);
            Files.createDirectories(out.getParent());
            Files.writeString(out, filtered, StandardCharsets.UTF_8);
            res.appendChild(resourceSet(doc, "PreResources", "FileResourceSet", out, mount));
            mountedFiles.add(mount);
            log.accept("filtered (" + origin + ") " + mount + (left.isEmpty() ? "" : " — ⚠ unresolved: " + left));
        }
    }

    private static Element resourceSet(Document doc, String tag, String cls, Path base, String mount) {
        Element e = doc.createElement(tag);
        e.setAttribute("className", "org.apache.catalina.webresources." + cls);
        e.setAttribute("base", abs(base));
        e.setAttribute("webAppMount", mount);
        return e;
    }

    /** "/" → ROOT, "/app" → app, "/a/b" → a#b (Tomcat naming convention). */
    public static String descriptorName(String contextPath) {
        String p = contextPath == null ? "" : contextPath.trim();
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p.isEmpty() ? "ROOT" : p.replace('/', '#');
    }

    static String displayPath(String contextPath) {
        String n = descriptorName(contextPath);
        return n.equals("ROOT") ? "/" : "/" + n.replace('#', '/');
    }

    private static String normalizeMount(String m) {
        if (m == null || m.isBlank()) return "/";
        String s = m.trim();
        if (!s.startsWith("/")) s = "/" + s;
        if (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    // ------------------------------------------------------------------ xml utils

    private static DocumentBuilder builder() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        // Tomcat (Digester) resolves XML entity includes such as
        //   <!DOCTYPE Server [ <!ENTITY connectors SYSTEM "connectors.xml"> ]> … &connectors;
        // relative to the including file. Expand them here too: the generated file is self-contained.
        f.setExpandEntityReferences(true);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return f.newDocumentBuilder();
    }

    static Document parse(Path p) throws Exception {
        Document doc = builder().parse(p.toFile());
        removeXmlBase(doc.getDocumentElement());
        return doc;
    }

    /** The parser marks expanded includes with xml:base; Tomcat would log a warning for each of them. */
    private static void removeXmlBase(Element e) {
        e.removeAttribute("xml:base");
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element c) removeXmlBase(c);
        }
    }

    static Document parseString(String xml) throws Exception {
        return builder().parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));
    }

    static Document newDocument() throws Exception {
        return builder().newDocument();
    }

    static String toXmlString(Document doc) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        try {
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        } catch (IllegalArgumentException ignored) {
        }
        Transformer t = tf.newTransformer();
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        java.io.StringWriter w = new java.io.StringWriter();
        t.transform(new DOMSource(doc), new StreamResult(w));
        return w.toString();
    }

    static void save(Document doc, Path out) throws Exception {
        stripWhitespace(doc.getDocumentElement());
        TransformerFactory tf = TransformerFactory.newInstance();
        try {
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        } catch (IllegalArgumentException ignored) {
        }
        Transformer t = tf.newTransformer();
        t.setOutputProperty(OutputKeys.INDENT, "yes");
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        Files.createDirectories(out.getParent());
        t.transform(new DOMSource(doc), new StreamResult(out.toFile()));
    }

    private static void stripWhitespace(Node n) {
        NodeList kids = n.getChildNodes();
        for (int i = kids.getLength() - 1; i >= 0; i--) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.TEXT_NODE && k.getTextContent().isBlank()) n.removeChild(k);
            else if (k.getNodeType() == Node.ELEMENT_NODE) stripWhitespace(k);
        }
    }

    private static Element firstChild(Element parent, String tag) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) instanceof Element e && e.getTagName().equals(tag)) return e;
        }
        return null;
    }

    private static String abs(Path p) {
        return p.toAbsolutePath().normalize().toString();
    }

    static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        Files.walkFileTree(p, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.delete(f);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
