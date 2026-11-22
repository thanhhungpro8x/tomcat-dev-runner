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
            List<Overlay> overlays        // built (filtered) files in target/<finalName> overriding the src copies
    ) {}

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
        return write(tomcatHome, base, serverXmlSource, httpPort, d, true, log);
    }

    /**
     * @param disableBrowserCache adds Tomcat's built-in ExpiresFilter to the GLOBAL conf/web.xml of the dev CATALINA_BASE:
     *                            browsers always revalidate (304 when unchanged), so rebuilt Angular files show up at once.
     *                            The project's own web.xml is not touched.
     */
    public static Path write(Path tomcatHome, Path base, Path serverXmlSource, Integer httpPort,
                             Deployment d, boolean disableBrowserCache, Consumer<String> log) throws Exception {
        Path conf = base.resolve("conf");
        deleteRecursively(conf);
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

        if (disableBrowserCache && Files.isRegularFile(conf.resolve("web.xml"))) {
            addNoCacheFilter(conf.resolve("web.xml"));
            log.accept("browser cache: disabled for development (ExpiresFilter in the CATALINA_BASE conf/web.xml)");
        }

        // 2) server.xml
        Path serverSrc = serverXmlSource != null ? serverXmlSource : homeConf.resolve("server.xml");
        Document server = parse(serverSrc);
        patchServer(server, httpPort, log);
        EngineHost eh = engineHost(server);
        save(server, conf.resolve("server.xml"));
        log.accept("server.xml ← " + serverSrc);

        // 3) context descriptor
        String name = descriptorName(d.contextPath());
        Path hostDir = conf.resolve(eh.engine()).resolve(eh.host());
        Files.createDirectories(hostDir);
        Path descriptor = hostDir.resolve(name + ".xml");
        Document ctx = buildContext(d, base, log);
        save(ctx, descriptor);
        log.accept("context " + displayPath(d.contextPath()) + " → " + descriptor);
        return descriptor;
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
        if (d.contextXmlSource() != null && Files.isRegularFile(d.contextXmlSource())) {
            doc = parse(d.contextXmlSource());
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

        // PreResources: the first declared entry wins when paths overlap.
        // 1) built files from target (filtered web.xml…) have the highest priority
        if (d.overlays() != null) {
            for (Overlay o : d.overlays()) {
                if (!Files.isRegularFile(o.file())) continue;
                res.appendChild(resourceSet(doc, "PreResources", "FileResourceSet", o.file(), o.webAppPath()));
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
        for (Path jar : d.jars()) {
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
        f.setExpandEntityReferences(false);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return f.newDocumentBuilder();
    }

    static Document parse(Path p) throws Exception {
        return builder().parse(p.toFile());
    }

    static Document newDocument() throws Exception {
        return builder().newDocument();
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
