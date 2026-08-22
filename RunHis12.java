///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//SOURCES src/main/java/**/*.java
//SOURCES src/test/java/**/*.java
//SOURCES ServerObservabilityContractTest.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/observability/ServerObservability.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/observability/ServerObservabilityFilter.java
//JAVA_OPTIONS -Dorg.slf4j.simpleLogger.defaultLogLevel=warn
//JAVA_OPTIONS -Dorg.slf4j.simpleLogger.showThreadName=false
//JAVA_OPTIONS -Dorg.slf4j.simpleLogger.showDateTime=false
//JAVA_OPTIONS -Dorg.slf4j.simpleLogger.levelInBrackets=true

// Mirror SkillManager.java's deps so the test sources compile against the
// same classpath as production. Keep the two lists in sync.
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
// Server-observability contract test dependencies; these are not part of the
// SkillManager.java CLI classpath mirrored above.
//DEPS org.springframework:spring-webmvc:6.1.13
//DEPS org.springframework:spring-test:6.1.13
//DEPS jakarta.servlet:jakarta.servlet-api:6.0.0

/**
 * The two suites HIS-12 (#161, #187) added, on their own.
 *
 * <p>SHIPPED ON PURPOSE. `results/epic-home-integrity-sync/probes/his-12/
 * vacuity-checks.txt` records every fix being disabled and the failure that
 * followed, and each record names this runner. A record naming a file that
 * does not exist cannot be re-run, which makes it a claim rather than
 * evidence -- and this epic's rule is that an assertion nobody ran against
 * broken code is not coverage. Re-run any V-number by applying the mutation
 * it names and running this.
 *
 * <p>`jbang RunTests.java` remains the full suite and the ticket's declared
 * local signal; this is the same cases, ~6s instead of ~4min.
 */
public class RunHis12 {
    public static void main(String[] args) throws Exception {
        int f = 0;
        f += dev.skillmanager.store.HomeDescriptorCliRemedyTest.run();
        f += dev.skillmanager.commands.ProjectSyncTypedRefusalTest.run();
        System.out.println(f == 0 ? "\nALL PASSED" : "\nFAILURES: " + f);
        if (f != 0) System.exit(1);
    }
}
