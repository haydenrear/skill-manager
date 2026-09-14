---
type: regex
pattern: "([Rr]ais(e|ing)|[Ii]ncreas(e|ing)|[Ee]xtend|[Bb]ump|[Ll]engthen|[Ll]onger)[^\\n]{0,60}([Tt]imeout|[Ll]imit|[Jj]ob)|([Ss]hrink|[Ss]horten|[Rr]educe)[^\\n]{0,40}([Ss]cenario|[Rr]epro|[Tt]est)|([Dd]rop|[Ll]ower|[Cc]ut|[Ss]et|[Mm]ove)[^\\n]{0,40}(internal|own|test('s)?)\\s+timeout"
weight: 2
---

The final response proposes raising the job limit or shrinking the scenario, the
remedy 31cf945 names when the timeout is shorter than time-to-outcome. Reads
the response only and cannot tell a recommendation from a mention; the llm
grader decides whether the mismatch itself was identified.
