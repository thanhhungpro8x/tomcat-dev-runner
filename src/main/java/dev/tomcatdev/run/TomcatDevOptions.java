package dev.tomcatdev.run;

/** Options of a "Tomcat Dev" run configuration. Empty string = automatic. */
public final class TomcatDevOptions {
    public String tomcatHome = "";
    public String moduleName = "";
    public String contextPath = "/";
    public String httpPort = "";
    /** Template server.xml. Empty → auto-detect (tomcat7-maven-plugin, src/main/tomcatconf, CATALINA_HOME/conf). */
    public String serverXml = "";
    /** Webapp context.xml. Empty → auto-detect (CATALINA_HOME/conf/Catalina/localhost, Maven plugins, target, META-INF). */
    public String contextXml = "";
    public String alternativeJre = "";
    public String vmOptions = "";
    /** KEY=VALUE, separated by ; */
    public String envVars = "";
    /** Empty → detected from angular.json. "-" → do not mount Angular. */
    public String angularDist = "";
    public String angularMount = "/";
    public boolean startAngularWatch = false;
    /** Disable browser caching of static files (only in the dev CATALINA_BASE, the project is not modified). */
    public boolean disableBrowserCache = true;
}
