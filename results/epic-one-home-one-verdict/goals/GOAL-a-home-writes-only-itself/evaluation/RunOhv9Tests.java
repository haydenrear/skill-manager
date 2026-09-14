///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//SOURCES /Users/hayde/IdeaProjects/wt-ohv-8/src/main/java/**/*.java
//SOURCES /Users/hayde/IdeaProjects/wt-ohv-8/src/test/java/**/*.java
//JAVA_OPTIONS -Dorg.slf4j.simpleLogger.defaultLogLevel=warn
//JAVA_OPTIONS -Dorg.slf4j.simpleLogger.showThreadName=false
//JAVA_OPTIONS -Dorg.slf4j.simpleLogger.showDateTime=false
//JAVA_OPTIONS -Dorg.slf4j.simpleLogger.levelInBrackets=true
//DEPS org.slf4j:slf4j-api:2.0.16
//DEPS info.picocli:picocli:4.7.6
//DEPS org.yaml:snakeyaml:2.3
//DEPS com.fasterxml.jackson.core:jackson-annotations:2.20
//DEPS com.fasterxml.jackson.core:jackson-databind:2.20.2
//DEPS com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.20.2
//DEPS org.tomlj:tomlj:1.1.1
//DEPS org.apache.commons:commons-compress:1.27.1
//DEPS org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r
//DEPS io.modelcontextprotocol.sdk:mcp:1.1.1
//DEPS org.slf4j:slf4j-simple:2.0.16
//DEPS io.opentelemetry:opentelemetry-sdk-extension-autoconfigure:1.62.0
//DEPS io.opentelemetry:opentelemetry-exporter-otlp:1.62.0
//DEPS org.springframework:spring-webmvc:6.1.13
//DEPS org.springframework:spring-test:6.1.13
//DEPS jakarta.servlet:jakarta.servlet-api:6.0.0
import dev.skillmanager.cli.installer.SkillScriptWriteThroughTest;
import dev.skillmanager.cli.installer.BinCliWritersDoNotFollowLinksTest;
public class RunOhv9Tests {
    public static void main(String[] args) throws Exception {
        System.setProperty("skill-manager.harness-cli.disabled", "true");
        int failures = 0;
        failures += SkillScriptWriteThroughTest.run();
        failures += BinCliWritersDoNotFollowLinksTest.run();
        System.out.println(failures == 0 ? "OHV-9 CASES: ALL PASSED" : "OHV-9 CASES: FAILURES " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
