import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "dev.tomcatdev"
version = "2.3.0"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.idea.maven")
    }
    // Only needed to compile ProjectActivity (Continuation/Unit); the IDE provides it at runtime
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
}

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }
        }
    }

    // Marketplace signing: values come from environment variables, never from the repository.
    // CERTIFICATE_CHAIN and PRIVATE_KEY may be given as raw PEM or Base64-encoded PEM.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    // ./gradlew publishPlugin  (only for updates; the very first upload must be done manually on the website)
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    // ./gradlew verifyPlugin — binary compatibility check.
    // recommended() is not used: with this Gradle plugin version it tries to download "IntelliJ IDEA Community 2025.3+",
    // which no longer exists (unified distribution since 2025.3).
    // Checked instead: the oldest supported version (already downloaded for compilation) + the locally installed IDE.
    pluginVerification {
        ides {
            ide(IntelliJPlatformType.IntellijIdeaCommunity, providers.gradleProperty("platformVersion").get())
            val localIde = file(providers.gradleProperty("verifyLocalIde").getOrElse("/Applications/IntelliJ IDEA.app"))
            if (localIde.exists()) local(localIde)
        }
    }
}

tasks.withType<JavaCompile> { options.encoding = "UTF-8" }
