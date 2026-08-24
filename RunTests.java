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

import dev.skillmanager.model.CoordParserTest;
import dev.skillmanager.model.CoordRoundTripTest;
import dev.skillmanager.model.EffectiveDepUnionTest;
import dev.skillmanager.model.PluginParserDriftWarnsTest;
import dev.skillmanager.model.PluginParserTest;
import dev.skillmanager.model.SkillProjectParserTest;
import dev.skillmanager.model.SkillUnitWrapsSkillTest;
import dev.skillmanager.model.UnitReferenceFromTomlTest;
import dev.skillmanager.observability.CliObservabilityTest;
import dev.skillmanager.observability.QuietConsoleTest;
import dev.skillmanager.plan.CycleDetectionTest;
import dev.skillmanager.plan.MixedKindTopoOrderTest;
import dev.skillmanager.cli.CliAgentContextExecutionTest;
import dev.skillmanager.cli.CliRefusalsAreMessagesTest;
import dev.skillmanager.cli.HomeBindsBothAxesTest;
import dev.skillmanager.cli.JsonContractTest;
import dev.skillmanager.cli.LazyHomeScaffoldTest;
import dev.skillmanager.command.CliAgentContextTest;
import dev.skillmanager.command.CliHelpProgressiveDisclosureTest;
import dev.skillmanager.command.CliMetadataTest;
import dev.skillmanager.command.CommandKindCoverageTest;
import dev.skillmanager.command.CreatePluginScenarioTest;
import dev.skillmanager.command.GatewayStatusCommandTest;
import dev.skillmanager.command.ListShowsKindAndShaTest;
import dev.skillmanager.commands.ProjectCommandTest;
import dev.skillmanager.commands.RemediesAreRunnableTest;
import dev.skillmanager.validation.ProjectMarkdownImportsTest;
import dev.skillmanager.effects.LocalInstallIsNotAnErrorTest;
import dev.skillmanager.store.HomeVerifyDiagnosticTextTest;
import dev.skillmanager.cli.installer.CliArtifactMatrixTest;
import dev.skillmanager.cli.installer.CliPresenceTest;
import dev.skillmanager.cli.installer.InstallerFingerprintTest;
import dev.skillmanager.cli.installer.SkillScriptBackendTest;
import dev.skillmanager.artifacts.ArtifactBackfillTest;
import dev.skillmanager.artifacts.ArtifactHomeStabilityTest;
import dev.skillmanager.artifacts.ArtifactGraphTest;
import dev.skillmanager.artifacts.ArtifactStalenessTest;
import dev.skillmanager.artifacts.ArtifactBuildTest;
import dev.skillmanager.artifacts.LazyArtifactHomeTest;
import dev.skillmanager.artifacts.ArtifactPruneTest;
import dev.skillmanager.bindings.BindingsTest;
import dev.skillmanager.command.SearchShowsKindTest;
import dev.skillmanager.command.ShowNonSkillUnitsTest;
import dev.skillmanager.command.ShowPluginListsContainedSkillsTest;
import dev.skillmanager.command.SkillManagerSkillDocsTest;
import dev.skillmanager.command.SyncFromLockScenarioTest;
import dev.skillmanager.command.UninstallCliCleanupTest;
import dev.skillmanager.command.UninstallScenarioTest;
import dev.skillmanager.effects.ScaffoldPluginTest;
import dev.skillmanager.registry.PublishDetectsPluginTest;
import dev.skillmanager.registry.PublishDetectsSkillTest;
import dev.skillmanager.validation.MarkdownImportValidatorTest;
import dev.skillmanager.effects.CompensationLogicTest;
import dev.skillmanager.effects.ProjectSyncErrorReportingTest;
import dev.skillmanager.effects.CompensationOrphanTest;
import dev.skillmanager.effects.CommitPreImageRestoreTest;
import dev.skillmanager.effects.CompensationPairingTest;
import dev.skillmanager.effects.FailureInjectionSweepTest;
import dev.skillmanager.lock.LockAtomicityTest;
import dev.skillmanager.lock.LockDiffTest;
import dev.skillmanager.lock.LockReadWriteTest;
import dev.skillmanager.lock.RequestedVersionTest;
import dev.skillmanager.lock.LockSchemaVersionTest;
import dev.skillmanager.project.ClaudeProjectorTest;
import dev.skillmanager.project.CodexProjectorTest;
import dev.skillmanager.project.GeminiProjectorTest;
import dev.skillmanager.commands.HomeRefreshPluginsTest;
import dev.skillmanager.project.HarnessPluginCliTest;
import dev.skillmanager.project.PluginMarketplaceTest;
import dev.skillmanager.project.ProjectChildHomeMaterializationTest;
import dev.skillmanager.project.ProjectSyncIdempotencyTest;
import dev.skillmanager.project.ProjectDependencyResolverTest;
import dev.skillmanager.project.ProjectResolveAtomicClosureTest;
import dev.skillmanager.project.ProjectEnvMaterializerTest;
import dev.skillmanager.project.ProjectLibResolverTest;
import dev.skillmanager.project.ProjectVendoredResolverTest;
import dev.skillmanager.project.ProjectorRegistryTest;
import dev.skillmanager.project.SkillProjectRegistryTest;
import dev.skillmanager.effects.RefreshHarnessPluginsTest;
import dev.skillmanager.effects.ResolveGraphDirectGitSyncTest;
import dev.skillmanager.effects.SyncGitDocRepoTest;
import dev.skillmanager.effects.HandlerSubstitutabilityTest;
import dev.skillmanager.effects.KindAwareDispatchTest;
import dev.skillmanager.effects.ListTypedHandlerSubstitutabilityTest;
import dev.skillmanager.effects.SourceProvenanceRecorderTest;
import dev.skillmanager.plan.PlanPolicyCategorizationTest;
import dev.skillmanager.plan.PlanShapeInvariantTest;
import dev.skillmanager.plan.PolicyGatingTest;
import dev.skillmanager.resolve.ResolverContainedSkillNotMatchedTest;
import dev.skillmanager.resolve.ResolverBundledAliasTest;
import dev.skillmanager.resolve.ResolverDeterminismTest;
import dev.skillmanager.resolve.ResolverDirectGitDetectsKindTest;
import dev.skillmanager.resolve.ResolverDirectGitTransitiveTest;
import dev.skillmanager.resolve.ResolverHeterogeneousRefsTest;
import dev.skillmanager.resolve.ResolverKindFilterTest;
import dev.skillmanager.resolve.ResolverCycleTest;
import dev.skillmanager.registry.RegistryUnavailableExceptionTest;
import dev.skillmanager.server.observability.ServerObservabilityContractTest;
import dev.skillmanager.store.FetcherLocalSourceTest;
import dev.skillmanager.store.InstalledUnitRoundTripTest;
import dev.skillmanager.store.MigrationFromSkillSourceTest;
import dev.skillmanager.store.UnitStoreDirChoiceTest;

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
        // Force the harness-CLI plumbing into its "unavailable" branch
        // for the entire suite — unit tests must never spawn `claude` /
        // `codex` subprocesses that would mutate the developer's real
        // harness config when those CLIs happen to be on PATH locally.
        // The driver-level branching is exhaustively covered with
        // fakes in HarnessPluginCliTest.
        System.setProperty("skill-manager.harness-cli.disabled", "true");

        int failures = 0;

        failures += PluginParserTest.run();
        failures += PluginParserDriftWarnsTest.run();
        failures += EffectiveDepUnionTest.run();
        failures += SkillUnitWrapsSkillTest.run();
        failures += CoordParserTest.run();
        failures += CoordRoundTripTest.run();
        failures += UnitReferenceFromTomlTest.run();
        failures += SkillProjectParserTest.run();
        failures += UnitStoreDirChoiceTest.run();
        failures += InstalledUnitRoundTripTest.run();
        failures += MigrationFromSkillSourceTest.run();
        failures += FetcherLocalSourceTest.run();
        failures += RegistryUnavailableExceptionTest.run();
        failures += ResolverKindFilterTest.run();
        failures += ResolverHeterogeneousRefsTest.run();
        failures += ResolverContainedSkillNotMatchedTest.run();
        failures += ResolverBundledAliasTest.run();
        failures += ResolverDirectGitDetectsKindTest.run();
        failures += ResolverDirectGitTransitiveTest.run();
        failures += ResolverDeterminismTest.run();
        failures += ResolverCycleTest.run();
        failures += PlanShapeInvariantTest.run();
        failures += SkillScriptBackendTest.run();
        failures += InstallerFingerprintTest.run();
        failures += CliPresenceTest.run();
        failures += CliArtifactMatrixTest.run();
        failures += CycleDetectionTest.run();
        failures += MixedKindTopoOrderTest.run();
        failures += PlanPolicyCategorizationTest.run();
        failures += PolicyGatingTest.run();
        failures += HandlerSubstitutabilityTest.run();
        failures += ListTypedHandlerSubstitutabilityTest.run();
        failures += dev.skillmanager.source.GitOpsRepoBoundaryTest.run();
        failures += dev.skillmanager.source.DereferencedStoreLinkSyncTest.run();
        failures += dev.skillmanager.source.SyncPathsAgreeAboutDirtyTest.run();
        failures += SourceProvenanceRecorderTest.run();
        failures += ResolveGraphDirectGitSyncTest.run();
        failures += SyncGitDocRepoTest.run();
        failures += KindAwareDispatchTest.run();
        failures += CompensationLogicTest.run();
        failures += ProjectSyncErrorReportingTest.run();
        failures += CompensationPairingTest.run();
        failures += CompensationOrphanTest.run();
        failures += UninstallScenarioTest.run();
        failures += UninstallCliCleanupTest.run();
        failures += FailureInjectionSweepTest.run();
        failures += CommitPreImageRestoreTest.run();
        failures += LockReadWriteTest.run();
        failures += RequestedVersionTest.run();
        failures += LockDiffTest.run();
        failures += LockSchemaVersionTest.run();
        failures += LockAtomicityTest.run();
        failures += SyncFromLockScenarioTest.run();
        failures += ClaudeProjectorTest.run();
        failures += CodexProjectorTest.run();
        failures += GeminiProjectorTest.run();
        failures += ProjectorRegistryTest.run();
        failures += SkillProjectRegistryTest.run();
        failures += ProjectDependencyResolverTest.run();
        failures += ProjectResolveAtomicClosureTest.run();
        failures += dev.skillmanager.commands.ProjectSyncTypedRefusalTest.run();
        failures += ProjectChildHomeMaterializationTest.run();
        failures += ProjectSyncIdempotencyTest.run();
        failures += ProjectEnvMaterializerTest.run();
        failures += ProjectLibResolverTest.run();
        failures += ProjectVendoredResolverTest.run();
        failures += PluginMarketplaceTest.run();
        failures += HarnessPluginCliTest.run();
        failures += HomeRefreshPluginsTest.run();
        failures += RefreshHarnessPluginsTest.run();
        failures += PublishDetectsPluginTest.run();
        failures += PublishDetectsSkillTest.run();
        failures += MarkdownImportValidatorTest.run();
        failures += ProjectMarkdownImportsTest.run();
        failures += LocalInstallIsNotAnErrorTest.run();
        failures += ScaffoldPluginTest.run();
        failures += CliObservabilityTest.run();
        failures += QuietConsoleTest.run();
        failures += ServerObservabilityContractTest.run();
        failures += CliAgentContextExecutionTest.run();
        failures += CliRefusalsAreMessagesTest.run();
        failures += JsonContractTest.run();
        failures += LazyHomeScaffoldTest.run();
        failures += HomeBindsBothAxesTest.run();
        failures += CliAgentContextTest.run();
        failures += CliHelpProgressiveDisclosureTest.run();
        failures += CliMetadataTest.run();
        failures += CommandKindCoverageTest.run();
        failures += ProjectCommandTest.run();
        failures += RemediesAreRunnableTest.run();
        failures += CreatePluginScenarioTest.run();
        failures += GatewayStatusCommandTest.run();
        failures += ListShowsKindAndShaTest.run();
        failures += SearchShowsKindTest.run();
        failures += ShowNonSkillUnitsTest.run();
        failures += ShowPluginListsContainedSkillsTest.run();
        failures += SkillManagerSkillDocsTest.run();
        failures += dev.skillmanager.agent.AgentHomesTest.run();
        failures += dev.skillmanager.agent.AgentProjectionFollowsHomeTest.run();
        failures += dev.skillmanager.mcp.McpWriterTest.run();
        failures += dev.skillmanager.mcp.GatewayRuntimeTest.run();
        failures += dev.skillmanager.store.FetcherGitCloneTest.run();
        failures += dev.skillmanager.store.HomePathsTest.run();
        failures += dev.skillmanager.store.HomeCloneTest.run();
        failures += dev.skillmanager.commands.HomeVerifyReportTest.run();
        // HIS-21 (#253): the four diagnostics that reported something that was
        // not so. DEF-102 lives in the execution strategy; DEF-105 and DEF-106
        // in what `home policy` and `home describe` say about files they never
        // read. DEF-104's regression sits in DamagedHomeIsRepairableTest below,
        // beside the repair half it had to stop disagreeing with.
        failures += dev.skillmanager.cli.HelpIsTextOnlyTest.run();
        failures += dev.skillmanager.commands.HomeReportsMarkWhatTheyInventTest.run();
        failures += HomeVerifyDiagnosticTextTest.run();
        failures += dev.skillmanager.commands.HomeUnresolvedGateTest.run();
        failures += dev.skillmanager.store.ChildHomeShimIsolationTest.run();
        failures += dev.skillmanager.store.ClonedHomeDescentTest.run();
        failures += dev.skillmanager.store.DamagedHomeIsRepairableTest.run();
        failures += dev.skillmanager.store.HomeVerifyPathSpellingTest.run();
        failures += dev.skillmanager.store.ProvenanceRecordExemptionTest.run();
        failures += dev.skillmanager.cli.installer.CliShimPrunerTest.run();
        // HIS-9 (#226): the write-confinement guard, its two DEF-007 delete
        // call sites, and the producer boundary.
        failures += dev.skillmanager.store.WriteConfinementTest.run();
        failures += dev.skillmanager.store.PruneStaysInsideItsHomeTest.run();
        failures += dev.skillmanager.cli.installer.ProducerStaysInsideItsHomeTest.run();
        failures += dev.skillmanager.sandbox.ConfinementTest.run();
        failures += dev.skillmanager.cli.BuildIdentityTest.run();
        failures += dev.skillmanager.store.HomeDescriptorTest.run();
        failures += dev.skillmanager.store.HomeDescriptorCliRemedyTest.run();
        failures += dev.skillmanager.policy.HomePolicyTest.run();
        failures += dev.skillmanager.launch.LauncherShimsTest.run();
        failures += dev.skillmanager.launch.DurableCliPinTest.run();
        failures += dev.skillmanager.project.ProjectTrunkSyncTest.run();
        failures += dev.skillmanager.store.HomeDriftGateTest.run();
        failures += dev.skillmanager.store.HomeSyncTest.run();
        failures += dev.skillmanager.store.HomeCloseOutTenseTest.run();
        failures += dev.skillmanager.store.HomeSyncMergeTest.run();
        failures += dev.skillmanager.store.HomeSyncGitUnitTest.run();
        failures += dev.skillmanager.store.HomeSyncUnitFilterTest.run();
        failures += dev.skillmanager.store.HomeSyncStaleBaselineTest.run();
        failures += dev.skillmanager.commands.HomeSyncUnitCliTest.run();
        failures += dev.skillmanager.plan.AuditTrailTest.run();
        failures += dev.skillmanager.mcp.SharedGatewayTest.run();
        failures += dev.skillmanager.app.SyncMcpSplitTest.run();
        failures += dev.skillmanager.project.GeminiHomeParityTest.run();
        failures += ArtifactBackfillTest.run();
        failures += ArtifactHomeStabilityTest.run();
        failures += ArtifactGraphTest.run();
        failures += ArtifactStalenessTest.run();
        failures += ArtifactBuildTest.run();
        failures += LazyArtifactHomeTest.run();
        failures += ArtifactPruneTest.run();
        failures += BindingsTest.run();
        failures += dev.skillmanager.bindings.DocRepoTest.run();
        failures += dev.skillmanager.bindings.HarnessTest.run();
        failures += dev.skillmanager.bindings.ScaffoldedTreeIsNotContentTest.run();

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL PASSED");
            System.exit(0);
        }
        System.out.println("FAILURES: " + failures);
        System.exit(1);
    }
}
