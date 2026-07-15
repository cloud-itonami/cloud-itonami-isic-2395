# ADR-0001: ConcreteAdvisor ⊣ Concrete Plant Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-2395` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by prior actors in this
fleet.

## Context

`cloud-itonami-isic-2395` publishes an OSS blueprint for concrete/
cement/plaster products plant **operations coordination**
(production-batch product-type/weight/dimensional-deviation/defect-
rate data logging, mixing/batching-plant or molding-line-equipment
maintenance scheduling, safety-concern flagging, and outbound
concrete/cement/plaster product shipment coordination). Like every
actor in this fleet, the blueprint alone is not an implementation:
this ADR records the governed-actor architecture that promotes it to
real, tested code, following the same langgraph StateGraph +
independent Governor + Phase 0->3 rollout pattern established across
the cloud-itonami fleet.

The closest architectural analog is `cloud-itonami-isic-2392`
(Manufacture of clay building materials): both are back-office
coordination actors for a fixed processing PLANT with heavy
manufacturing equipment and a real physical safety dimension, and both
share the same four-op shape (`:log-production-batch`/
`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment`)
and the same two-entity verified/registered gate structure (equipment
for maintenance scheduling, batch for shipment coordination). The two
verticals are, however, distinct plants with distinct hazard profiles:
2392's central physical hazard is kiln-firing heat and clay/silica-
dust exposure (kiln-fire/thermal-hazard at the firing zone, clay/
silica-dust hazard at pugging/extrusion, extrusion-press pinch-point
hazard), while 2395's is cement/silica-dust exposure at batching and
mold-stripping plus curing-heat/steam-burn hazard at the curing
chamber or autoclave, and mixing/batching-plant pinch-point hazard.
This build mirrors 2392's architecture closely but adapts the hazard
profile and equipment/product vocabulary to the concrete/cement/
plaster plant: 2395's permanent equipment-actuation block guards a
mixing/batching plant or molding line (`:actuate-mixing-line?`) rather
than an extrusion press/kiln line (`:actuate-kiln-line?`); and 2395's
production-batch record declares a `:product-type` (spanning precast
panel, pipe, masonry block, paving slab, plasterboard, fiber-cement
sheet, roof tile and post families, per ISIC 2395's own combined
scope), a `:dimensional-deviation-percent` and a `:defect-rate-
percent`, the same field shape as 2392's own record but scoped to this
vertical's own product families.

`cloud-itonami-isic-2395` is also distinct from
`cloud-itonami-isic-2392` (Manufacture of clay building materials, a
distinct plant that wins/pugs clay and fires it in a kiln rather than
mixing/casting a cementitious mix) and from
`cloud-itonami-isic-2396` (Cutting, shaping and finishing of stone, a
distinct vertical that cuts and finishes natural stone rather than
casting a manufactured concrete/cement/plaster mix) -- neither of
which this build depends on or wraps.

This vertical has NO pre-existing `kotoba-lang/concretemfg`-style
capability library to wrap (verified: no such repo exists). This build
therefore uses self-contained domain logic -- pure functions in
`concretemfg.registry` (equipment/batch verification, shipment-weight
recompute, product-type validation, dimensional-deviation plausibility
validation, defect-rate plausibility validation) are re-verified
independently by the governor, the same "ground truth, not
self-report" discipline established across prior actors (most
directly `cloud-itonami-isic-2392`'s `claymfg.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:concrete-plant-operations-governor`, is grep-verified UNIQUE
fleet-wide (`gh search code "concrete-plant-operations-governor"
--owner cloud-itonami`, zero hits before this repo was created); the
`concretemfg` namespace prefix is likewise grep-verified UNIQUE
fleet-wide (`gh search code "concretemfg" --owner cloud-itonami`, zero
hits before this repo was created).

## Decision

### Decision 1: Self-contained domain logic (no external concrete-products capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
concrete/cement/plaster products vertical has NO pre-existing
capability library to wrap. The equipment/batch-verification /
shipment-weight / product-type / dimensional-deviation / defect-rate
validation functions live as pure functions in `concretemfg.registry`
and are re-verified independently by `concretemfg.governor` -- the
same "ground truth, not self-report" discipline established across
prior actors (most directly `cloud-itonami-isic-2392`'s
`claymfg.registry`).

