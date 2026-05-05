///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//SOURCES src/main/java/**/*.java
//SOURCES src/test/java/**/*.java
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

import dev.skillmanager.model.CoordParserTest;
import dev.skillmanager.model.CoordRoundTripTest;
import dev.skillmanager.model.EffectiveDepUnionTest;
import dev.skillmanager.model.PluginParserDriftWarnsTest;
import dev.skillmanager.model.PluginParserTest;
import dev.skillmanager.model.SkillUnitWrapsSkillTest;
import dev.skillmanager.model.UnitReferenceFromTomlTest;

/**
 * Layer-2 unit-test runner. Each test class exposes a {@code static int
 * run()} returning its failure count; this aggregates them and exits
 * non-zero on any failure.
 *
 * <p>Usage: {@code jbang RunTests.java} (or {@code ./RunTests.java}
 * directly). New test classes are added to the imports + invocation
 * list below as they land. As the suite grows past ~20 classes,
 * consider auto-discovery via reflection on the {@code src/test/java}
 * tree.
 */
public class RunTests {

    public static void main(String[] args) throws Exception {
        int failures = 0;

        failures += PluginParserTest.run();
        failures += PluginParserDriftWarnsTest.run();
        failures += EffectiveDepUnionTest.run();
        failures += SkillUnitWrapsSkillTest.run();
        failures += CoordParserTest.run();
        failures += CoordRoundTripTest.run();
        failures += UnitReferenceFromTomlTest.run();

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL PASSED");
            System.exit(0);
        }
        System.out.println("FAILURES: " + failures);
        System.exit(1);
    }
}
