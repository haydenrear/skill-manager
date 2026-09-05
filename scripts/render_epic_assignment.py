import json, sys, subprocess
sys.path.insert(0,'.skill-manager/skills/spec-double-compiler/scripts')
from extract_spec_manifest import parse_simple_yaml

PLAN_COMMIT = subprocess.run(["git","rev-parse","HEAD"],capture_output=True,text=True).stdout.strip()[:40]
BASE = "4423b80"
KIND = {  # our vocabulary -> the schema's GOAL_KINDS
 'correctness':'quality','capability':'integration','diagnosis':'quality',
 'analysis':'quality','design':'quality',
}
GOALKIND_OVERRIDE = {'GOAL-propagation-paths-are-classified':'eval'}
CONTRIB = {'primary':'direct','measurement':'direct','supporting':'enabling',
           'measurement-and-contract':'guard','enabling':'enabling'}
DECIDER = {'home-boundary-resolution':'HBR-5','substrate-home-model':'SUB-6'}
GRAPHS = {'home-boundary-resolution':['home-integrity','checkout-home','project-child-home'],
          'substrate-home-model':['home-clone','home-sync','project-child-home']}

def q(s):
    s=' '.join(str(s).split())
    return json.dumps(s)

def block(slug, plan, t):
    goals={g['id']:g for g in plan['epic_goals']}
    ev=DECIDER[slug]
    is_eval = t['id']==ev
    L=[]
    A=L.append
    A("<!-- git-epic-workflow:assignment:start -->")
    A("```yaml")
    A("version: 1")
    A("epic:")
    A(f"  id: {slug}")
    A(f"  workflow: {plan['name']}")
    A(f"  branch: {plan['branch']}")
    A(f"  base_sha: {BASE}")
    A(f"  plan_commit: {PLAN_COMMIT}")
    A("  default_branch: main")
    A(f"  schedule_revision: {int(plan['schedule_revision'])}")
    A("ticket:")
    A(f"  spec_id: {t['id']}")
    A(f"  feature_branch: feature/{t['id'].lower()}")
    A(f"  worktree: ../wt-{t['id'].lower()}")
    A(f"  pr_base: {plan['branch']}")
    A(f"  wave: {int(t['wave'])}")
    A(f"  promotion_order: {int(t['promotion_order'])}")
    pred=t.get('promotion_predecessor')
    A(f"  promotion_predecessor: {pred if pred and pred!='null' else 'null'}")
    A(f"  role: {'evaluation' if is_eval else 'implementation'}")
    if is_eval:
        # An evaluation ticket must NAME the goals it decides, or it is an
        # evaluation that decides nothing.
        A(f"  owns_goals: {json.dumps([g['goal'] for g in (t.get('goals') or [])])}")
    A("  conflict_keys:")
    ck=t.get('conflict_keys') or {}
    for kind in ("production","tla","adapters","test_graph","workflow"):
        A(f"    {kind}: {json.dumps(list(ck.get(kind) or []))}")
    A("goals:")
    for entry in (t.get('goals') or []):
        gid=entry['goal']; g=goals[gid]
        kk=str(g['kind'])
        k=kk if kk in ('perf','eval','integration','quality') else KIND[kk]
        A(f"  - goal: {gid}")
        A(f"    kind: {k}")
        A(f"    statement: {q(g['statement'])}")
        A(f"    metric: {q(g['metric'])}")
        A(f"    baseline: {q(g['baseline']['value'])}")
        A(f"    target: {q(g['target'])}")
        if not is_eval:
            A(f"    contribution: {entry['contribution']}")
        A(f"    expected_effect: {q(entry['expected_effect'])}")
        A(f"    local_signal: {q(entry['local_signal'])}")
        A("    decided_by:")
        A(f"      ticket: {t['id'] if is_eval else ev}")
        A(f"      harness: {q(g['harness'])}")
    A("validation:")
    A('  tlc: "N/A: the spec workflow for this epic is STAGED, not scaffolded — child-home-materialization-workflow still occupies specs/current and specs/desired_program_model, so there is no TLC model for this ticket to check yet."')
    A('  spec_unit: "python3 .skill-manager/skills/spec-double-compiler/scripts/run_spec_units.py"')
    A('  repository_unit: "jbang RunTests.java"')
    A(f'  spec_graph: "N/A: staged epic, see validation.tlc — the graphs named in conflict_keys.test_graph are the repository graphs this ticket must keep green, run with the test_graph runner named in the plan"')
    A('  toolchain_spec_workflow: "N/A: this epic does not change spec-double-compiler or its toolchain."')
    A(f'  evidence_root: results/epic-{slug}/tickets/{t["id"]}/')
    gl=list((t.get('conflict_keys') or {}).get('test_graph') or []) or ["home-integrity"]
    A(f"  graphs: {json.dumps(gl)}")
    A("review:")
    A("  mode: external")
    A("  ticket_agent_stops_after: pr_open")
    A("  merged_by: epic-owner")
    A(f"  cadence: {plan['review_policy']['cadence']}")
    A(f"  gate: {str(plan['review_policy']['gate']).lower() if not isinstance(plan['review_policy']['gate'],str) else plan['review_policy']['gate']}")
    A(f"  artifact_root: {plan['review_policy']['artifact_root']}")
    A("deferment:")
    A(f"  mode: {plan['deferment_policy']['mode']}")
    A(f"  blocking: {plan['deferment_policy']['blocking']}")
    A(f"  budget: {int(plan['deferment_policy']['budget'])}")
    A(f"  backlog: {plan['deferment_policy']['backlog']}")
    A("```")
    A("<!-- git-epic-workflow:assignment:end -->")
    return "\n".join(L)

if __name__=="__main__":
    slug=sys.argv[1]; tid=sys.argv[2]
    plan=parse_simple_yaml(open(f'specs/epics/{slug}/ticket_plan.yaml').read())
    t=[x for x in plan['tickets'] if x['id']==tid][0]
    print(block(slug, plan, t))
