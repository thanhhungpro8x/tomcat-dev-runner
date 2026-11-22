# Dev Runner for Apache Tomcat

An IntelliJ IDEA plugin that runs a Maven WAR module on a local Apache Tomcat 10/11 **straight from your build output**,
with hot reload of Java and Angular changes. It works in the free edition of IntelliJ IDEA and requires no changes
to your project.

- **No packaging, no copying.** Tomcat reads `target/classes` of the WAR module and of every jar module it depends on,
  the Maven libraries, `src/main/webapp`, and the Angular build output in place.
- **Hot reload.** Save a Java file → its module is compiled → HotSwap when debugging. Structural changes
  (new fields, methods, endpoints, beans) trigger an automatic **context reload** in a few seconds instead of a restart.
- **Your configuration, auto-detected.** Tomcat, `server.xml` and `context.xml` are found where they usually live
  (CATALINA_HOME, tomcat-maven-plugin, maven-war-plugin, META-INF). Maven-filtered files are taken from
  `target/<finalName>`. JNDI resources and parameters are preserved.
- **Angular friendly.** Start `ng build --watch` from the IDE; browser caching is disabled in the development Tomcat.

## Quick start

1. `mvn clean package` once.
2. *Run › Edit Configurations… › + › Tomcat Dev*: check the detected Tomcat, pick the WAR module, set the context path.
3. Start it with **Debug** and open the **Tomcat Dev** tool window.
4. Edit a Java file and save.

Full documentation: **[docs/USER_GUIDE.md](docs/USER_GUIDE.md)**

## Requirements

IntelliJ IDEA 2024.2+ (free edition or Ultimate) · Apache Tomcat 10.x / 11.x installed locally · JDK 17+ ·
a Maven project with a `war` module.

## Building from source

```bash
./gradlew buildPlugin      # → build/distributions/tomcat-dev-runner-<version>.zip
./gradlew runIde           # sandbox IDE with the plugin installed
./gradlew verifyPlugin     # binary compatibility check against recommended IDE versions
```

Install the zip through *Settings › Plugins › ⚙ › Install Plugin from Disk…*.
Publishing to JetBrains Marketplace is described in [docs/PUBLISHING.md](docs/PUBLISHING.md).

## Project layout

| Path | Purpose |
|---|---|
| `run/TomcatDevConfigurationType`, `TomcatDevRunConfiguration`, `TomcatDevSettingsEditor` | Run configuration and its UI |
| `run/TomcatDevState` | Generates CATALINA_BASE and builds the `java … Bootstrap start` command |
| `run/DeploymentResolver` | Modules and libraries from the project model; locating `server.xml` / `context.xml` |
| `run/CatalinaBaseWriter` | Writes `conf/`, patches `server.xml`, merges the context descriptor (plain Java) |
| `run/TomcatContextReloader` | Context reload through JMX (plain Java) |
| `run/TomcatHomes` | Tomcat auto-detection and version |
| `hotreload/*` | Compile on save, HotSwap / reload / restart, Angular watch, tool window, settings |
| `actions/*` | Menu and toolbar actions |

## Source code and issues

<https://github.com/thanhhungpro8x/tomcat-dev-runner> — bug reports and suggestions: <https://github.com/thanhhungpro8x/tomcat-dev-runner/issues>

## Author

**Thanh-Hung DOAN** — Île-de-France, France
[LinkedIn](https://www.linkedin.com/in/thanh-hung-doan/)

## License

[MIT](LICENSE)
