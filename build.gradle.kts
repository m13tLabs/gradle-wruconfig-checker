import com.vanniktech.maven.publish.GradlePublishPlugin

plugins {
    `java-gradle-plugin`
    kotlin("jvm") version "2.4.10"
    id("com.gradle.plugin-publish") version "2.1.1"
    id("com.vanniktech.maven.publish") version "0.35.0"
}

group = "de.m13t.oss"
version = findProperty("releaseVersion") ?: "0.0.1-SNAPSHOT"

repositories { mavenCentral() }

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.5")
    testImplementation(gradleTestKit())
}

mavenPublishing {
    configure(GradlePublishPlugin())

    coordinates(group.toString(), "msixwrapper-verify-gradle-plugin", version.toString())

    publishToMavenCentral()
    signAllPublications()

    pom {
        name = "MSIX-Power-Wrapper Check Plugin"
        description = "A Gradle plugin that verifies the MSIX-Power-Wrapper project settings"
        url = "https://github.com/m13tLabs/gradle-wruconfig-checker"

        licenses {
            license {
                name = "MIT License"
                url = "https://mit-license.org"
            }
        }

        developers {
            developer {
                id = "mreinhardt"
                name = "Martin Reinhardt"
                email = "martin@m13t.de"
            }
        }

        scm {
            url = "https://github.com/m13tLabs/gradle-wruconfig-checker"
            connection = "scm:git:git://github.com/m13tLabs/gradle-wruconfig-checker.git"
            developerConnection = "scm:git:ssh://git@github.com/m13tLabs/gradle-wruconfig-checker.git"
        }
    }
}

gradlePlugin {
    website = "https://github.com/m13tLabs/gradle-wruconfig-checker"
    vcsUrl = "https://github.com/m13tLabs/gradle-wruconfig-checker.git"
    plugins {
        create("wrunconfigVerify") {
            id = "de.m13t.wrunconfig-verify"
            implementationClass = "de.m13t.wrunconfig.WrunconfigVerifyPlugin"
            displayName = "wrunconfig classpath verifier"
            description = "Verifies classpath entries and main class in MSIX-Power-Wrapper .wrunconfig files."
            tags.set(listOf("java", "code quality", "msix", "wru", "MSIX-Power-Wrapper"))
        }
    }
}

kotlin { jvmToolchain(17) }

tasks.test { useJUnitPlatform() }
