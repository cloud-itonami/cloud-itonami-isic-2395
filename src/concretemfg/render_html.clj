(ns concretemfg.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo: it previously shipped
  `docs/index.html` (the product face) but had NO operator-console
  sample and no generator at all. This namespace drives the REAL actor
  stack -- `concretemfg.operation` (the langgraph-clj StateGraph) ->
  `concretemfg.governor` (the independent censor) ->
  `concretemfg.store` (the SSoT + append-only ledger) -- through a
  scenario adapted from this repo's own `concretemfg.sim` demo driver
  (`clojure -M:dev:run`, run BEFORE writing this file to confirm the
  seeded ids `batch-001`..`batch-003` / `batcher-001` / `molder-002`
  really exist in `concretemfg.store/sample-data!` and really produce
  the ledger rendered here).

  WHAT IS REAL vs WHAT IS STATIC, stated plainly:

    - REAL runtime output (produced by actually executing the actor,
      never hand-typed): every batch/equipment row and its numbers,
      every `verified?`/`registered?`/ready? cell, every draft
      maintenance/shipment record number (`MNT-000000`/`SHP-000000`,
      minted by `concretemfg.registry`), every safety concern, every
      HARD-hold rule + the governor's own Japanese `:detail` string,
      and every audit-ledger row. All of it is read back out of the
      `concretemfg.store` instance that `run-demo!` returned.

    - STATIC description of fixed contract (documentation, not
      telemetry): only the `action-gate-rows` table below, which
      describes this actor's own closed four-op contract as declared by
      `concretemfg.governor/allowed-ops` and `concretemfg.phase/phases`.
      It is a description of behaviour that cannot vary between runs,
      and is labelled as such on the page.

  DETERMINISTIC by construction: no timestamps, no random, no
  wall-clock anywhere in the page content -- the only dates shown are
  the fixed seed data / fixed request values. Two consecutive runs are
  byte-identical (verify by diffing two runs to different paths).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [concretemfg.store :as store]
            [concretemfg.registry :as registry]
            [concretemfg.operation :as op]
            [langgraph.graph :as g]))

(def ^:private coordinator
  "The same operator context this repo's own `concretemfg.sim` uses --
  a phase-3 (`supervised-auto`) plant coordinator."
  {:actor-id "coord-1" :actor-role :plant-coordinator :phase 3})

(defn- exec!
  "One coordination request = one supervised actor run (one thread-id)."
  [actor tid request]
  (g/run* actor {:request request :context coordinator} {:thread-id tid}))

