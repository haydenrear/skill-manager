#!/usr/bin/env python3
"""Progressive-disclosure cost eval — real `claude -p` agents, none of them inheriting.

WHAT IS MEASURED, and why it is not "total tokens".

A fresh agent pays ~40k tokens for the system prompt and tool schemas before it
reads a single byte of this project. That floor is Claude Code's, not skill
manager's, and no amount of good disclosure moves it. What disclosure DOES
govern is the second number: how much of the home's own corpus the agent has to
pull into context to do the job. The root home holds ~8.5 MILLION tokens of
markdown. The budget is 2,000. That ratio is the whole claim.

So:

  corpus_tokens   bytes of tool-result content returned by Read / Grep / Glob /
                  Bash whose target is inside the home under test, / 4.
                  THIS is the budget. Target <= 2000.
  total_tokens    everything, floor included. Reported, never graded.
  floor_tokens    a control run ("reply OK") in the same config, so corpus cost
                  can be separated from the harness's own weight.

CONTAINMENT. Every run pins all four home axes plus HOME itself at a scratch
path, so `$HOME/.skill-manager` -- skill-manager's fallback when nothing is set
-- cannot resolve to the operator's real root home. The real root home, the real
project home, ~/.claude, ~/.codex, ~/.gemini and every wt-* worktree are
untouchable by construction, not by instruction.
"""

import argparse, json, os, pathlib, shutil, subprocess, sys, time

SCRATCH = pathlib.Path(__file__).resolve().parent
CFG = SCRATCH / "cfg-clean"

# Env vars this session carries that would tell a child it is nested.
STRIP = ["CLAUDECODE", "CLAUDE_CODE_ENTRYPOINT", "CLAUDE_CODE_SESSION_ID",
         "CLAUDE_CODE_CHILD_SESSION", "CLAUDE_CODE_MESSAGING_SOCKET",
         "CLAUDE_CODE_MESSAGING_TOKEN", "CLAUDE_CODE_EXECPATH", "CLAUDE_PID",
         "CLAUDE_EFFORT", "AI_AGENT", "SKILL_MANAGER_HOME", "SKILL_MANAGER_CLI",
         "CLAUDE_CONFIG_DIR", "CLAUDE_HOME", "CODEX_HOME", "GEMINI_HOME"]

READERS = {"Read", "Grep", "Glob", "Bash", "NotebookRead"}


def build_env(home: pathlib.Path, fake_home: pathlib.Path, cli: str | None):
    env = {k: v for k, v in os.environ.items() if k not in STRIP}
    env["HOME"] = str(fake_home)
    env["CLAUDE_CONFIG_DIR"] = str(CFG)
    env["SKILL_MANAGER_HOME"] = str(home)
    env["CLAUDE_HOME"] = str(fake_home)
    env["CODEX_HOME"] = str(fake_home / ".codex")
    env["GEMINI_HOME"] = str(fake_home / ".gemini")
    if cli:
        env["SKILL_MANAGER_CLI"] = cli
    env["JAVA_TOOL_OPTIONS"] = "-Djava.net.preferIPv6Addresses=true"
    env["GRADLE_OPTS"] = "-Dorg.gradle.daemon=false"
    return env


def tool_use_index(events):
    """tool_use_id -> (tool_name, input dict). Needed because the result event
    carries only the id, and the budget is per-TOOL, not per-message."""
    idx = {}
    for e in events:
        msg = e.get("message") or {}
        for block in (msg.get("content") or []):
            if isinstance(block, dict) and block.get("type") == "tool_use":
                idx[block.get("id")] = (block.get("name"), block.get("input") or {})
    return idx


def result_text(block):
    c = block.get("content")
    if isinstance(c, str):
        return c
    if isinstance(c, list):
        return "".join(p.get("text", "") for p in c if isinstance(p, dict))
    return ""


def targets_corpus(name, tool_input, home: str):
    """Did this tool read the home under test? Deliberately generous -- a Bash
    `cat` of a corpus file counts, because the agent paid for those bytes just
    the same as a Read would have."""
    blob = json.dumps(tool_input)
    return home in blob


def measure(events, home: str):
    idx = tool_use_index(events)
    corpus_bytes = 0
    other_bytes = 0
    reads = []
    for e in events:
        msg = e.get("message") or {}
        for block in (msg.get("content") or []):
            if not (isinstance(block, dict) and block.get("type") == "tool_result"):
                continue
            name, tin = idx.get(block.get("tool_use_id"), (None, {}))
            n = len(result_text(block))
            if name in READERS and targets_corpus(name, tin, home):
                corpus_bytes += n
                reads.append({"tool": name, "bytes": n,
                              "target": (tin.get("file_path") or tin.get("path")
                                         or tin.get("pattern") or tin.get("command", ""))[:160]})
            else:
                other_bytes += n
    return corpus_bytes, other_bytes, reads


