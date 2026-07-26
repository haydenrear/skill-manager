plugins {
    `java-library`
    `maven-publish`
}

group = "com.hayden"
version = "0.1.0"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
    withSourcesJar()
}

repositories { mavenCentral() }

dependencies {
    val openTelemetryVersion = "1.62.0"

    api("com.fasterxml.jackson.core:jackson-databind:2.20.2")
    api(platform("io.opentelemetry:opentelemetry-bom:$openTelemetryVersion"))
    api("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure")
    api("io.opentelemetry:opentelemetry-exporter-otlp")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "testgraphsdk-java"
        }
    }
}
