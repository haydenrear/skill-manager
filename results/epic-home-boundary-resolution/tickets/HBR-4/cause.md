# HBR-4 — what actually causes `Not logged in`

Measured 2026-08-30 against Claude Code **2.1.251** on macOS 25.6.0.

Three explanations were on the table when this ticket opened. Two were already
written down as fact. **Both are wrong**, and the option set changes with them.

## The three candidates, discriminated

| # | Claim | Source | Verdict |
| --- | --- | --- | --- |
| A | The macOS keychain is keyed to the operator's default config dir and `exec` puts it out of reach | #263 as filed | **Half right.** The keychain is reachable — `exec` never overrides `HOME` — but the *service name* the CLI asks for does change with the config dir. |
| B | The redirected `CLAUDE_CONFIG_DIR` simply has no authenticated `.claude.json` in it | the code survey, and the ticket brief | **Wrong, measured.** |
| C | `CLAUDE_CONFIG_DIR` silently renames the credential slot | this ticket | **Confirmed, and it is the cause.** |

### Disproving B

An `oauthAccount`-bearing `.claude.json` was written into an otherwise empty
config directory (keys copied from the operator's own: `oauthAccount`,
`userID`, `hasCompletedOnboarding`, `firstStartTime`, `numStartups`):

```
$ CLAUDE_CONFIG_DIR=<scratch>/withjson claude -p 'reply OK'
Not logged in · Please run /login
```

So the missing file is not what stops the launch.

### Confirming C

The CLI derives its credential slot from the config directory. From the shipped
bundle, verbatim:

```js
var s5 = "-credentials";
function a0(n = "") {
  let e = process.env.CLAUDE_SECURESTORAGE_CONFIG_DIR,
      t = e !== void 0 ? !e : !process.env.CLAUDE_CONFIG_DIR,
      r = e !== void 0 ? e.normalize("NFC") : be(),
      c = t ? "" : `-${createHash("sha256").update(r).digest("hex").substring(0, 8)}`;
  return `Claude Code${zt().OAUTH_FILE_SUFFIX}${n}${c}`;
}
function $T() {
  let n = process.env.CLAUDE_SECURESTORAGE_CONFIG_DIR;
  if (n !== void 0) return (n || join(homedir(), ".claude")).normalize("NFC");
  return be();
}
```

`a0("-credentials")` is the keychain service name; `$T()` is the directory the
file-backed store uses on platforms without a keychain. Both take
`CLAUDE_SECURESTORAGE_CONFIG_DIR` first and fall back to the effective config
directory.

The keychain on this machine agrees:

```
$ security dump-keychain | grep -i svce | grep -i claude | sort -u
    "svce"<blob>="Claude Code-credentials"
    "svce"<blob>="Claude Code-credentials-84a08daf"
```

An operator who logged in normally has the **unsuffixed** entry. A home that
redirects `CLAUDE_CONFIG_DIR` asks for `Claude Code-credentials-<hash>`, which
nobody has written — hence `Not logged in`, exit 0, `Churned for 0s`.

## The value has to be the empty string, and that is the trap

Read the derivation again: `t` is true only when the variable is set **and
empty**. Only then is the suffix dropped. Three cells, same config dir:

| `CLAUDE_SECURESTORAGE_CONFIG_DIR` | result |
| --- | --- |
| *(unset)* | `Not logged in · Please run /login` |
| `/Users/hayde/.claude` | `Not logged in · Please run /login` |
| *(empty)* | `OK` |

The middle row is the one that matters for review: naming the operator's own
config directory is the spelling every reader reaches for, it looks more
correct than the empty string, and it fails identically to doing nothing. It is
the single most likely way for this fix to be silently undone.

The empty value is also right on file-backed platforms: `$T()` resolves
`value || ~/.claude`, so empty means the operator's real
`~/.claude/.credentials.json`. One value covers both backends.
