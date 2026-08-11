from pathlib import Path


SPEC_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = SPEC_ROOT.parents[1]


def test_tg6d_jbang_environment_repository_graph_is_registered() -> None:
    graph = (REPO_ROOT / "test_graph/build.gradle.kts").read_text(encoding="utf-8")

    assert 'testGraph("environmentRepositoryContractJbang")' in graph
    assert "sources/Tg6EnvironmentRepositoryProvisionJbang.java" in graph
    assert "sources/Tg6EnvironmentContextEnvKeyJbang.java" in graph
    assert "sources/Tg6EnvironmentContextEnvAllJbang.java" in graph


def test_tg6d_jbang_nodes_use_real_java_environment_repository_contract() -> None:
    provision = (REPO_ROOT / "test_graph/sources/Tg6EnvironmentRepositoryProvisionJbang.java").read_text(encoding="utf-8")
    env_key = (REPO_ROOT / "test_graph/sources/Tg6EnvironmentContextEnvKeyJbang.java").read_text(encoding="utf-8")
    env_all = (REPO_ROOT / "test_graph/sources/Tg6EnvironmentContextEnvAllJbang.java").read_text(encoding="utf-8")

    assert ".builder(\"build/tg6-environment-repository-local-preview\", \"templates/branch-preview\")" in provision
    assert "SideEffect.EnvironmentAction.PROVISION" in provision
    assert "TEST_GRAPH_ENVIRONMENT_REPOSITORY_DIR" in provision
    assert "TEST_GRAPH_ENVIRONMENT_TEMPLATE_DIR" in provision
    assert "SideEffect.env(\"EnvironmentId\")" in env_key
    assert "SideEffect.envAll()" in env_all
    assert "EnvironmentRepositoryReused" in env_all