(defn- approve!
  "Resume a run paused by `interrupt-before #{:request-approval}` with a
  human plant supervisor's / shipping approver's approval."
  [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Builds a fresh seeded `concretemfg.store` MemStore, compiles a real
  `concretemfg.operation` actor against it, and drives every
  disposition this actor can reach. Returns the resulting store.

  Committed paths (4):
    1. `:log-production-batch` on `batch-001` -- governor-clean, and
       `:log-production-batch` is the ONLY member of phase 3's `:auto`
       set, so it AUTO-COMMITS with no human in the loop.
    2. `:schedule-maintenance` `mnt-1` against `batcher-001` (verified +
       registered batching plant) -- the governor clears it, but
       `concretemfg.phase` never puts `:schedule-maintenance` in ANY
       phase's `:auto` set, so it escalates; a human approves and it
       commits, minting draft `MNT-000000`.
    3. `:flag-safety-concern` `concern-1` -- ALWAYS escalates
       (`:coordination/safety-concern` is in
       `concretemfg.governor/high-stakes`); approved and committed.
    4. `:coordinate-shipment` `ship-1` on `batch-001` for 5000 kg --
       inside the batch's own recorded headroom (50000 kg logged,
       10000 kg already shipped); escalates, approved, commits, minting
       draft `SHP-000000` and advancing the batch's own
       `:shipped-weight-kg` to 15000.0.

  HARD holds (10) -- each exercises ONE governor rule directly rather
  than only via a happy path, and NONE of them ever reaches a human
  (the graph routes HARD holds straight to `:hold`, bypassing
  `:request-approval` entirely):
    `:not-propose-effect`, `:unknown-op` + `:mixing-line-control-blocked`,
    `:equipment-not-verified`, `:batch-not-verified`,
    `:shipment-weight-exceeded`, `:mixing-line-actuate-blocked`,
    `:already-scheduled`, `:invalid-product-type`,
    `:invalid-dimensional-deviation`, `:invalid-defect-rate`.

  The flagship one is `:mixing-line-actuate-blocked` (thread `t11`): a
  `:schedule-maintenance` proposal carrying `:actuate-mixing-line?
  true` is an attempt to directly actuate the mixing/batching plant or
  molding line. That is this actor's permanent scope boundary -- it is
  HARD and unconditional in `concretemfg.governor`, AND
  `:schedule-maintenance` is absent from every phase's `:auto` set, so
  two independent layers agree. No phase, no confidence and no human
  approval can ever override it."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]

    ;; ---- committed paths -------------------------------------------------
    (exec! actor "t1" {:op :log-production-batch :effect :propose
                       :subject "batch-001"
                       :patch {:product-type :precast-concrete-panel
                               :last-assessed "2026-07-14"}})

    (exec! actor "t2" {:op :schedule-maintenance :effect :propose
                       :subject "mnt-1"
                       :value {:equipment-id "batcher-001"
                               :maintenance-type :mixer-drum-inspection
                               :scheduled-date "2026-08-01"
                               :actuate-mixing-line? false}})
    (approve! actor "t2")

    (exec! actor "t3" {:op :flag-safety-concern :effect :propose
                       :subject "concern-1"
                       :value {:equipment-id "batcher-001" :severity :moderate
                               :description "バッチングプラント周辺のセメント粉塵滞留と養生室の蒸気漏れの兆候"}})
    (approve! actor "t3")

    (exec! actor "t4" {:op :coordinate-shipment :effect :propose
                       :subject "ship-1"
                       :value {:batch-id "batch-001" :weight-kg 5000.0
                               :destination "buyer-yard-north"}})
    (approve! actor "t4")

    ;; ---- HARD holds ------------------------------------------------------
    (exec! actor "t5" {:op :log-production-batch :effect :direct-write
                       :subject "batch-001"
                       :patch {:product-type :precast-concrete-panel}})

    (exec! actor "t6" {:op :actuate-mixing-line :effect :propose
                       :subject "batch-001"})

    (exec! actor "t7" {:op :schedule-maintenance :effect :propose
                       :subject "mnt-2"
                       :value {:equipment-id "molder-002"
                               :maintenance-type :mold-inspection
                               :scheduled-date "2026-08-01"
                               :actuate-mixing-line? false}})

    (exec! actor "t8" {:op :coordinate-shipment :effect :propose
                       :subject "ship-2"
                       :value {:batch-id "batch-003" :weight-kg 1000.0
                               :destination "buyer-yard-south"}})

    (exec! actor "t9" {:op :coordinate-shipment :effect :propose
                       :subject "ship-3"
                       :value {:batch-id "batch-002" :weight-kg 1000.0
                               :destination "buyer-yard-east"}})

    ;; The permanent boundary: direct mixing/molding-line actuation.
    (exec! actor "t11" {:op :schedule-maintenance :effect :propose
                        :subject "mnt-3"
                        :value {:equipment-id "batcher-001"
                                :maintenance-type :force-run
                                :scheduled-date "2026-09-01"
                                :actuate-mixing-line? true}})

    (exec! actor "t12" {:op :schedule-maintenance :effect :propose
                        :subject "mnt-1"
                        :value {:equipment-id "batcher-001"
                                :maintenance-type :mixer-drum-inspection
                                :scheduled-date "2026-08-01"
                                :actuate-mixing-line? false}})

    (exec! actor "t13" {:op :log-production-batch :effect :propose
                        :subject "batch-001"
                        :patch {:product-type :unobtainium-slab}})

    (exec! actor "t14" {:op :log-production-batch :effect :propose
                        :subject "batch-001"
                        :patch {:dimensional-deviation-percent 999.0}})

    (exec! actor "t15" {:op :log-production-batch :effect :propose
                        :subject "batch-001"
                        :patch {:defect-rate-percent 999.0}})
    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- kw-name [v]
  (if (keyword? v) (name v) (str v)))

