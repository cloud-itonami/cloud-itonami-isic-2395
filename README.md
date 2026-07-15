# cloud-itonami-isic-2395: Manufacture of articles of concrete, cement and plaster

Open Business Blueprint for **ISIC Rev.5 2395**: manufacture of articles of concrete, cement and plaster — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office **concrete/cement/plaster products plant operations**: production-batch data logging (product-type/weight/dimensional-deviation/defect-rate), mixing/batching-plant or molding-line-equipment maintenance scheduling, safety-concern flagging, and outbound concrete/cement/plaster product shipment coordination.

This repository designs a forkable OSS business for concrete/cement/
plaster products plant operations: run by a qualified operator so a
precast/masonry plant keeps its own operating records instead of
renting a closed SaaS.

## Scope: concrete/cement/plaster products plant, not clay building materials or cut stone

ISIC 2395 covers the **concrete/cement/plaster products plant** that
mixes/batches concrete, cement mortar or plaster slurry, molds or
casts it into forms, and cures it (steam curing, autoclave curing, or
ambient curing) — producing precast concrete panels, concrete pipe,
concrete masonry block, paving slabs, plasterboard, fiber-cement
sheet, concrete roof tile, or concrete posts, ready to sell or ship.
This is distinct from `cloud-itonami-isic-2392` (Manufacture of clay
building materials), which wins/pugs clay and fires it in a kiln, and
from `cloud-itonami-isic-2396` (Cutting, shaping and finishing of
stone), a separate vertical that cuts and finishes natural stone
rather than casting a manufactured cementitious mix. This actor's own
hazard profile is centered on the mixing/batching and curing lines:
cement/silica-dust hazard at batching and mold-stripping, curing-heat
hazard (steam curing/autoclave burn or scald exposure) at the curing
chamber, and mixing/batching-plant pinch-point hazard.

## What this actor does

Proposes **plant operations coordination**, not equipment operation:
- `:log-production-batch` — product-type/weight/dimensional-deviation/defect-rate data logging (administrative, not an operational decision)
- `:schedule-maintenance` — mixing/batching-plant or molding-line-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface a materials-safety/equipment-safety concern (silica dust, curing-heat), always escalates
- `:coordinate-shipment` — outbound concrete/cement/plaster product shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY — this is a safety-critical domain**
(cement/silica-dust exposure, curing-heat/steam-burn hazard,
mixing/batching-plant pinch-point hazard, heavy precast-unit handling
hazard):

- Does NOT control the mixing/batching plant or molding line equipment directly
- Does NOT make plant-safety or materials-safety decisions (that's the plant supervisor's exclusive human authority)
- Does NOT actuate the mixing/batching plant or molding line (human plant supervisor decides)
- ONLY proposes/coordinates operations back-office; all actuation requires explicit human approval
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`concretemfg.operation/build`, a langgraph-clj StateGraph):
1. **`concretemfg.advisor`** (sealed intelligence node, `ConcreteAdvisor`): proposes decisions only, never commits
2. **`concretemfg.governor`** (independent, `Concrete Plant Operations Governor`): validates against domain rules, re-derived from `concretemfg.registry`'s pure functions and `concretemfg.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Plant/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct mixing/molding-line-equipment control)
     - Directly actuating the mixing/batching plant or molding line (`:actuate-mixing-line? true`) is a PERMANENT, unconditional block
     - A shipment may not push a batch's own recorded shipped weight past its own logged production weight (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:product-type` value on a production-batch patch
     - No physically implausible `:dimensional-deviation-percent` value on a production-batch patch
     - No physically implausible `:defect-rate-percent` value on a production-batch patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`concretemfg.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`concretemfg.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
clojure -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
