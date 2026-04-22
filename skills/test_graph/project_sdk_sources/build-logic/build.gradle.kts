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
    // Node specs come from invoking scripts with --describe-out, parsed by
    // MiniJson.kt. No YAML, no JSON library — plugin stays dependency-free.
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
