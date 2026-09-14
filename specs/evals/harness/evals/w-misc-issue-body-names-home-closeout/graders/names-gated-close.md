---
type: regex
pattern: "(?:\\bwt\\b|\\$\\{?WT\\}?\"?|skt\\s+ticket)\\s+close\\b"
weight: 3
---

The drafted body in the final response tells the implementer to tear down with
`wt close`, `"$WT" close` or `skt ticket close`, the gated command in the
git-issue template (307da5d, c0821bb). Reads the response only.
