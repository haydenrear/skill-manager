///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//SOURCES src/main/java/**/*.java
//SOURCES src/test/java/**/*.java
//SOURCES ServerObservabilityContractTest.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/observability/ServerObservability.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/observability/ServerObservabilityFilter.java
//JAVA_OPTIONS -Dorg.slf4j.simpleLogger.defaultLogLevel=warn
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
/**
 * The suite HIS-16 (#237) added, on its own.
 *
 * <p>SHIPPED ON PURPOSE, for the reason RunHis11/12/15 are: every record in
 * results/epic-home-integrity-sync/probes/his-16/vacuity-checks.txt names this
 * runner, and a record naming a file that does not exist is a claim rather than
 * evidence. Apply the mutation a V-number names, run this, revert.
 *
 * <h2>Why this runner deliberately does NOT include JsonContractTest</h2>
 *
 * <p>{@code JsonContractTest} now drives {@code project register|remove|resolve|
 * sync} on their REAL execution paths, and it is safe to do so <b>only because
 * the confinement guard refuses them</b>. Run that suite with the guard mutated
 * away and it does not merely go red — it reproduces DEF-047 against whatever
 * checkout the JVM is standing in, which for a ticket agent is its own
 * worktree home. That is the incident, not a probe of it.
 *
 * <p>So the mutation lane is this suite, whose fixtures are temp directories,
 * and the guard's effect on {@code JsonContractTest} is measured the other way
 * round: it is asserted GREEN on the unmutated tree, where the four project
 * entries reach the refusal. The end-to-end escape — real subprocess, real
 * working directory inside another checkout — is reproduced under control by
 * the {@code home-integrity} graph node {@code ProjectVerbStaysInItsHome},
 * which owns a throwaway victim and can afford to let it be damaged.
 */
public class RunHis16 {
    public static void main(String[] a) throws Exception {
        int f = dev.skillmanager.sandbox.ConfinementTest.run();
        System.out.println(f == 0 ? "\nALL PASSED" : "\nFAILURES: " + f);
        if (f != 0) System.exit(1);
    }
}
