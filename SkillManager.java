///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//SOURCES src/main/java/**/*.java
// SLF4J default config for the CLI: WARN everywhere, no threads/dates in output.
// The MCP SDK uses SLF4J — override any of these with -D at the jbang command.
//JAVA_OPTIONS -Dorg.slf4j.simpleLogger.defaultLogLevel=warn
//JAVA_OPTIONS -Dorg.slf4j.simpleLogger.showThreadName=false
//JAVA_OPTIONS -Dorg.slf4j.simpleLogger.showDateTime=false
//JAVA_OPTIONS -Dorg.slf4j.simpleLogger.levelInBrackets=true
//JAVA_OPTIONS -Dorg.slf4j.simpleLogger.log.io.modelcontextprotocol.client.LifecycleInitializer=warn

// Pin SLF4J 2.x before any dep that might drag slf4j-api 1.7 (e.g., jgit's transitives).
//DEPS org.slf4j:slf4j-api:2.0.16
//DEPS info.picocli:picocli:4.7.6
//DEPS org.yaml:snakeyaml:2.3
// Jackson 2.20 — what io.modelcontextprotocol.sdk:mcp-core:1.1.1 pulls in for
// jackson-annotations; keep databind + yaml on the same version line to avoid
// the com.fasterxml.jackson.* API drift across 2.x minor releases.
//DEPS com.fasterxml.jackson.core:jackson-annotations:2.20
//DEPS com.fasterxml.jackson.core:jackson-databind:2.20.2
//DEPS com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.20.2
//DEPS org.tomlj:tomlj:1.1.1
//DEPS org.apache.commons:commons-compress:1.27.1
//DEPS org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r
//DEPS io.modelcontextprotocol.sdk:mcp:1.1.1
//DEPS org.slf4j:slf4j-simple:2.0.16

import dev.skillmanager.cli.SkillManagerCli;

public class SkillManager {
    public static void main(String[] args) {
        System.exit(SkillManagerCli.run(args));
    }
}
