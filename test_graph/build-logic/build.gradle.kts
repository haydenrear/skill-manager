plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

group = "com.hayden.testgraphsdk"
version = "0.1.0"

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    val openTelemetryVersion = "1.62.0"

    implementation(platform("io.opentelemetry:opentelemetry-bom:$openTelemetryVersion"))
    implementation("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")

    // Node specs come from invoking scripts with --describe-out, parsed by
    // MiniJson.kt. No YAML sidecar is required.
    testImplementation(kotlin("test"))
}

gradlePlugin {
    plugins {
        register("validation-graph") {
            id = "com.hayden.testgraphsdk.graph"
            implementationClass = "com.hayden.testgraphsdk.ValidationGraphPlugin"
            displayName = "Validation Graph"
            description = "Orchestrates polyglot validation nodes as a Gradle-managed graph."
        }
    }
}
