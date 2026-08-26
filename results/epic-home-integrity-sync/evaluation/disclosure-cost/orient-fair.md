Your session has SKILL_MANAGER_HOME set in the environment. You are an agent about
to start ticket work in this repository's Skill Manager home. Before you change
anything, answer these four questions.

1. Which home tier is $SKILL_MANAGER_HOME — root, project, or worktree — and how do
   you know?
2. What, if anything, is this home a copy of, and what does it inherit from that
   source?
3. Suppose you edit a skill's SKILL.md inside this home. Give the exact commands, in
   order, that make that edit (a) reach the tier above and (b) reach the skill's own
   git repository.
4. Name one path this session must NEVER write to, and say why.

RULES:
- For every answer, cite your EVIDENCE: either a file path you read, or the exact
  command you ran and the line of its output that says it. Both count equally.
- If something is not stated anywhere you can find, answer exactly NOT ANSWERABLE.
  That is a CORRECT and VALUABLE answer here.
- Do not answer from background knowledge of this product. Do not guess. A confident
  guess corrupts this measurement; NOT ANSWERABLE does not.
- Be economical: stop as soon as you can answer all four with evidence.

Output ONLY a JSON object, no prose around it:
{"q1":{"answer":"...","evidence":"...","quote":"..."},
 "q2":{...},"q3":{...},"q4":{...}}