def run(task_id, prompt, home, cwd, model, max_turns, cli, timeout):
    fake_home = SCRATCH / "fakehomes" / task_id
    (fake_home / ".codex").mkdir(parents=True, exist_ok=True)
    (fake_home / ".gemini").mkdir(parents=True, exist_ok=True)
    # jbang/maven/gradle caches are shared deliberately: they are caches, and
    # rebuilding them per run would make the eval expensive for no signal.
    # $HOME/.skill-manager is the one thing NOT linked -- that is the point.
    # Library/Keychains is NOT a convenience: macOS resolves the login keychain
    # through $HOME, so overriding HOME cuts the child off from the OAuth
    # credential and every run returns "Not logged in". Linked narrowly --
    # Keychains only, never all of ~/Library.
    for cache in (".jbang", ".m2", ".gradle", ".sdkman", "Library/Keychains"):
        link = fake_home / cache
        real = pathlib.Path(os.path.expanduser("~")) / cache
        if real.exists() and not link.exists():
            link.parent.mkdir(parents=True, exist_ok=True)
            link.symlink_to(real)
    assert not (fake_home / ".skill-manager").exists(), "fallback home must not exist"

    cwd = pathlib.Path(cwd); cwd.mkdir(parents=True, exist_ok=True)
    out = SCRATCH / "runs" / f"{task_id}.jsonl"
    out.parent.mkdir(parents=True, exist_ok=True)

    argv = ["claude", "-p", prompt, "--output-format", "stream-json", "--verbose",
            "--model", model, "--max-turns", str(max_turns),
            "--allowedTools", "Read", "Grep", "Glob", "Bash", "TodoWrite",
            "--permission-mode", "acceptEdits"]
    t0 = time.time()
    with open(out, "wb") as fh:
        p = subprocess.run(argv, env=build_env(pathlib.Path(home), fake_home, cli),
                           cwd=str(cwd), stdout=fh, stderr=subprocess.STDOUT,
                           stdin=subprocess.DEVNULL, timeout=timeout)
    wall = time.time() - t0

    events = []
    for line in out.read_text(errors="replace").splitlines():
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            events.append(json.loads(line))
        except json.JSONDecodeError:
            pass

    res = next((e for e in reversed(events) if e.get("type") == "result"), {})
    u = res.get("usage") or {}
    corpus_bytes, other_bytes, reads = measure(events, str(home))
    return {
        "task": task_id, "rc": p.returncode, "wall_s": round(wall, 1),
        "is_error": res.get("is_error"),
        "num_turns": res.get("num_turns"),
        "cost_usd": res.get("total_cost_usd"),
        "corpus_bytes": corpus_bytes,
        "corpus_tokens": round(corpus_bytes / 4),
        "other_read_bytes": other_bytes,
        "total_tokens": (u.get("input_tokens", 0) + u.get("cache_read_input_tokens", 0)
                         + u.get("cache_creation_input_tokens", 0) + u.get("output_tokens", 0)),
        "output_tokens": u.get("output_tokens"),
        "reads": reads,
        "answer": res.get("result"),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--task", required=True)
    ap.add_argument("--prompt-file", required=True)
    ap.add_argument("--home", required=True)
    ap.add_argument("--cwd", required=True)
    ap.add_argument("--model", default="sonnet")
    ap.add_argument("--max-turns", type=int, default=25)
    ap.add_argument("--cli", default=None)
    ap.add_argument("--timeout", type=int, default=900)
    a = ap.parse_args()

    r = run(a.task, pathlib.Path(a.prompt_file).read_text(), a.home, a.cwd,
            a.model, a.max_turns, a.cli, a.timeout)
    dest = SCRATCH / "runs" / f"{a.task}.result.json"
    dest.write_text(json.dumps(r, indent=2))

    verdict = "OVER BUDGET" if r["corpus_tokens"] > 2000 else "within budget"
    print(f"{r['task']:28} corpus={r['corpus_tokens']:>6} tok  "
          f"total={r['total_tokens']:>7}  turns={r['num_turns']}  "
          f"{r['wall_s']}s  ${r['cost_usd']}  [{verdict}]")
    for rd in r["reads"]:
        print(f"    {rd['tool']:6} {rd['bytes']:>7}B  {rd['target']}")


if __name__ == "__main__":
    main()
