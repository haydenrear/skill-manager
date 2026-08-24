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
 * The three suites HIS-21 (#253) touched, on their own.
 *
 * <p>SHIPPED ON PURPOSE, for the reason RunHis11/12/13/15/16/18/19 are: every
 * row in {@code results/epic-home-integrity-sync/probes/his-21/vacuity-checks.md}
 * names this runner, and a record naming a file that does not exist is a claim
 * rather than evidence. Apply the mutation a V-number names, run this, and
 * revert BY COPYING THE SAVED FILE BACK — never with {@code git checkout --},
 * which eats uncommitted work (DEF-035, which has bitten five agents in this
 * epic).
 *
 * <p>{@code DamagedHomeIsRepairableTest} carries DEF-104's regression beside
 * the repair half it had to stop disagreeing with;
 * {@code HelpIsTextOnlyTest} is DEF-102; {@code HomeReportsMarkWhatTheyInventTest}
 * is DEF-105 and DEF-106.
 *
 * <p>{@code jbang RunTests.java} remains the full suite and the ticket's
 * declared local signal; this is the three suites, seconds instead of minutes.
 */
public class RunHis21 {
    public static void main(String[] a) throws Exception {
        int f = dev.skillmanager.store.DamagedHomeIsRepairableTest.run();
        f += dev.skillmanager.cli.HelpIsTextOnlyTest.run();
        f += dev.skillmanager.commands.HomeReportsMarkWhatTheyInventTest.run();
        System.out.println(f == 0 ? "\nALL PASSED" : "\nFAILURES: " + f);
        System.exit(f == 0 ? 0 : 1);
    }
}
