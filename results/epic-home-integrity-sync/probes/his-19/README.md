# HIS-19 probe artifacts

| file | what it is |
| --- | --- |
| `BASELINE.out` | a clean run of `RunHis19.java`, no mutation — the colour every probe is a departure from, and the source of `labels.txt`. |
| `labels.txt` | the assertion labels this suite declares, captured from the baseline. How a real assertion is told from text matched inside a failure message. |
| `probes.py` | the vacuity harness. `python3 probes.py [--baseline] [V1 V2 …]`. Copies the production file aside, applies ONE mutation, runs `jbang RunHis19.java`, restores by **copying the saved file back**. Asserts each pattern matched **exactly once** before applying it (mechanism C), and strips the five home env vars before running (DEF-074) so the baseline is not read against noise. |
| `V<n>.out` | one per probe: the mutation, the file, **the branch it moves**, the verdict, and how many reds landed on a CLAIM versus on a PRECONDITION. |
| `V8.out` | **declared expected-green before it was run.** See its header: the two gates it disables are refused again by the same-file gate below them, so they are ordering and message quality rather than semantics. Ledger row 12's rule is that an all-green probe is a failed probe *unless it was declared*, so it is declared. |
| `transcript.sh` | the command-level before/after. Builds a fake Homebrew prefix, provisions two homes over it (one durable, one in the pre-HIS-19 shape), runs `brew upgrade` twice, and reports `home verify`'s exit code at every step. |
| `transcript.out` | that script against **this branch**. |
| `transcript-pre-his19.out` | the same script against the **pre-HIS-19 behaviour**, restored by mutation. This is where the headline number comes from: after the upgrade both homes are exit 127 and `home verify` returns **exit 0** on both. |
| `real-machine.out` | `DurableCliPin` run against the operator's **real** `/opt/homebrew` install, including the exact path the root home currently pins. |

## Restoring after a mutation

`cp` from the saved copy. **Never `git checkout --`, `git restore`, `git stash`
or `git clean`** — DEF-035. `probes.py` restores with `shutil.copy2` in a
`finally`; the two hand-run mutations for `transcript-pre-his19.out` were saved
to `probe/*.bak` and copied back the same way.

## The one that matters most

`transcript-pre-his19.out`. Every other artifact here says what the new code
does; that one says what the old code did, in the same sandbox, with the same
script, and it is the only file that establishes there was anything to fix.
