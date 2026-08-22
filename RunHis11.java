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
 * The suite HIS-11 (#186) added, on its own.
 *
 * <p>SHIPPED ON PURPOSE, for the reason RunHis12 states: every record in
 * results/epic-home-integrity-sync/probes/his-11/vacuity-checks.txt names
 * this runner, and a record naming a file that does not exist is a claim
 * rather than evidence. Re-run any V-number by applying the mutation it
 * names and running this.
 *
 * <p>jbang RunTests.java remains the full suite and the ticket's declared
 * local signal; this is the three compensation suites, seconds instead of
 * minutes. CompensationPairingTest and FailureInjectionSweepTest are in
 * here because they are what a bad pre-image escrow would break FIRST --
 * a vacuity run that only ever reddens the new file has not checked that
 * the fix left the existing walk-back contract alone.
 */
public class RunHis11 {
    public static void main(String[] args) throws Exception {
        int f = 0;
        f += dev.skillmanager.effects.CommitPreImageRestoreTest.run();
        f += dev.skillmanager.effects.CompensationPairingTest.run();
        f += dev.skillmanager.effects.FailureInjectionSweepTest.run();
        System.out.println(f == 0 ? "\nALL PASSED" : "\nFAILURES: " + f);
        if (f != 0) System.exit(1);
    }
}
