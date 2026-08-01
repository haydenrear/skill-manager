"""NodeSpec — self-declared node metadata."""
from __future__ import annotations

import json
import re
from dataclasses import dataclass, field


VALID_KINDS = {"testbed", "fixture", "action", "assertion", "evidence", "report"}
_ENV_KEY = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
_ENV_REPO_NAME = re.compile(r"[a-z][a-z0-9-]*")
_ARCHIVE_SOURCE = re.compile(r".*\.(tar|tar\.gz|tgz|zip)$")
_REQUIRED_ENV_REPO_OUTPUTS = {"EnvironmentId", "KUBECONFIG", "KUBECONTEXT"}
_ALLOWED_SIDE_EFFECTS = {
    "db": {"writes"},
    "fs": {"tmp"},
    "net": {"external", "local"},
    "process": {"gradle"},
    "environment": {"provision", "reuse", "deploy", "reset", "destroy"},
}


@dataclass(frozen=True)
class SideEffect:
    """Typed side-effect metadata for ``NodeSpec``.

    Later execution tickets attach behavior to selected forms; this class only
    validates and serializes the declared contract.
    """

    raw: str

    @classmethod
    def of(cls, raw: str) -> "SideEffect":
        value = str(raw).strip()
        if not value:
            raise ValueError("side_effects contains a blank side effect")
        if value == "browser":
            return cls(value)

        if ":" not in value:
            raise ValueError(
                f"malformed side effect {raw!r}; expected a registered form like "
                "browser, net:local, or env:[KEY]"
            )
        family, action = value.split(":", 1)
        if not family or not action:
            raise ValueError(
                f"malformed side effect {raw!r}; expected a registered form like "
                "browser, net:local, or env:[KEY]"
            )
        if family == "env":
            _validate_env_action(action, raw)
            return cls(value)

        allowed = _ALLOWED_SIDE_EFFECTS.get(family)
        if allowed is None or action not in allowed:
            raise ValueError(f"unsupported side effect {raw!r}")
        return cls(value)

    @classmethod
    def browser(cls) -> "SideEffect":
        return cls.of("browser")

    @classmethod
    def db_writes(cls) -> "SideEffect":
        return cls.of("db:writes")

    @classmethod
    def fs_tmp(cls) -> "SideEffect":
        return cls.of("fs:tmp")

    @classmethod
    def net_external(cls) -> "SideEffect":
        return cls.of("net:external")

    @classmethod
    def net_local(cls) -> "SideEffect":
        return cls.of("net:local")

    @classmethod
    def process_gradle(cls) -> "SideEffect":
        return cls.of("process:gradle")

    @classmethod
    def env(cls, key: str) -> "SideEffect":
        if not isinstance(key, str):
            raise ValueError("env side effect key must be a string")
        return cls.of(f"env:[{key}]")

    @classmethod
    def env_all(cls) -> "SideEffect":
        return cls.of("env:[*]")

    @classmethod
    def environment(cls, action: str) -> "SideEffect":
        if not isinstance(action, str):
            raise ValueError("environment side effect action must be a string")
        return cls.of(f"environment:{action.lower()}")


@dataclass(frozen=True)
class EnvironmentRepository:
    """Provider-neutral Git environment repository contract metadata."""

    source: str
    template: str
    target: str = "local-preview"
    backend: str = "local"
    branch: str = "feature"
    output_keys: tuple[str, ...] = ("EnvironmentId", "KUBECONFIG", "KUBECONTEXT")

    @classmethod
    def of(cls, source: str, template: str) -> "EnvironmentRepository":
        return cls(source=source, template=template)

    def with_target(self, target: str) -> "EnvironmentRepository":
        return EnvironmentRepository(self.source, self.template, target, self.backend, self.branch, self.output_keys)

    def with_backend(self, backend: str) -> "EnvironmentRepository":
        return EnvironmentRepository(self.source, self.template, self.target, backend, self.branch, self.output_keys)

    def with_branch(self, branch: str) -> "EnvironmentRepository":
        return EnvironmentRepository(self.source, self.template, self.target, self.backend, branch, self.output_keys)

    def with_output_keys(self, *keys: str) -> "EnvironmentRepository":
        return EnvironmentRepository(self.source, self.template, self.target, self.backend, self.branch, tuple(keys))

    def to_json_obj(self) -> dict[str, object]:
        _validate_environment_repository(self)
        return {
            "source": self.source,
            "template": self.template,
            "target": self.target,
            "backend": self.backend,
            "branch": self.branch,
            "outputKeys": list(self.output_keys),
        }


def _validate_env_action(action: str, raw: str) -> None:
    if not action.startswith("[") or not action.endswith("]"):
        raise ValueError(f"malformed env side effect {raw!r}; expected env:[KEY] or env:[*]")
    key = action[1:-1]
    if key != "*" and _ENV_KEY.fullmatch(key) is None:
        raise ValueError(f"malformed env side effect {raw!r}; expected env:[KEY] or env:[*]")


