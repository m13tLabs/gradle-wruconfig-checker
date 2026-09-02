plugins {
    `java-gradle-plugin`
    kotlin("jvm") version "1.9.25"
}

group = "de.m13t.gradle"
version = findProperty('releaseVersion') ?: '0.0.1-SNAPSHOT'

repositories { mavenCentral() }

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.5")
    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins {
        create("wrunconfigVerify") {
            id = "de.m13t.wrunconfig-verify"
            implementationClass = "de.m13t.wrunconfig.WrunconfigVerifyPlugin"
            displayName = "wrunconfig classpath verifier"
            description = "Verifies classpath entries and main class in MSIX-Power-Wrapper .wrunconfig files."
        }
    }
}

kotlin { jvmToolchain(17) }

tasks.test { useJUnitPlatform() }