### Decision 2: Coordination, not control — scope boundary at the back-office

This actor is **strictly back-office coordination** of concrete/
cement/plaster products plant operations. It does NOT:
- Control the mixing/batching plant or molding line equipment directly
- Make plant-safety or materials-safety decisions (exclusive to the human plant supervisor)
- Actuate the mixing/batching plant or molding line

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human plant-supervisor
approval. This is not a replacement for the supervisor's authority --
it is a proposal-screening and documentation layer.

**CRITICAL SAFETY BOUNDARY**: concrete/cement/plaster products
manufacturing is a safety-critical domain (cement/silica-dust
exposure, curing-heat/steam-burn hazard, mixing/batching-plant
pinch-point hazard, heavy precast-unit handling hazard). Safety-
concern flagging NEVER auto-commits. All safety concerns escalate
immediately to human review.

### Decision 3: Safety-concern escalation — always human sign-off

`:flag-safety-concern` (cement/silica-dust hazard, curing-heat/steam-
burn hazard, mixing/batching-plant-equipment safety concern, crew
fatigue) ALWAYS escalates, never auto-commits. This is not a
"low-stakes proposal" -- it is a circuit-breaker that must reach human
authority.

### Decision 4: Two independent verified/registered gates (equipment AND batch), not one

Like `cloud-itonami-isic-2392`, this vertical has TWO entity kinds
each gating a different op: `:schedule-maintenance` independently
verifies the referenced **equipment** unit's own `:verified?`/
`:registered?` fields; `:coordinate-shipment` independently verifies
the referenced **batch**'s own `:verified?`/`:registered?` fields.
Both are the same "plant/batch record must be independently verified/
registered before any action" HARD invariant applied to the two
distinct record kinds this domain actually has.
`:coordinate-shipment` additionally independently recomputes whether a
batch's own recorded shipped-to-date weight plus the proposal's own
claimed weight would exceed the batch's own recorded production
weight -- never taken on the advisor's self-report.

### Decision 5: HARD invariants (no override)

Four HARD governor invariants (elaborated into eleven concrete checks
in `concretemfg.governor`, matching `cloud-itonami-isic-2392`'s own
eleven) block proposals and cannot be overridden by human approval:
1. Plant/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action is taken against it, and a shipment's weight must independently recompute within the batch's own logged production weight
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Direct mixing/batching-plant or molding-line-equipment control or actuation is permanently blocked
4. The op allowlist is closed -- `:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` only

## Consequences

(+) Concrete/cement/plaster products plant operations back-office now
has a documented, governed, auditable coordination layer that funnels
all decisions through independent validation before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human plant-
supervisor sign-off.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into eleven concrete governor checks) protect against scope creep into
unauthorized equipment operation or mixing/molding-line actuation.
Safety concerns are a circuit-breaker, not a threshold.

(+) Safety-critical discipline is explicit: safety-concern flagging
cannot be rate-limited, suppressed, or auto-decided by phase gate.
Human review is mandatory.

(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation and mixing/molding-line operation
remain human-controlled via external channels.

(-) No integration with real plant-management databases (equipment
telemetry, batch tracking, freight dispatch) -- this is a standalone
coordinator blueprint.

## Verification

- `cloud-itonami-isic-2395`: `clojure -M:test` green (all tests pass;
  see the superproject ADR and `kotoba-lang/industry` registry entry
  for the exact `Ran N tests containing M assertions, 0 failures, 0
  errors` output, verified from an independent fresh clone), `clojure
  -M:lint` clean, `clojure -M:dev:run` demo narrative exercises
  proposal submission, escalation, and every HARD-hold scenario
  directly (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-weight-exceeded, mixing-line-actuate-
  blocked, already-scheduled, invalid-product-type, invalid-
  dimensional-deviation, invalid-defect-rate).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) -- no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `clojure -M:test` resolves offline inside the monorepo checkout.
