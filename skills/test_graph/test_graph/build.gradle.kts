plugins {
    id("com.hayden.testgraphsdk.graph")
}

validationGraph {
    sourcesDir("sources")

    testGraph("rerunGraphJbang") {
        node("sources/RerunGraphJbang.py")
    }

    testGraph("rerunGraphUv") {
        node("sources/RerunGraphUv.py")
    }

    testGraph("runOnlyJbang") {
        node("sources/RunOnlyJbang.py")
    }

    testGraph("runOnlyUv") {
        node("sources/RunOnlyUv.py")
    }

    testGraph("branchEnvironmentWorkflow") {
        node("sources/BranchEnvironmentMarkersPresent.py")
        node("sources/Tg5EnvironmentRepositoryContract.py")
        node("sources/Tg5FutureWorkflowPlan.py")
        node("sources/Tg5DeployCdcIssueWorkflowRecord.py")
    }

    testGraph("generatedEnvironmentRepositoryFixture") {
        node("sources/Tg5GeneratedEnvironmentRepositoryFixture.py")
    }

    testGraph("environmentRepositoryContract") {
        node("sources/Tg5StableEnvironmentRepositoryFixture.py")
        node("sources/Tg5EnvironmentRepositoryProvision.py")
        node("sources/Tg5EnvironmentContextEnvKey.py")
        node("sources/Tg5EnvironmentContextEnvAll.py")
        node("sources/Tg5EnvironmentRepositoryReuse.py")
    }

    testGraph("branchEnvironmentReset") {
        node("sources/Tg5StableEnvironmentRepositoryFixture.py")
        node("sources/Tg5EnvironmentRepositoryProvision.py")
        node("sources/Tg5EnvironmentRepositoryDeploy.py")
        node("sources/Tg5EnvironmentRepositoryReset.py")
        node("sources/Tg5BranchEnvironmentResetMarkers.py")
    }

    testGraph("branchEnvironmentMergeDestroy") {
        node("sources/Tg5StableEnvironmentRepositoryFixture.py")
        node("sources/Tg5EnvironmentRepositoryProvision.py")
        node("sources/Tg5EnvironmentRepositoryDeploy.py")
        node("sources/Tg5EnvironmentRepositoryDestroy.py")
        node("sources/Tg5BranchEnvironmentDestroyMarkers.py")
    }

    testGraph("deployCdcIssueContract") {
        node("sources/Tg5DeployCdcIssueContract.py")
        node("sources/Tg5DeployCdcNoSdkCoupling.py")
        node("sources/Tg5DeployCdcIssueWorkflowRecord.py")
    }

    testGraph("environmentRepositoryDocumentation") {
        node("sources/Tg6EnvironmentRepositoryDocumentation.py")
    }

    testGraph("environmentRepositoryScaffoldLocal") {
        node("sources/Tg6EnvironmentRepositoryScaffoldLocal.py")
        node("sources/Tg6EnvironmentRepositoryProvision.py")
        node("sources/Tg6EnvironmentContextEnvKey.py")
        node("sources/Tg6EnvironmentContextEnvAll.py")
        node("sources/Tg6EnvironmentRepositoryReuse.py")
    }

    testGraph("environmentRepositoryScaffoldGithubAction") {
        node("sources/Tg6EnvironmentRepositoryScaffoldGithubAction.py")
    }

    testGraph("environmentRepositoryScaffoldAws") {
        node("sources/Tg6EnvironmentRepositoryScaffoldAws.py")
    }

    testGraph("environmentLifecycleNodeTemplates") {
        node("sources/deploy_cluster.py")
        node("sources/reset_node.py")
        node("sources/delete_cluster.py")
        node("sources/DeployCluster.java")
        node("sources/ResetNode.java")
        node("sources/DeleteCluster.java")
    }

    testGraph("environmentRepositoryContractJbang") {
        node("sources/Tg6EnvironmentRepositoryScaffoldLocal.py")
        node("sources/Tg6EnvironmentRepositoryProvisionJbang.java")
        node("sources/Tg6EnvironmentContextEnvKeyJbang.java")
        node("sources/Tg6EnvironmentContextEnvAllJbang.java")
    }

    testGraph("environmentRepositoryLocalLifecycle") {
        node("sources/Tg6EnvironmentRepositoryScaffoldLocal.py")
        node("sources/Tg6LocalLifecycleProvisionMissing.py")
        node("sources/Tg6LocalLifecycleDeployExisting.py")
        node("sources/Tg6LocalLifecycleDeployMarkers.py")
        node("sources/Tg6LocalLifecycleReset.py")
        node("sources/Tg6LocalLifecycleResetMarkers.py")
        node("sources/Tg6LocalLifecycleSkipDestroy.py")
    }

    testGraph("environmentRepositoryLocalLifecycleDestroy") {
        node("sources/Tg6EnvironmentRepositoryScaffoldLocal.py")
        node("sources/Tg6LocalLifecycleProvisionMissing.py")
        node("sources/Tg6LocalLifecycleDeployExisting.py")
        node("sources/Tg6LocalLifecycleReset.py")
        node("sources/Tg6LocalLifecycleDestroy.py")
        node("sources/Tg6LocalLifecycleDestroyMarkers.py")
    }

    testGraph("environmentRepositoryGithubActionLifecycle") {
        node("sources/Tg6EnvironmentRepositoryScaffoldGithubAction.py")
        node("sources/Tg6GithubActionLifecycleProvisionMissing.py")
        node("sources/Tg6GithubActionLifecycleContextJbang.java")
        node("sources/Tg6GithubActionLifecycleDeployExisting.py")
        node("sources/Tg6GithubActionLifecycleDeployMarkers.py")
        node("sources/Tg6GithubActionLifecycleReset.py")
        node("sources/Tg6GithubActionLifecycleResetMarkers.py")
        node("sources/Tg6GithubActionLifecycleSkipDestroy.py")
    }

    testGraph("environmentRepositoryGithubActionLifecycleDestroy") {
        node("sources/Tg6EnvironmentRepositoryScaffoldGithubAction.py")
        node("sources/Tg6GithubActionLifecycleProvisionMissing.py")
        node("sources/Tg6GithubActionLifecycleContextJbang.java")
        node("sources/Tg6GithubActionLifecycleDeployExisting.py")
        node("sources/Tg6GithubActionLifecycleDeployMarkers.py")
        node("sources/Tg6GithubActionLifecycleReset.py")
        node("sources/Tg6GithubActionLifecycleResetMarkers.py")
        node("sources/Tg6GithubActionLifecycleDestroy.py")
        node("sources/Tg6GithubActionLifecycleDestroyMarkers.py")
    }

    testGraph("environmentRepositoryAwsLifecycle") {
        node("sources/Tg6EnvironmentRepositoryScaffoldAws.py")
        node("sources/Tg6AwsLifecycleGuard.py")
        node("sources/Tg6AwsLifecycleProvisionMissing.py")
        node("sources/Tg6AwsLifecycleContextJbang.java")
        node("sources/Tg6AwsLifecycleDeployExisting.py")
        node("sources/Tg6AwsLifecycleDeployMarkers.py")
        node("sources/Tg6AwsLifecycleReset.py")
        node("sources/Tg6AwsLifecycleResetMarkers.py")
        node("sources/Tg6AwsLifecycleSkipDestroy.py")
    }

    testGraph("environmentRepositoryAwsLifecycleDestroy") {
        node("sources/Tg6EnvironmentRepositoryScaffoldAws.py")
        node("sources/Tg6AwsLifecycleGuard.py")
        node("sources/Tg6AwsLifecycleProvisionMissing.py")
        node("sources/Tg6AwsLifecycleContextJbang.java")
        node("sources/Tg6AwsLifecycleDeployExisting.py")
        node("sources/Tg6AwsLifecycleDeployMarkers.py")
        node("sources/Tg6AwsLifecycleReset.py")
        node("sources/Tg6AwsLifecycleResetMarkers.py")
        node("sources/Tg6AwsLifecycleDestroy.py")
        node("sources/Tg6AwsLifecycleDestroyMarkers.py")
    }
}