(defn- last-fact-for [ledger subject]
  (last (filter #(= (:subject %) subject) ledger)))

(defn- status-cell
  "Last disposition the ledger recorded for `subject` -- real, read back
  out of the store's append-only ledger."
  [ledger subject]
  (let [f (last-fact-for ledger subject)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :governor-hold (:t f))
      (str "<span class=\"critical\">HARD hold &middot; "
           (esc (kw-name (or (-> f :violations first :rule) :unknown)))
           "</span>")
      :else (str "<span class=\"muted\">" (esc (kw-name (:t f))) "</span>"))))

(defn- ready-cell [ready?]
  (if ready?
    "<span class=\"ok\">verified &amp; registered</span>"
    "<span class=\"critical\">NOT verified/registered &middot; blocked</span>"))

(defn- batch-row
  [ledger {:keys [id product-type material weight-kg shipped-weight-kg
                  dimensional-deviation-percent defect-rate-percent
                  last-assessed]
           :as b}]
  (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s / %s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc (kw-name product-type)) (esc material)
          (esc weight-kg) (esc shipped-weight-kg)
          (esc (- (double (or weight-kg 0.0)) (double (or shipped-weight-kg 0.0))))
          (esc dimensional-deviation-percent) (esc defect-rate-percent)
          (str (ready-cell (registry/batch-ready? b))
               " <span class=\"muted\">(assessed " (esc last-assessed) ")</span>")
          (status-cell ledger id)))

(defn- equipment-row
  [{:keys [id kind last-maintenance-date last-scheduled-maintenance-date] :as eq}]
  (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc (kw-name kind))
          (ready-cell (registry/equipment-ready? eq))
          (if last-maintenance-date
            (esc last-maintenance-date)
            "<span class=\"muted\">never</span>")
          (if last-scheduled-maintenance-date
            (str "<span class=\"ok\">" (esc last-scheduled-maintenance-date) "</span>")
            "<span class=\"muted\">none this run</span>")))

(defn- hold-rows
  "One row per governor violation actually raised in this run -- rule
  keyword, subject, and the governor's OWN `:detail` string (not a
  paraphrase)."
  [ledger]
  (for [f ledger
        :when (= :governor-hold (:t f))
        v (:violations f)]
    (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td></tr>"
            (esc (kw-name (:rule v)))
            (esc (kw-name (or (:op f) :n-a)))
            (esc (:subject f))
            (esc (:detail v)))))

(defn- ledger-row [{:keys [t op subject disposition basis confidence]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (if (= :committed t)
            "<span class=\"ok\">committed</span>"
            (str "<span class=\"critical\">" (esc (kw-name t)) "</span>"))
          (esc (kw-name (or op :n-a)))
          (esc subject)
          (esc (kw-name (or disposition "")))
          (esc (str/join ", " (map kw-name basis)))
          (esc confidence)))

(defn- draft-row [{:strs [record_id kind maintenance_id equipment_id shipment_id immutable]}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td><td>%s</td></tr>"
          (esc record_id) (esc kind)
          (esc (or maintenance_id shipment_id))
          (if immutable
            (str "<span class=\"ok\">immutable</span> <span class=\"muted\">&middot; "
                 (esc (or equipment_id "-")) "</span>")
            "<span class=\"warn\">mutable</span>")))

(defn- concern-row [{:keys [id equipment-id severity description]}]
  (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc (or equipment-id "-")) (esc (kw-name severity)) (esc description)))

(def ^:private action-gate-rows
  ;; STATIC description of this actor's own closed op contract
  ;; (`concretemfg.governor/allowed-ops`, `concretemfg.phase/phases`,
  ;; README `What this actor does`). Fixed behaviour, identical on every
  ;; run -- documentation, NOT runtime telemetry, and labelled as such
  ;; on the page. Every other table on the page is real actor output.
  ["        <tr><td><code>:log-production-batch</code></td><td><span class=\"ok\">phase-3 auto-commit when governor-clean</span> &middot; the only member of any phase's <code>:auto</code> set</td></tr>"
   "        <tr><td><code>:schedule-maintenance</code></td><td><span class=\"warn\">ALWAYS human approval</span> &middot; never auto at any phase (real downtime, equipment is actually touched) &middot; equipment re-verified independently</td></tr>"
   "        <tr><td><code>:flag-safety-concern</code></td><td><span class=\"warn\">ALWAYS human approval</span> &middot; <code>:coordination/safety-concern</code> is permanently high-stakes &middot; never blocked on an administrative technicality</td></tr>"
   "        <tr><td><code>:coordinate-shipment</code></td><td><span class=\"warn\">ALWAYS human approval</span> &middot; batch re-verified and shipment weight independently recomputed from the batch's own logged production weight</td></tr>"
   "        <tr><td><code>:actuate-mixing-line?&nbsp;true</code> / any other effect</td><td><span class=\"critical\">PERMANENT HARD hold</span> &middot; direct mixing/batching-plant or molding-line control is outside this actor's authority &mdash; no phase, no confidence and no human approval can override it</td></tr>"])

