package dev.tomcatdev.run;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reloads a context of a running Tomcat through JMX (the "reload" operation of the WebModule MBean).
 * The JVM keeps running; only a new classloader is created, so new and structurally changed classes are loaded and Spring restarts.
 * Plain Java (javax.management), no IntelliJ dependency.
 */
public final class TomcatContextReloader {

    private TomcatContextReloader() {}

    /** A free TCP port on the loopback interface. */
    public static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        }
    }

    /** VM options enabling JMX on 127.0.0.1 only, without authentication (development use only). */
    public static List<String> jvmArgs(int port) {
        return List.of(
                "-Dcom.sun.management.jmxremote",
                "-Dcom.sun.management.jmxremote.port=" + port,
                "-Dcom.sun.management.jmxremote.rmi.port=" + port,
                "-Dcom.sun.management.jmxremote.host=127.0.0.1",
                "-Djava.rmi.server.hostname=127.0.0.1",
                "-Dcom.sun.management.jmxremote.authenticate=false",
                "-Dcom.sun.management.jmxremote.ssl=false",
                "-Dcom.sun.management.jmxremote.local.only=true");
    }

    /**
     * Reloads the context with the given path ("/" or "" = ROOT).
     *
     * @return the name of the reloaded MBean
     */
    public static String reload(int port, String contextPath) throws Exception {
        String wanted = normalize(contextPath);
        JMXServiceURL url = new JMXServiceURL("service:jmx:rmi:///jndi/rmi://127.0.0.1:" + port + "/jmxrmi");
        try (JMXConnector c = JMXConnectorFactory.connect(url, Map.of())) {
            MBeanServerConnection mbs = c.getMBeanServerConnection();
            Set<ObjectName> modules = mbs.queryNames(new ObjectName("*:j2eeType=WebModule,*"), null);
            for (ObjectName on : modules) {
                String name = on.getKeyProperty("name");            // e.g. //localhost/my-app or //localhost/
                if (name == null) continue;
                int idx = name.indexOf('/', 2);
                String path = idx < 0 ? "" : name.substring(idx);
                if (normalize(path).equals(wanted)) {
                    mbs.invoke(on, "reload", null, null);
                    return on.toString();
                }
            }
            throw new IllegalStateException("Context '" + (wanted.isEmpty() ? "/" : wanted)
                                            + "' not found in Tomcat (available: " + modules + ")");
        }
    }

    private static String normalize(String p) {
        String s = p == null ? "" : p.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (!s.isEmpty() && !s.startsWith("/")) s = "/" + s;
        return s;
    }
}
