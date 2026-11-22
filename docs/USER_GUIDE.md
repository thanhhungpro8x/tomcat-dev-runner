# Dev Runner for Apache Tomcat — User Guide

Dev Runner for Apache Tomcat runs a Maven WAR module on a local Apache Tomcat 10 or 11 directly from your build output, and keeps
the running application up to date while you edit Java and Angular code. It is built for multi-module Maven projects
(a WAR module plus reusable jar modules, often Spring Boot with an Angular front end), works in the free edition of
IntelliJ IDEA, and requires **no changes to your project**.

---

## Contents

1. [Why this plugin](#1-why-this-plugin)
2. [Requirements](#2-requirements)
3. [Installation](#3-installation)
4. [Quick start](#4-quick-start)
5. [Run configuration reference](#5-run-configuration-reference)
6. [Project settings reference](#6-project-settings-reference)
7. [How it works](#7-how-it-works)
8. [Hot reload in detail](#8-hot-reload-in-detail)
9. [Angular workflow](#9-angular-workflow)
10. [Projects that use Maven resource filtering](#10-projects-that-use-maven-resource-filtering)
11. [Enhanced HotSwap with JetBrains Runtime and HotswapAgent](#11-enhanced-hotswap-with-jetbrains-runtime-and-hotswapagent)
12. [Tool window and actions](#12-tool-window-and-actions)
13. [Troubleshooting](#13-troubleshooting)
14. [Limitations](#14-limitations)
15. [Security notes](#15-security-notes)

---

## 1. Why this plugin

The usual local setup for a Spring Boot WAR with an Angular front end looks like this:

1. Build Angular, copy the output into the webapp.
2. Package the WAR (or an exploded WAR) with Maven.
3. Deploy it to Tomcat through a run configuration.

Every change then requires repeating part of that pipeline. Classes compiled by the IDE land in `target/classes`,
but Tomcat runs from `target/<finalName>/WEB-INF`, so it never sees them. Angular output has to be copied again.
Jar modules are packaged inside `WEB-INF/lib`, so a change in a shared module needs a full rebuild.

Dev Runner for Apache Tomcat removes the pipeline from the development loop. Instead of copying files into a deployment folder,
it configures Tomcat to **read your build output where it already is**:

| Source in your project | Seen by Tomcat as | Effect of a change |
|---|---|---|
| `target/classes` of the WAR module | `/WEB-INF/classes` | Save → compile → HotSwap or context reload |
| `target/classes` of every jar module the WAR depends on | `/WEB-INF/classes` | Same as above, no repackaging |
| Maven runtime libraries in `~/.m2` | `/WEB-INF/lib/*.jar` | — |
| `src/main/webapp` | Webapp root (`docBase`) | Visible immediately |
| Angular build output (from `angular.json`) | Webapp root `/` | Visible after the watch build finishes |
| Maven-filtered files in `target/<finalName>` | Their usual path (`/WEB-INF/web.xml`, …) | Picked up at the next reload/restart |

Nothing is copied and nothing is packaged. The project's files and its `pom.xml` are never modified.

---

## 2. Requirements

| Item | Version |
|---|---|
| IntelliJ IDEA | 2024.2 or newer, including the free edition (formerly Community) |
| Bundled plugins | Java, Maven (both enabled by default) |
| Apache Tomcat | 10.x or 11.x (Jakarta EE). Tomcat 9 starts but is only suitable for `javax.*` applications |
| JDK | 11+ for Tomcat 10.1, 17+ for Tomcat 11; Java 21 recommended |
| Project | Maven project with one module using `war` packaging |
| Angular (optional) | Any version with an `angular.json`; Node.js version supported by your Angular release |

Tomcat must be installed locally: a downloaded `apache-tomcat-x.y.z` folder (Core `tar.gz`/`zip`) or a Homebrew install.
The plugin does not download Tomcat.

---

## 3. Installation

**From JetBrains Marketplace:** *Settings › Plugins › Marketplace*, search for **Dev Runner for Apache Tomcat**, click *Install*.

**From disk** (a build of this repository):

```bash
./gradlew buildPlugin
# → build/distributions/tomcat-dev-runner-<version>.zip
```

Then *Settings › Plugins › ⚙ › Install Plugin from Disk…* and select the zip. When installed from disk, IntelliJ IDEA
2025.3+ may show "Cannot update plugin — unable to identify where it was installed from". This is informational only:
the plugin works normally. Choose a "do not update" option, or install from Marketplace to receive updates.

---

## 4. Quick start

1. **Build the project once** so that the module output folders exist:
   ```bash
   mvn clean package
   ```
   (Projects that filter `context.xml` with Maven also need this step to produce the filtered copy; see
   [section 10](#10-projects-that-use-maven-resource-filtering).)
2. **Create a run configuration:** *Run › Edit Configurations… › + › Tomcat Dev*.
   - *Tomcat home* — leave empty if the information line underneath shows an auto-detected Tomcat; otherwise browse to it.
   - *Module WAR* — preselected when the project has a module with `war` packaging.
   - *Context path* — for example `/my-app`, or `/` for the root context.
   - Leave all other fields empty.
3. **Start it with Debug** (the bug icon). Debug is required for HotSwap; context reload works in Run mode as well.
4. **Open the Tomcat Dev tool window** (bottom of the IDE). The startup log shows what was detected and mounted:
   ```
   ──────── my-app ────────
   Tomcat 11.0.29.0 @ /Users/you/apps/apache-tomcat-11.0.29
   found server.xml [CATALINA_HOME/conf]: …/conf/server.xml
   found context.xml [filtered: target/my-app]: …/target/my-app/META-INF/context.xml
   modules: [my-app-web, my-app-core]
   browser cache: disabled for development (ExpiresFilter in the CATALINA_BASE conf/web.xml)
   static / ← …/frontend/../target/frontend
   mount: 2 classes folders, 87 jars (3 container jars skipped)
   CATALINA_BASE = …/tomcat-dev/…/my-app
   JMX (context reload) on 127.0.0.1:53817
   ```
5. **Edit and save a Java file.** The log shows `Compile: [module]` followed by `HotSwap OK ✔` or a context reload.

---

## 5. Run configuration reference

*Run › Edit Configurations… › Tomcat Dev*

| Field | Default | Description |
|---|---|---|
| **Tomcat home** | auto-detect | Tomcat installation folder (contains `bin/bootstrap.jar` and `lib/catalina.jar`). The line underneath shows the detected version. |
| **Module WAR** | first `war` module | The IntelliJ module to deploy. Its jar module dependencies are added automatically. |
| **Context path** | `/` | Web context path, for example `/my-app`. `/` deploys as `ROOT`. |
| **HTTP port** | from `server.xml` | Overrides the port of the first non-SSL HTTP connector. |
| **server.xml** | auto-detect | Template `server.xml`. See [7.3](#73-how-serverxml-is-found). |
| **context.xml** | auto-detect | Webapp context descriptor to merge. See [7.4](#74-how-contextxml-is-found). |
| **Alternative JRE** | module SDK | JDK/JRE used to run Tomcat. Point it to a JetBrains Runtime for enhanced HotSwap. |
| **VM options** | — | Extra JVM options for Tomcat, for example `-Xmx2g -Dspring.profiles.active=dev`. |
| **Environment** | — | Environment variables, `KEY=VALUE;KEY2=VALUE2`. The IDE environment is inherited. |
| **Angular dist** | from `angular.json` | Folder containing the built Angular files. `-` disables the Angular mount. |
| **Mount Angular at** | `/` | Path inside the webapp where Angular files are served. |
| **Start Angular watch together with Tomcat** | off | Starts the Angular watch command (see [6](#6-project-settings-reference)) on every launch. |
| **Disable browser caching of static files** | on | Adds a no-cache filter to the development Tomcat. See [9.3](#93-browser-caching). |

**Before launch.** New configurations get the standard *Build* step, which compiles the modules before Tomcat starts.
You can add a Maven goal there (see [10](#10-projects-that-use-maven-resource-filtering)).

**Configuration storage.** Like any run configuration, it is stored in `.idea/workspace.xml` unless you tick
*Store as project file*.

---

## 6. Project settings reference

*Settings › Tools › Dev Runner for Apache Tomcat* — stored per project in `.idea/workspace.xml`.

| Setting | Default | Description |
|---|---|---|
| **Enable hot reload for this project** | on | Master switch for compile-on-save and reloading. The *Enable Hot Reload* toggle action does the same. |
| **Compile the changed module on save** | on | When a Java or resource file under a source root is saved, compile only the module it belongs to. |
| **Only compile on save while Tomcat Dev is running** | on | Avoids background compilation when no Tomcat Dev configuration is running. |
| **Debounce (ms)** | 500 | Waiting time after the last save before compiling, so that several quick saves give one compilation. |
| **After compilation** | HotSwap, fall back to context reload | What to do with compiled classes. See [8.2](#82-reload-modes). |
| **Angular project dir** | auto-detect | Folder containing `angular.json`. Searched up to four levels below the project root, skipping `node_modules`, `target`, `dist`, `.git`. |
| **Angular watch command** | `npx ng build --watch --configuration development` | Command started by *Start Angular Watch*. Runs in the Angular project folder with your shell environment. |

When hot reload is enabled, the plugin sets *Settings › Build, Execution, Deployment › Debugger › HotSwap ›
Reload classes after compilation* to **Never**. It performs the HotSwap itself, and leaving the IDE option on would
reload twice or show a prompt after each compilation. This is an IDE-wide setting; restore it manually if you
uninstall the plugin.

---

## 7. How it works

### 7.1 The development CATALINA_BASE

Tomcat distinguishes `CATALINA_HOME` (the installation: binaries and libraries) from `CATALINA_BASE`
(the instance: configuration, logs, work files). On each launch the plugin generates a private `CATALINA_BASE`
inside the IDE system folder, for example on macOS:

```
~/Library/Caches/JetBrains/IntelliJIdea2026.3/tomcat-dev/<project-hash>/<configuration-name>/
├── conf/
│   ├── server.xml                    ← template + patched ports
│   ├── web.xml                       ← copied from CATALINA_HOME + optional no-cache filter
│   ├── context.xml, catalina.properties, logging.properties, …   ← copied from CATALINA_HOME
│   └── Catalina/localhost/<name>.xml ← generated context descriptor
├── logs/  temp/  webapps/ (empty)  work/
```

Your Tomcat installation and your project are not modified. *Tools › Dev Runner for Apache Tomcat › Open Generated
CATALINA_BASE* opens this folder.

Tomcat is started as a normal Java process:

```
java -cp $CATALINA_HOME/bin/bootstrap.jar:$CATALINA_HOME/bin/tomcat-juli.jar
     -Dcatalina.home=… -Dcatalina.base=… -Djava.io.tmpdir=…/temp
     -Djava.util.logging.manager=org.apache.juli.ClassLoaderLogManager
     -Djava.util.logging.config.file=…/conf/logging.properties
     -Djava.protocol.handler.pkgs=org.apache.catalina.webresources
     --add-opens=… (same as catalina.sh)
     -Dcom.sun.management.jmxremote… (127.0.0.1 only, see section 15)
     <your VM options>
     org.apache.catalina.startup.Bootstrap start
```

Because this is a regular Java run configuration, the IDE's **Run** and **Debug** buttons, console, and debugger work
as usual.

### 7.2 The generated context descriptor

The file `conf/Catalina/localhost/<name>.xml` is built from your own `context.xml` (if any) and extended with Tomcat
*resource sets*:

```xml
<Context docBase="/…/web-module/src/main/webapp" reloadable="false">
  <!-- everything from your context.xml is preserved: Environment, Resource, Parameter, … -->
  <Parameter name="application.environment" value="development" override="false"/>
  <Resources cachingAllowed="false">
    <!-- 1. built files that replace templates from src (e.g. filtered web.xml) -->
    <PreResources className="org.apache.catalina.webresources.FileResourceSet"
                  base="/…/target/my-app/WEB-INF/web.xml" webAppMount="/WEB-INF/web.xml"/>
    <!-- 2. Angular output -->
    <PreResources className="org.apache.catalina.webresources.DirResourceSet"
                  base="/…/target/frontend" webAppMount="/"/>
    <!-- 3. module classes: WAR module first, then jar modules -->
    <PreResources className="org.apache.catalina.webresources.DirResourceSet"
                  base="/…/web-module/target/classes" webAppMount="/WEB-INF/classes"/>
    <PreResources className="org.apache.catalina.webresources.DirResourceSet"
                  base="/…/core-module/target/classes" webAppMount="/WEB-INF/classes"/>
    <!-- 4. one entry per runtime library -->
    <PostResources className="org.apache.catalina.webresources.FileResourceSet"
                   base="~/.m2/repository/…/spring-web-6.2.8.jar"
                   webAppMount="/WEB-INF/lib/spring-web-6.2.8.jar"/>
  </Resources>
</Context>
```

- `cachingAllowed="false"` makes Tomcat read files from disk on every request, so edits are visible immediately.
- `reloadable="false"`: reloading is controlled by the plugin, not by Tomcat's periodic class scanning.
- Libraries are mounted as real `WEB-INF/lib` jars, so `ServletContainerInitializer` discovery (the mechanism Spring
  Boot WAR deployments use to start) and `web-fragment.xml` scanning work as in a packaged WAR.
- Libraries with `provided` or `test` scope are excluded, as are jars that Tomcat already provides
  (`tomcat-embed-*`, Servlet/JSP/EL/WebSocket API jars).

### 7.3 How server.xml is found

When the *server.xml* field is empty, the first match wins:

1. `<serverXml>` of `tomcat7-maven-plugin` / `tomcat8-maven-plugin` in the WAR module's `pom.xml`
2. `<war-module>/src/main/tomcatconf/server.xml`
3. `<war-module>/src/main/tomcat/server.xml`
4. `CATALINA_HOME/conf/server.xml`, the installation's own file, including any `GlobalNamingResources` you configured there

The plugin then patches the copy:

- `<Server port>` is set to `-1` (no shutdown port), so several Tomcats can run side by side without fighting over 8005.
  The IDE stops Tomcat directly.
- The first non-SSL HTTP connector gets the *HTTP port* from the run configuration, if one is set.

Everything else is kept: connectors, realms, valves, global resources. JDBC drivers placed in `CATALINA_HOME/lib`
remain on the common classpath.

### 7.4 How context.xml is found

When the *context.xml* field is empty, the first match wins. The order follows Tomcat's own precedence:

1. `CATALINA_HOME/conf/<Engine>/<Host>/<name>.xml` — a descriptor already deployed in your Tomcat for this context
2. `<contextFile>` of `tomcat7/8-maven-plugin`
3. `<containerConfigXML>` of `maven-war-plugin`
4. `target/<finalName>/META-INF/context.xml` — **the copy filtered by Maven**, if it contains no unresolved `${…}`
5. `src/main/webapp/META-INF/context.xml` — a warning is logged if it still contains Maven placeholders
6. `src/main/resources/META-INF/context.xml`

`${catalina.*}`, `${java.*}`, `${user.*}` and `${os.*}` are not treated as Maven placeholders, because Tomcat
resolves them itself.

### 7.5 Files taken from the built WAR

Files under `target/<finalName>/WEB-INF` (except `classes/` and `lib/`) are used instead of `src/main/webapp` when:

- the `src` copy is a template containing Maven `${…}` placeholders, or
- the file exists only in `target` (generated during the build, for example by `maven-war-plugin` `webResources`).

Files that are not filtered keep being served from `src/main/webapp`, so changes to them show up immediately.
`target/<finalName>/WEB-INF/classes` and `lib` are always ignored, because they may be stale. Classes always come
from each module's `target/classes`.

### 7.6 Module and library resolution

Modules and libraries come from IntelliJ's project model (imported from Maven): the WAR module, then all modules it
depends on recursively, restricted to production runtime scope. Module output folders are the IntelliJ compiler
output paths, which for Maven projects are `target/classes`. A module whose output folder does not exist yet is
skipped with a warning; build the project and restart.

### 7.7 Tomcat auto-detection

When *Tomcat home* is empty, these locations are checked and the newest version found is used:

- `CATALINA_HOME`, `TOMCAT_HOME` environment variables
- Homebrew: `/opt/homebrew/opt/tomcat*/libexec`, `/usr/local/opt/tomcat*/libexec`
- Folders whose name contains `tomcat` in `~`, `~/apps`, `~/Applications`, `~/tools`, `/opt`, `/usr/local`, `/Library`
  (one level of nesting, for example `~/apps/tomcat/apache-tomcat-11.0.29`)

The version is read from `lib/catalina.jar`.

---

## 8. Hot reload in detail

### 8.1 The three mechanisms

| Mechanism | What it can apply | Time | State kept | Requires |
|---|---|---|---|---|
| **HotSwap** (JVM class redefinition) | Method bodies only (standard JVM) | Instant | Everything: sessions, beans, caches | Debug mode |
| **Context reload** (new classloader for the webapp) | Any change: new fields, methods, classes, endpoints, beans, configuration classes | 2–5 s for a typical Spring Boot app | JVM, Tomcat, debugger connection | JMX (enabled automatically) |
| **Restart** (new Tomcat process) | Everything, including `context.xml` and `server.xml` | Slowest | Nothing | — |

A context reload is the equivalent of redeploying the webapp: Tomcat stops it, discards its classloader, creates a new
one, and starts it again. Because classes are read directly from `target/classes`, the new classloader sees the
latest compiled classes of every module. Spring Boot starts again inside the same JVM, which is much faster than a
full Tomcat restart.

### 8.2 Reload modes

*Settings › Tools › Dev Runner for Apache Tomcat › After compilation*

| Mode | Method body changed | Structural change | Running without Debug |
|---|---|---|---|
| **HotSwap, fall back to context reload** (default) | HotSwap | Context reload | Context reload |
| **Always reload the context** | Context reload | Context reload | Context reload |
| **HotSwap, notify on failure** | HotSwap | Notification with *Reload Context* and *Restart Tomcat* buttons | Notification |
| **HotSwap, restart Tomcat on failure** | HotSwap | Restart | Restart |

The default mode keeps application state whenever possible and still never leaves you with stale code.

### 8.3 What happens when you save

1. A file under a source root is saved (Java or resources; test sources are ignored).
2. After the debounce delay, only the affected modules are compiled. Changes to several modules are compiled together.
3. When compilation succeeds, the selected reload mode is applied. On compilation errors nothing is reloaded and the
   log says so.
4. If more compilations finish while a context reload is running, they are folded into one additional reload.

Compilations you start yourself (*Build › Build Project*, ⌘F9 / Ctrl+F9) trigger the same reload logic.

### 8.4 Which changes need what

| Change | Applied by |
|---|---|
| Method body in any module | HotSwap (or context reload) |
| New field, method, record component, class, annotation | Context reload |
| New `@RestController`, `@Bean`, `@Configuration` | Context reload |
| `application.properties` / `application.yml` in `src/main/resources` | Context reload (Spring reads it at startup) |
| Static files in `src/main/webapp` | Immediately, nothing to do |
| Angular output | Immediately after the watch build finishes |
| Filtered `WEB-INF/web.xml` | Re-run Maven filtering, then context reload |
| `context.xml`, `server.xml`, run configuration fields | Restart (the descriptor is generated at launch) |
| Dependency added to `pom.xml` | Reload the Maven project in IntelliJ, then restart |

---

## 9. Angular workflow

### 9.1 Watch build served by Tomcat

The plugin mounts the Angular build output read from `angular.json`: the `outputPath` of the build target, plus
`/browser` for the `application` builder (Angular 17+) unless `browser` is set to `""`. Keep that folder up to date
with a watch build:

- *Tools › Dev Runner for Apache Tomcat › Start Angular Watch*, or ▶ in the Tomcat Dev tool window (output appears in the same log), or
- tick *Start Angular watch together with Tomcat* in the run configuration, or
- run it yourself: `npx ng build --watch --configuration development`

Make sure the configuration you watch writes to the same folder the plugin mounts. Check the `static / ← …` line of the
startup log. The mounted folder must exist when Tomcat starts; build Angular once (or run `mvn package`) before the
first launch.

### 9.2 Angular dev server

For pure UI work, the Angular dev server is faster because it updates the browser by itself:

```bash
npx ng serve --proxy-config proxy.conf.json
```

with a proxy that forwards API calls to Tomcat Dev, for example:

```json
{ "/api": { "target": "http://localhost:8080/my-app", "secure": false, "changeOrigin": true } }
```

The dev server keeps its output in memory and does not write to the folder Tomcat serves, so pages served by Tomcat on
port 8080 do not change while only `ng serve` is running.

### 9.3 Browser caching

Development builds often use fixed file names (`main.js`, `styles.css`). Browsers may then keep using a cached copy
after a rebuild. With *Disable browser caching of static files* on (the default), the plugin adds Tomcat's built-in
`ExpiresFilter` to the **global** `conf/web.xml` of the development `CATALINA_BASE`:

```xml
<filter>
  <filter-name>tomcatDevNoCache</filter-name>
  <filter-class>org.apache.catalina.filters.ExpiresFilter</filter-class>
  <init-param><param-name>ExpiresDefault</param-name><param-value>access plus 0 seconds</param-value></init-param>
</filter>
```

Responses get `Cache-Control: max-age=0`: the browser revalidates every file and receives `304 Not Modified` when it
has not changed, so a plain F5 is enough after a rebuild. Responses for which the application sets its own caching
headers are left alone. Your project's `web.xml` is not modified.

### 9.4 Node.js version

The watch command uses your shell environment, so it runs with the Node.js version that is active in a new terminal.
Angular CLI refuses unsupported versions (for example odd-numbered releases). With nvm: `nvm alias default 22`.

---

## 10. Projects that use Maven resource filtering

Many projects keep templates with `${…}` placeholders in `src/main/webapp` (for example `META-INF/context.xml` or
`WEB-INF/web.xml`) and let `maven-war-plugin` replace them per environment. The plugin uses the **filtered copies**
from `target/<finalName>` (see [7.4](#74-how-contextxml-is-found) and [7.5](#75-files-taken-from-the-built-war)).
Those copies must exist and be current:

- Run the WAR build once before the first launch.
- After changing a filter properties file or a template, run it again and restart (for `context.xml`) or reload (for `web.xml`).

To regenerate them automatically on each launch, add a Maven goal under **Before launch** in the run configuration,
for example:

```
package -DskipTests -Dskip.npm -Dskip.installnodenpm
```

Avoid `clean` in this goal: it deletes the folders that Tomcat and the Angular watch are using, and makes every launch
and restart rebuild the front end. The `-Dskip.*` flags apply to `frontend-maven-plugin`; drop them if you do not use it.

---

## 11. Enhanced HotSwap with JetBrains Runtime and HotswapAgent

With a standard JVM, HotSwap is limited to method bodies, and the plugin falls back to a context reload. To redefine
classes structurally without any reload:

1. Install a **JetBrains Runtime (JBR) 21** and set it as *Alternative JRE* in the run configuration.
2. Download `hotswap-agent.jar` from the HotswapAgent releases and place it at `<JBR>/lib/hotswap/hotswap-agent.jar`.
3. Add these *VM options*:
   ```
   -XX:+AllowEnhancedClassRedefinition -XX:HotswapAgent=fatjar
   ```
4. Run with **Debug**.

HotSwap then accepts new fields and methods, and HotswapAgent's Spring support refreshes affected beans. Changes it
cannot handle still fall back to a context reload in the default mode.

---

## 12. Tool window and actions

**Tomcat Dev tool window** (bottom): plugin log with launch details, compilations, HotSwap and reload results, and the
Angular watch output. The toolbar contains the actions below.

**Tools › Dev Runner for Apache Tomcat menu:**

| Action | Description |
|---|---|
| Enable Hot Reload | Toggles compile-on-save and reloading for the project |
| Reload Context | Reloads the running webapp now (JMX) |
| Restart Tomcat | Restarts the running Tomcat Dev configuration (runs *Before launch* steps) |
| Open Generated CATALINA_BASE | Opens the generated configuration folder (`conf`, `logs`) |
| Start / Stop Angular Watch | Runs or stops the Angular watch command |

Notifications for failed HotSwaps or reloads offer *Reload Context* and *Restart Tomcat* buttons.

---

## 13. Troubleshooting

| Symptom / log message | Cause | Fix |
|---|---|---|
| *No Tomcat installation found automatically* | Tomcat is not in a detected location | Set *Tomcat home* |
| *Invalid Tomcat home* | Folder is not a Tomcat root | Select the folder that contains `bin/` and `lib/` |
| Nothing is logged when saving a Java file | Hot reload disabled, file outside a source root, or no Tomcat Dev running | Check *Settings › Tools › Dev Runner for Apache Tomcat*; build manually with ⌘F9 / Ctrl+F9 |
| *Tomcat is running in Run mode, so HotSwap is not available* | Started with Run | Expected in Run mode; the default mode reloads the context instead. Use Debug for HotSwap |
| *No JMX port known for '…'* | Tomcat was started before the plugin was installed or updated | Stop the configuration and start it again |
| *Context reload failed: …* | Application failed to start after reload, or JMX unreachable | Check the run console for the startup exception; use *Restart Tomcat* |
| *⚠ skipped (not built yet): …/target/classes* | Module never compiled | Build the project, then restart |
| *⚠ Angular dist does not exist yet* | Angular not built before launch | Build Angular once, then restart |
| *⚠ … is a template with unresolved ${…}* | Maven filtering has not run | Run the WAR build ([section 10](#10-projects-that-use-maven-resource-filtering)) |
| API returns new data but the page does not change | Browser cache, or you are looking at the `ng serve` port instead of Tomcat (or the reverse) | Keep *Disable browser caching* on; hard-reload once (⌘⇧R / Ctrl+Shift+R); check the URL |
| Angular watch fails with `ERR_REQUIRE_ESM` or a Node version message | Unsupported Node.js version | Switch to a supported LTS (see [9.4](#94-nodejs-version)) |
| `Address already in use` | Port taken by another server | Set *HTTP port* in the run configuration |
| Classes from `WEB-INF/lib` of an old build are used | — | Not possible: `target/<finalName>/WEB-INF/lib` and `classes` are never mounted |

The generated configuration and Tomcat's own log files are in the folder opened by *Open Generated CATALINA_BASE*
(`conf/` and `logs/`).

---

## 14. Limitations

- One WAR module per run configuration. Create several configurations with different ports to run several webapps.
- WAR overlays (dependencies of type `war`) are not merged.
- Non-Maven projects work for classes and libraries; `src/main/webapp` is located from the module content root, and
  Maven-specific detection (plugin configuration, filtered files) is skipped.
- A context reload discards HTTP sessions and in-memory application state.
- Repeated context reloads can accumulate memory if the application leaks classloaders (threads, drivers, caches
  that are not shut down). Restart Tomcat occasionally if memory grows.

---

## 15. Security notes

- JMX is enabled for context reloads on a random free port, bound to `127.0.0.1`, **without authentication or SSL**.
  Any process running under a user account on the same machine can connect to it while Tomcat Dev is running. This is
  intended for local development only; never use the generated configuration on a shared or production server.
- The no-cache filter and the disabled shutdown port only exist in the generated development `CATALINA_BASE`.
- The plugin does not send data anywhere and makes no network requests.

---

**Author:** Thanh-Hung DOAN — Île-de-France, France · [LinkedIn](https://www.linkedin.com/in/thanh-hung-doan/) · License: MIT
