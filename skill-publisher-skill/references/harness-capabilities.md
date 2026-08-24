# Harness capability matrix for skt disclosure

What "startup disclosure" means differs per harness, and the epic's
goals are scored against THIS declared capability — not against literal
hook injection everywhere (recorded on epic issue
haydenrear/skill-manager-integration-repository#72).

| Harness | Plugin registration | Startup disclosure | Session notifications |
| --- | --- | --- | --- |
| claude | marketplace (per-home name since skill-manager#165) | **injection** — `SessionStart` hook prints `skt status`; stdout becomes session context | **injection** — `PostToolUse` hook surfaces `skt check --cached` exit-10 output via `additionalContext` |
| codex | marketplace (`codex plugin marketplace`; uninstall is a documented CLI no-op) | **instruction** — no hook runtime; the projected skt skill plus the AGENTS.md snippet below tell the agent to run `skt status` first | **instruction** — the skill instructs `skt check` before starting work |
| gemini | skills projection only (no plugin runtime) | **instruction** — projected skill + GEMINI.md snippet | **instruction** |

Every hook invocation that injects appends one line to
`<home>/logs/skt/hook.log` — the mode-independent postcondition the eval
suite scores. Instruction-mode harnesses have no such line; their
disclosure is scored on whether the agent runs the instructed command.

## The instruction snippet (AGENTS.md / GEMINI.md)

Bind this via skill-manager's doc mechanisms (managed imports) or paste
it into the project's agent file for harnesses without a hook runtime:

```markdown
Before starting work in this checkout, run `skt status` — it is on
PATH in a home that installed the skt plugin, at `<home>/bin/cli/skt`.
If it is not there, this checkout's home does not carry skt; use
`skill-manager list` and `skill-manager home describe --json` instead,
and see the skt skill for the rest of the fallbacks. Read its report: loaded skills/plugins,
which home tier this session writes, ticket/epic state, and pending
gates. Run `skt check` to learn about stale units before relying on a
skill.
```

## Hook failure policy

Both hook scripts exit 0 unconditionally — a broken orientation hook
must never break the session it orients. Failures are recorded in
`hook.log` (`skt-unresolvable`, `status-failed`) instead of surfacing
as session errors.
