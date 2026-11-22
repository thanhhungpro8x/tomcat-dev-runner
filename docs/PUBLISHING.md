# Publishing to JetBrains Marketplace

This checklist covers the first release and later updates of Dev Runner for Apache Tomcat.
Official references: [Publishing a Plugin](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html),
[Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html),
[Approval Guidelines](https://plugins.jetbrains.com/docs/marketplace/jetbrains-marketplace-approval-guidelines.html),
[Best practices for listing](https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html).

## 1. Decide before the first upload (cannot easily change later)

| Item | Where | Notes |
|---|---|---|
| **Plugin ID** | `plugin.xml` `<id>` | Permanent once published. Chosen: `fr.thanhhungdoan.tomcatdevrunner`. |
| **Plugin name** | `plugin.xml` `<name>` | Chosen: "Dev Runner for Apache Tomcat" (descriptive use of the Apache Tomcat trademark). |
| **Vendor** | `plugin.xml` `<vendor>` | Currently `Thanh-Hung DOAN` with the LinkedIn profile as `url`. Optionally add `email="…"` to show a contact address; links must be reachable. |
| **License** | `LICENSE` + upload form | Mandatory. This repository uses MIT. |
| **Source code link** | Upload form / admin page | Required with an open-source license: https://github.com/thanhhungpro8x/tomcat-dev-runner (public). |

## 2. Prepare the release

1. Bump `version` in `build.gradle.kts`.
2. Update `<change-notes>` in `plugin.xml`. The first sentence must be in English.
3. Build and check:
   ```bash
   ./gradlew buildPlugin verifyPlugin
   ```
   `verifyPlugin` checks binary compatibility against `platformVersion` (2024.2.5) and the IDE installed at
   `verifyLocalIde` in `gradle.properties` (default `/Applications/IntelliJ IDEA.app`).
4. Test the zip from `build/distributions/` in a clean IDE (or `./gradlew runIde`).

## 3. Sign the plugin (recommended)

Unsigned plugins install with a warning. Generate a key and a self-signed certificate once:

```bash
openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
openssl rsa -in private_encrypted.pem -out private.pem
openssl req -key private.pem -new -x509 -days 365 -out chain.crt
```

Keep these files **outside the repository** (`*.pem`, `*.crt` are git-ignored). Provide them as environment variables;
multi-line PEM can be Base64-encoded:

```bash
export CERTIFICATE_CHAIN="$(base64 < chain.crt)"
export PRIVATE_KEY="$(base64 < private.pem)"
export PRIVATE_KEY_PASSWORD='…'        # password chosen during genpkey
./gradlew signPlugin                    # → build/distributions/tomcat-dev-runner-<version>-signed.zip
```

## 4. First upload (manual)

The first version must be uploaded through the website:

1. Sign in at <https://plugins.jetbrains.com> with your JetBrains Account.
2. Accept the Developer Agreement when prompted.
3. Account menu › **Upload plugin**.
4. Choose the vendor profile (personal or organization).
5. Upload the signed zip, select the license, choose the category (for example *Deployment* or *Tools Integration*), and add tags.
6. Submit. Automatic checks run immediately. Then the plugin is reviewed by the Marketplace team; contact
   marketplace@jetbrains.com if there is no answer within 3–4 working days.

After approval, add on the plugin page: source code and issue tracker links, and screenshots (at least 1200×760,
default IDE theme, no personal data), for example the run configuration, the tool window log, and the settings page.

## 5. Later updates (automated)

1. Create a token: Marketplace › your profile › **My Tokens** › Generate Token.
2. Publish:
   ```bash
   export PUBLISH_TOKEN='perm:…'
   ./gradlew publishPlugin          # runs signPlugin first when the signing variables are set
   ```
   Updates are also reviewed before they become available.

## 6. After publishing

- Users who installed a zip from disk can reinstall from Marketplace. This also removes the
  "Cannot update plugin — unable to identify where it was installed from" notification.
- Keep `sinceBuild` / `untilBuild` and `verifyPlugin` in mind when new IntelliJ IDEA versions are released.