def _validate_environment_repository(repository: EnvironmentRepository) -> None:
    if not isinstance(repository, EnvironmentRepository):
        raise ValueError("environmentRepository must be an EnvironmentRepository")
    if not isinstance(repository.source, str) or not repository.source.strip():
        raise ValueError("environmentRepository.source must be a Git URL or local Git repository path")
    if not isinstance(repository.template, str) or not repository.template.strip():
        raise ValueError("environmentRepository.template must name a template directory inside the repository")
    segments = repository.template.split("/")
    if (
        repository.template.startswith("/")
        or "\\" in repository.template
        or any(segment in {"", ".", ".."} for segment in segments)
    ):
        raise ValueError("environmentRepository.template must be a relative repository path without '.', '..', or empty segments")
    if _ENV_REPO_NAME.fullmatch(repository.target) is None:
        raise ValueError("environmentRepository.target must use lowercase words and '-' separators")
    if _ENV_REPO_NAME.fullmatch(repository.backend) is None:
        raise ValueError("environmentRepository.backend must use lowercase words and '-' separators")
    if not isinstance(repository.branch, str) or not repository.branch.strip():
        raise ValueError("environmentRepository.branch must not be blank")
    if _ARCHIVE_SOURCE.fullmatch(repository.source.lower()) is not None:
        raise ValueError("environmentRepository.source must be an ordinary Git URL/path, not an archive or tarball")
    output_keys = set(repository.output_keys)
    if not _REQUIRED_ENV_REPO_OUTPUTS.issubset(output_keys):
        raise ValueError(f"environmentRepository.outputKeys must include {sorted(_REQUIRED_ENV_REPO_OUTPUTS)}")
    for key in output_keys:
        if _ENV_KEY.fullmatch(key) is None:
            raise ValueError(f"environmentRepository.outputKeys contains invalid key {key!r}")


@dataclass
class NodeSpec:
    """Metadata a Python node declares about itself.

    The plugin invokes the script with ``--describe-out=<path>``; the SDK
    writes this spec as JSON to that path so the graph can be discovered
    without running the body. Runtime is always ``"uv"`` for this SDK.
    """

    id: str
    _kind: str = "action"
    _depends_on: list[str] = field(default_factory=list)
    _tags: list[str] = field(default_factory=list)
    _timeout: str = "60s"
    _retries: int = 0
    _rerun: bool = True
    _cacheable: bool = False
    _side_effects: list[str] = field(default_factory=list)
    _environment_repository: EnvironmentRepository | None = None
    _inputs: dict[str, str] = field(default_factory=dict)
    _outputs: dict[str, str] = field(default_factory=dict)
    _junit_xml: bool = False
    _cucumber: bool = False

    def kind(self, k: str) -> "NodeSpec":
        if k not in VALID_KINDS:
            raise ValueError(f"invalid kind '{k}'; expected one of {sorted(VALID_KINDS)}")
        self._kind = k
        return self

    def depends_on(self, *ids: str) -> "NodeSpec":
        self._depends_on.extend(ids)
        return self

    def tags(self, *t: str) -> "NodeSpec":
        self._tags.extend(t)
        return self

    def timeout(self, v: str) -> "NodeSpec":
        self._timeout = v
        return self

    def retries(self, n: int) -> "NodeSpec":
        """Extra attempts the executor makes on a timeout outcome.

        Default 0 — fail fast on the first timeout. Only set ``> 0`` for
        nodes that are safe to re-run; most graph nodes are stateful
        (start a server, claim a port, cache a token) and would leave
        orphaned state on retry. Triggers only on timeout, never on a
        body-returned ``NodeResult.fail(...)``.
        """
        self._retries = max(0, n)
        return self

    def rerun(self, enabled: bool = True) -> "NodeSpec":
        """Control whether failed-run guidance should offer a direct rerun.

        Defaults to ``True``. Set ``False`` for nodes where replaying from
        the previous build-directory input context is unsafe because the node
        mutates non-idempotent resources, claims external state, consumes a
        one-shot token, or otherwise cannot be retried from context alone.

        This is distinct from :meth:`retries`, which controls automatic
        executor attempts after timeouts.
        """
        self._rerun = bool(enabled)
        return self

    def cacheable(self, b: bool = True) -> "NodeSpec":
        self._cacheable = b
        return self

    def side_effects(self, *s: str | SideEffect) -> "NodeSpec":
        for raw in s:
            effect = raw if isinstance(raw, SideEffect) else SideEffect.of(raw)
            self._side_effects.append(effect.raw)
        return self

    def environment_repository(self, repository: EnvironmentRepository) -> "NodeSpec":
        _validate_environment_repository(repository)
        self._environment_repository = repository
        return self

    def input(self, name: str, type_: str = "string") -> "NodeSpec":
        self._inputs[name] = type_
        return self

    def output(self, name: str, type_: str = "string") -> "NodeSpec":
        self._outputs[name] = type_
        return self

    def junit_xml(self) -> "NodeSpec":
        self._junit_xml = True
        return self

    def cucumber(self) -> "NodeSpec":
        self._cucumber = True
        return self

    def to_json(self) -> str:
        obj: dict[str, object] = {
            "id": self.id,
            "kind": self._kind,
            "runtime": "uv",
            "dependsOn": list(self._depends_on),
            "tags": list(self._tags),
            "timeout": self._timeout,
            "retries": self._retries,
            "rerun": self._rerun,
            "cacheable": self._cacheable,
            "sideEffects": list(self._side_effects),
            "inputs": dict(self._inputs),
            "outputs": dict(self._outputs),
            "reports": {
                "structuredJson": True,
                "junitXml": self._junit_xml,
                "cucumber": self._cucumber,
            },
        }
        if self._environment_repository is not None:
            obj["environmentRepository"] = self._environment_repository.to_json_obj()
        return json.dumps(obj, separators=(",", ":"))