(defn render
  "Renders the full operator-console document from a store `db` that has
  already been driven by `run-demo!` (or any other real scenario).
  Every table except `Action gate` is read back out of `db`."
  [db]
  (let [ledger (vec (store/ledger db))
        batches (store/all-batches db)
        equipment (store/all-equipment db)
        concerns (vec (store/safety-concerns db))
        drafts (concat (store/maintenance-history db) (store/shipment-history db))
        holds (hold-rows ledger)]
    (str
     "<!DOCTYPE html>\n"
     "<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
     "<meta name=\"color-scheme\" content=\"light\">"
     "<title>cloud-itonami-isic-2395 &middot; concretemfg &middot; Operator Console</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Articles of concrete, cement and plaster (ISIC 2395) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · maintenance / safety / shipment always human-approved · direct mixing-line actuation permanently blocked</span>\n"
     "</header>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>Production batches</h2>\n"
     "    <p class=\"muted\">Build-time snapshot generated from <code>concretemfg.store</code> by <code>concretemfg.render-html</code> (<code>clojure -M:dev:render-html</code>) after driving the real <code>concretemfg.operation</code> actor. <code>verified?</code>/<code>registered?</code> are re-derived through <code>concretemfg.registry/batch-ready?</code>, never taken from an advisor rationale.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Batch</th><th>Product type</th><th>Material</th><th>Logged weight (kg)</th><th>Shipped (kg)</th><th>Headroom (kg)</th><th>Dim. dev. / defect (%)</th><th>Ground truth</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial batch-row ledger) batches)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Plant equipment</h2>\n"
     "    <p class=\"muted\">Mixing/batching-plant and molding-line units. Maintenance may only ever be <em>scheduled</em> against a unit that is independently both verified and registered (<code>concretemfg.registry/equipment-ready?</code>) — and only ever as a draft window, never as an actuation.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Unit</th><th>Kind</th><th>Ground truth</th><th>Last maintenance (seed)</th><th>Scheduled this run</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map equipment-row equipment)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Action gate (Concrete Plant Operations Governor)</h2>\n"
     "    <p class=\"muted\">Static description of this actor's fixed, closed four-op contract (<code>concretemfg.governor/allowed-ops</code> + <code>concretemfg.phase/phases</code>) — documentation of behaviour that cannot vary between runs, not runtime telemetry. Every other table on this page is real output from the run below.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>HARD holds raised in this run</h2>\n"
     "    <p class=\"muted\">Real governor verdicts. A HARD violation routes straight to <code>:hold</code> — it never reaches <code>:request-approval</code>, so no human can approve it. The <code>detail</code> column is the governor's own message, verbatim.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Rule</th><th>Op</th><th>Subject</th><th>Governor detail</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" holds) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Draft registry records</h2>\n"
     "    <p class=\"muted\">Minted by <code>concretemfg.registry</code> on commit. These are DRAFTS a plant coordinator would keep — a scheduled maintenance window and a coordinated shipment — never an actuation of the mixing/batching plant or a dispatched freight carrier. Certificates are issued unsigned; signature is the human approver's act.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Record</th><th>Kind</th><th>Subject</th><th>Status</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map draft-row drafts)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Safety concerns</h2>\n"
     "    <p class=\"muted\">Cement/silica-dust, curing-heat and pinch-point hazards. Always escalated to a human plant supervisor regardless of confidence, and never gated on the referenced equipment being verified.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Concern</th><th>Equipment</th><th>Severity</th><th>Description</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map concern-row concerns)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">The append-only decision-fact log — every commit and every hold this scenario produced, in order, exactly as <code>concretemfg.store/ledger</code> returns it.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Disposition</th><th>Basis</th><th>Confidence</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map ledger-row ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "</main>\n"
     "<footer><p class=\"muted\">Generated by <code>concretemfg.render-html</code> from a real actor run — "
     (count ledger) " ledger facts, " (count batches) " batches, "
     (count equipment) " equipment units, " (count drafts) " draft records, "
     (count concerns) " safety concerns. Deterministic: no timestamps, no random, byte-identical across reruns.</p></footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (io/make-parents out)
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/all-batches db)) "batches,"
             (count (store/all-equipment db)) "equipment,"
             (count (store/maintenance-history db)) "maintenance drafts,"
             (count (store/shipment-history db)) "shipment drafts,"
             (count (store/safety-concerns db)) "safety concerns )")))
