(ns concretemfg.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2: this repo had a hand-maintained
  `docs/index.html` product face but NO generated operator console and
  no generator at all. This namespace drives the REAL actor stack --
  `concretemfg.operation` (langgraph StateGraph) ->
  `concretemfg.advisor` -> `concretemfg.governor` -> `concretemfg.phase`
  -> `concretemfg.store` -- through a scenario built ONLY out of this
  repo's own seed data (`concretemfg.store/sample-data!`: `batch-001` /
  `batch-002` / `batch-003`, `batcher-001` / `molder-002`), and renders
  the resulting store + audit ledger. Every id, weight, disposition,
  rule name and rule detail on the page is output the actor actually
  produced during the build -- nothing on the page is hand-typed
  domain content.

  Determinism: no timestamps, no clock reads, no randomness; every set
  and map read out of the code (phase table, op allowlist, product-type
  allowlist) is sorted explicitly rather than relying on iteration
  order. Two runs are byte-identical.

  Refusal: `-main` throws and writes NOTHING when the run produced zero
  HARD governor holds. A console that shows no real hold is exactly the
  console this page exists to disprove, so it must not be writable by
  accident.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [concretemfg.store :as store]
            [concretemfg.operation :as op]
            [concretemfg.governor :as governor]
            [concretemfg.phase :as phase]
            [concretemfg.registry :as registry]
            [langgraph.graph :as g]))

;; ----------------------------- scenario -----------------------------

(def ^:private coordinator
  "The calling actor's own identity/role -- the same shape
  `concretemfg.sim`'s own demo coordinator uses."
  {:actor-id "coord-1" :actor-role :plant-coordinator :phase 3})

(def ^:private supervisor-id
  "The human plant supervisor who resumes an interrupted run. Supplied
  as a RUNTIME input (`{:approval {:by ..}}`), never seeded -- kept
  distinct from `coordinator`'s own `:actor-id` on purpose, so the page
  can tell apart 'the actor that asked' from 'the human that approved'."
  "supervisor-1")

(def ^:private shipping-approver-id "shipping-approver-1")

(defn- exec!
  "One coordination request through the real compiled actor."
  [actor tid request phase-n]
  {:thread tid
   :phase phase-n
   :request request
   :result (g/run* actor
                   {:request request :context (assoc coordinator :phase phase-n)}
                   {:thread-id tid})})

(defn- resume!
  "Resume an interrupted run with a human decision."
  [actor entry status by]
  (assoc entry
         :approval {:status status :by by}
         :resume (g/run* actor {:approval {:status status :by by}}
                         {:thread-id (:thread entry) :resume? true})))

(defn run-demo!
  "Drives a freshly seeded store through a scenario that reaches every
  disposition this actor can produce, using ONLY seeded entities.

  Clean lifecycle on `batch-001` (verified, registered, 50000.0 kg
  logged, 10000.0 kg already shipped):
    - a production-batch intake patch auto-commits (phase 3, the only
      op ever eligible to auto-commit, and only when governor-clean);
    - `mnt-1` schedules a mixer-drum inspection on `batcher-001`
      (verified + registered) -- `:schedule-maintenance` is never in
      any phase's `:auto` set, so it ALWAYS escalates; approved;
    - `concern-1` flags a cement/silica-dust + curing-steam concern on
      `batcher-001` -- always high-stakes, always escalates; approved;
    - `ship-1` ships 5000.0 kg (10000 -> 15000 of 50000); approved;
    - `ship-2` ships the remaining 35000.0 kg, filling the batch
      EXACTLY to its own recorded weight -- legal, and the case an
      earlier build read as over-capacity; approved;
    - `ship-3` then asks for 100.0 kg more and HARD-holds: the same
      batch that was shippable twice is now out of headroom. The
      governor recomputed this from the batch's own record each time.

  A human REJECTION: `concern-2` on `molder-002` escalates and the
  supervisor rejects it -- a hold that DID reach a human (contrast
  with every HARD hold below, which never can).

  Phase ladder: a `batch-002` intake at phase 2 is governor-clean but
  not auto-eligible there, so the phase gate escalates it
  (`:phase-approval`); a governor-clean `batch-002` shipment at phase 1
  is HELD outright because phase 1 cannot write that op at all
  (`:phase-disabled`).

  HARD holds, one request per rule, none of which reaches a human:
  request `:effect` not `:propose`; an unrecognized op (which trips the
  op allowlist AND the proposal-effect allowlist at once); maintenance
  against the UNVERIFIED/unregistered `molder-002`; a shipment against
  the UNVERIFIED/unregistered `batch-003`; a shipment that would exceed
  `batch-002`'s own logged weight; a shipment stating no amount at all
  (un-checkable headroom is not headroom); a maintenance proposal that
  tries to ACTUATE the mixing line (permanent, no override); a
  double-schedule of `mnt-1`; and three fabricated batch readings
  (product type, dimensional deviation, defect rate).

  Returns `{:db store :runs [..]}` -- `:runs` keeps the in-graph state
  so the page can show where an approver id does and does not survive."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)
        runs (atom [])
        add! (fn [entry] (swap! runs conj entry) entry)]

    ;; --- clean lifecycle -------------------------------------------------
    (add! (exec! actor "t01"
                 {:op :log-production-batch :effect :propose :subject "batch-001"
                  :patch {:product-type :precast-concrete-panel
                          :dimensional-deviation-percent 0.9
                          :defect-rate-percent 1.1
                          :last-assessed "2026-07-14"}}
                 3))

    (add! (resume! actor
                   (exec! actor "t02"
                          {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                           :value {:equipment-id "batcher-001"
                                   :maintenance-type :mixer-drum-inspection
                                   :scheduled-date "2026-08-01"
                                   :actuate-mixing-line? false}}
                          3)
                   :approved supervisor-id))

    (add! (resume! actor
                   (exec! actor "t03"
                          {:op :flag-safety-concern :effect :propose :subject "concern-1"
                           :value {:equipment-id "batcher-001" :severity :moderate
                                   :description "バッチングプラント周辺のセメント粉塵滞留と養生室の蒸気漏れの兆候"}}
                          3)
                   :approved supervisor-id))

    (add! (resume! actor
                   (exec! actor "t04"
                          {:op :coordinate-shipment :effect :propose :subject "ship-1"
                           :value {:batch-id "batch-001" :weight-kg 5000.0
                                   :destination "buyer-yard-north"}}
                          3)
                   :approved shipping-approver-id))

    (add! (resume! actor
                   (exec! actor "t05"
                          {:op :coordinate-shipment :effect :propose :subject "ship-2"
                           :value {:batch-id "batch-001" :weight-kg 35000.0
                                   :destination "buyer-yard-north"}}
                          3)
                   :approved shipping-approver-id))

    (add! (exec! actor "t06"
                 {:op :coordinate-shipment :effect :propose :subject "ship-3"
                  :value {:batch-id "batch-001" :weight-kg 100.0
                          :destination "buyer-yard-north"}}
                 3))

    ;; --- a hold that DID reach a human -----------------------------------
    (add! (resume! actor
                   (exec! actor "t07"
                          {:op :flag-safety-concern :effect :propose :subject "concern-2"
                           :value {:equipment-id "molder-002" :severity :minor
                                   :description "成形ステーションの脱型時粉塵についての追加情報待ち"}}
                          3)
                   :rejected supervisor-id))

    ;; --- phase ladder ----------------------------------------------------
    (add! (resume! actor
                   (exec! actor "t08"
                          {:op :log-production-batch :effect :propose :subject "batch-002"
                           :patch {:defect-rate-percent 0.6 :last-assessed "2026-07-20"}}
                          2)
                   :approved supervisor-id))

    (add! (exec! actor "t09"
                 {:op :coordinate-shipment :effect :propose :subject "ship-4"
                  :value {:batch-id "batch-002" :weight-kg 100.0
                          :destination "buyer-yard-east"}}
                 1))

    ;; --- HARD holds ------------------------------------------------------
    (add! (exec! actor "t10"
                 {:op :log-production-batch :effect :direct-write :subject "batch-001"
                  :patch {:product-type :precast-concrete-panel}}
                 3))

    (add! (exec! actor "t11"
                 {:op :actuate-mixing-line :effect :propose :subject "batcher-001"}
                 3))

    (add! (exec! actor "t12"
                 {:op :schedule-maintenance :effect :propose :subject "mnt-2"
                  :value {:equipment-id "molder-002" :maintenance-type :mold-inspection
                          :scheduled-date "2026-08-01" :actuate-mixing-line? false}}
                 3))

    (add! (exec! actor "t13"
                 {:op :coordinate-shipment :effect :propose :subject "ship-5"
                  :value {:batch-id "batch-003" :weight-kg 1000.0
                          :destination "buyer-yard-south"}}
                 3))

    (add! (exec! actor "t14"
                 {:op :coordinate-shipment :effect :propose :subject "ship-6"
                  :value {:batch-id "batch-002" :weight-kg 1000.0
                          :destination "buyer-yard-east"}}
                 3))

    (add! (exec! actor "t15"
                 {:op :coordinate-shipment :effect :propose :subject "ship-7"
                  :value {:batch-id "batch-002" :destination "buyer-yard-east"}}
                 3))

    (add! (exec! actor "t16"
                 {:op :schedule-maintenance :effect :propose :subject "mnt-3"
                  :value {:equipment-id "batcher-001" :maintenance-type :force-run
                          :scheduled-date "2026-09-01" :actuate-mixing-line? true}}
                 3))

    (add! (exec! actor "t17"
                 {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                  :value {:equipment-id "batcher-001"
                          :maintenance-type :mixer-drum-inspection
                          :scheduled-date "2026-08-01" :actuate-mixing-line? false}}
                 3))

    (add! (exec! actor "t18"
                 {:op :log-production-batch :effect :propose :subject "batch-001"
                  :patch {:product-type :unobtainium-slab}}
                 3))

    (add! (exec! actor "t19"
                 {:op :log-production-batch :effect :propose :subject "batch-001"
                  :patch {:dimensional-deviation-percent 999.0}}
                 3))

    (add! (exec! actor "t20"
                 {:op :log-production-batch :effect :propose :subject "batch-001"
                  :patch {:defect-rate-percent -4.0}}
                 3))

    {:db db :runs @runs}))

;; ----------------------------- derivation -----------------------------

(defn hard-holds
  "The HARD governor holds on the ledger -- the holds that never reach a
  human. A human-rejected escalation is written with `:t
  :approval-rejected` and is deliberately NOT counted here."
  [ledger]
  (filterv #(= :governor-hold (:t %)) ledger))

(defn- rejected-holds [ledger]
  (filterv #(= :approval-rejected (:t %)) ledger))

(defn- final-state
  "The state of a run entry after the human step, if there was one."
  [{:keys [result resume]}]
  (:state (or resume result)))

(defn- audit-of [entry] (vec (:audit (final-state entry))))

(defn- gate-reason
  "The phase gate's own reason for this run, if it recorded one."
  [entry]
  (let [a (audit-of entry)]
    (or (some :phase-reason (filter #(= :governor-hold (:t %)) a))
        (some :reason (filter #(= :approval-requested (:t %)) a)))))

(defn- run-basis
  "Rule names the governor cited for this run (empty when clean)."
  [entry]
  (->> (audit-of entry)
       (filter #(#{:governor-hold :approval-rejected} (:t %)))
       (mapcat :basis)
       (mapv str)))

(defn- hard-held?
  "Did this run end in a hold the human never saw? A run a human
  actually rejected ended in a hold too, but it is not that."
  [entry]
  (and (= :hold (:disposition (final-state entry)))
       (not= :rejected (:status (:approval entry)))))

(defn- hard-holds-referencing
  "Runs HARD-held whose request named `id` under `k` -- exact, taken
  from the request the actor was actually given, never string-matched
  out of a rendered message."
  [runs k id]
  (filterv #(and (hard-held? %) (= id (get-in % [:request :value k]))) runs))

(defn- violation-rows
  "One entry per (hold fact x violation) -- the rule-coverage source."
  [ledger]
  (vec (mapcat (fn [f]
                 (map #(assoc % :subject (:subject f) :op (:op f))
                      (:violations f)))
               (hard-holds ledger))))

(def ^:private approver-key?
  "Keys any part of this stack could plausibly use to retain the human
  who approved a run. The disclosure below is DERIVED by looking for
  these -- it is not a hard-coded claim about this repo."
  #{:approved-by :approver :approved-by-id :by :signed-by :approval-by})

(defn- approver-hits
  "Every place inside `x` where an approver-ish key carries a value."
  [x]
  (letfn [(walk [node path acc]
            (cond
              (map? node)
              (reduce-kv (fn [a k v]
                           (walk v (conj path k)
                                 (if (and (keyword? k) (approver-key? k) (some? v))
                                   (conj a {:path (conj path k) :value v})
                                   a)))
                         acc node)
              (sequential? node)
              (reduce (fn [a [i v]] (walk v (conj path i) a))
                      acc (map-indexed vector node))
              :else acc))]
    (vec (sort-by (comp pr-str :path) (walk x [] [])))))

(defn- committed-subjects [ledger op-kw]
  (->> ledger
       (filter #(and (= :committed (:t %)) (= op-kw (:op %))))
       (mapv :subject)))

(defn- approver-surfaces
  "Each surface a reader might expect the approver id on, with the
  approver keys actually found there. Derived at render time so this
  page self-corrects if the store starts retaining the id."
  [{:keys [db runs]}]
  (let [ledger (vec (store/ledger db))
        ships (mapv #(store/shipment db %) (committed-subjects ledger :coordinate-shipment))]
    [{:surface "store: batches" :persisted? true :data (store/all-batches db)}
     {:surface "store: equipment" :persisted? true :data (store/all-equipment db)}
     {:surface "store: maintenance (committed drafts)" :persisted? true :data (store/all-maintenance db)}
     {:surface "store: shipments (committed drafts)" :persisted? true :data ships}
     {:surface "store: safety-concerns" :persisted? true :data (store/safety-concerns db)}
     {:surface "store: generic records" :persisted? true :data (store/get-records db)}
     {:surface "store: maintenance-history (registry drafts)" :persisted? true :data (store/maintenance-history db)}
     {:surface "store: shipment-history (registry drafts)" :persisted? true :data (store/shipment-history db)}
     {:surface "store: audit ledger" :persisted? true :data ledger}
     {:surface "in-graph state :audit (NOT persisted)" :persisted? false
      :data (mapv audit-of runs)}
     {:surface "in-graph state :record :payload (NOT persisted)" :persisted? false
      :data (mapv #(:record (final-state %)) runs)}]))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- kw-name
  "Keywords print with their namespace AND their colon -- `:batch/upsert`
  is not `upsert`, and eliding either would misreport a closed
  allowlist."
  [v]
  (str v))

(defn- yn [b]
  (if b "<span class=\"ok\">yes</span>" "<span class=\"err\">no</span>"))

(defn- num-cell [v]
  (str "<span class=\"num\">" (esc (if (nil? v) "—" v)) "</span>"))

(defn- tr [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" (esc %) "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n"
       (str/join "\n" rows) "\n"
       "      </tbody>\n"
       "    </table>\n"))

(defn- section [title lede body]
  (str "  <section class=\"card\">\n"
       "    <h2>" (esc title) "</h2>\n"
       "    <p class=\"muted\">" lede "</p>\n"
       body
       "  </section>\n"))

(defn- sorted-names [coll] (sort (map kw-name coll)))

(defn- joined-codes [coll]
  (str/join " " (map code (sorted-names coll))))

;; ----------------------------- sections -----------------------------

(defn- disposition-cell [entry]
  (let [d (:disposition (final-state entry))
        rejected? (= :rejected (:status (:approval entry)))]
    (cond
      (= :commit d) (if (:approval entry)
                      "<span class=\"ok\">approved &amp; committed</span>"
                      "<span class=\"ok\">auto-committed</span>")
      rejected? "<span class=\"warn\">rejected by human &middot; held</span>"
      (= :hold d) "<span class=\"critical\">HARD hold &middot; never reaches a human</span>"
      (= :escalate d) "<span class=\"warn\">awaiting approval</span>"
      :else (str "<span class=\"muted\">" (esc (kw-name d)) "</span>"))))

(defn- governor-cell [entry]
  (let [v (:verdict (:state (:result entry)))]
    (cond
      (nil? v) "<span class=\"muted\">—</span>"
      (:hard? v) (str "<span class=\"critical\">HARD &times;"
                      (count (:violations v)) "</span>")
      (:high-stakes? v) "<span class=\"warn\">high-stakes &rarr; human</span>"
      (:escalate? v) "<span class=\"warn\">below confidence floor &rarr; human</span>"
      :else "<span class=\"ok\">clean</span>")))

(defn- run-row [entry]
  (let [req (:request entry)
        v (:verdict (:state (:result entry)))
        basis (run-basis entry)]
    (tr (code (:thread entry))
        (num-cell (:phase entry))
        (code (:op req))
        (esc (:subject req))
        (code (:effect req))
        (num-cell (:confidence v))
        (governor-cell entry)
        (if-let [r (gate-reason entry)] (code r) "<span class=\"muted\">—</span>")
        (disposition-cell entry)
        (if (seq basis)
          (str/join " " (map code basis))
          "<span class=\"muted\">—</span>"))))

(defn- held-cell
  "The HARD holds this run produced against one entity, listed by the
  subject of the request that was held."
  [held]
  (if (seq held)
    (str "<span class=\"critical\">" (count held) "</span> "
         (str/join " " (map #(code (get-in % [:request :subject])) held)))
    "<span class=\"muted\">0</span>"))

(defn- batch-row [runs {:keys [id product-type material weight-kg shipped-weight-kg
                               dimensional-deviation-percent defect-rate-percent
                               verified? registered? last-assessed]}]
  (let [headroom (when (and (number? weight-kg) (number? shipped-weight-kg))
                   (- (double weight-kg) (double shipped-weight-kg)))]
    (tr (code id)
        (code product-type)
        (esc material)
        (num-cell weight-kg)
        (num-cell shipped-weight-kg)
        (str (num-cell headroom)
             (when (and headroom (zero? headroom))
               " <span class=\"warn\">full</span>"))
        (num-cell dimensional-deviation-percent)
        (num-cell defect-rate-percent)
        (yn verified?)
        (yn registered?)
        (esc last-assessed)
        (held-cell (hard-holds-referencing runs :batch-id id)))))

(defn- equipment-row [runs {:keys [id kind verified? registered? last-maintenance-date
                                   last-scheduled-maintenance-date]}]
  (tr (code id)
      (code kind)
      (yn verified?)
      (yn registered?)
      (esc (or last-maintenance-date "—"))
      (esc (or last-scheduled-maintenance-date "—"))
      (held-cell (hard-holds-referencing runs :equipment-id id))))

(defn- maintenance-row [{:keys [id equipment-id maintenance-type scheduled-date
                                actuate-mixing-line? scheduled? maintenance-number]}]
  (tr (code id)
      (code equipment-id)
      (code maintenance-type)
      (esc scheduled-date)
      (yn (true? actuate-mixing-line?))
      (yn (true? scheduled?))
      (code maintenance-number)))

(defn- shipment-row [{:keys [id batch-id weight-kg destination shipment-number]}]
  (tr (code id)
      (code batch-id)
      (num-cell weight-kg)
      (esc destination)
      (code shipment-number)))

(defn- concern-row [{:keys [id equipment-id severity description]}]
  (tr (code id)
      (code equipment-id)
      (code severity)
      (esc description)))

(defn- phase-row [[n {:keys [label writes auto]}]]
  (tr (num-cell n)
      (str (esc label)
           (when (= n phase/default-phase)
             " <span class=\"badge\">default</span>"))
      (if (seq writes) (joined-codes writes) "<span class=\"muted\">(none)</span>")
      (if (seq auto) (joined-codes auto) "<span class=\"muted\">(none — every write needs a human)</span>")))

(defn- rule-row [[rule vs]]
  (tr (code rule)
      (str "<span class=\"critical\">" (count vs) "</span>")
      (str/join " " (map code (sort (distinct (map :subject vs)))))
      (esc (:detail (first vs)))))

(defn- hold-row [{:keys [op subject basis violations confidence phase-reason]}]
  (tr (code op)
      (esc subject)
      (num-cell confidence)
      (if (seq basis)
        (str/join " " (map code (map kw-name basis)))
        "<span class=\"muted\">— (phase gate, no governor rule)</span>")
      (if-let [d (seq (map :detail violations))]
        (str/join "<br>" (map esc d))
        (str "<span class=\"warn\">" (esc (kw-name (or phase-reason :n-a))) "</span>"))))

(defn- ledger-row [{:keys [t op subject disposition basis actor summary]}]
  (tr (code t)
      (code (or op :n-a))
      (esc subject)
      (code (or actor "—"))
      (code (or disposition "—"))
      (if (seq basis)
        (str/join " " (map code (map kw-name basis)))
        "<span class=\"muted\">—</span>")
      (esc (or summary "—"))))

(def ^:private approver-hit-sample
  "How many approver-key hits a surface lists inline before the cell is
  truncated. Truncation is ANNOUNCED (below) rather than silent -- a
  cell that shows 4 of 5 without saying so reads as though 4 is the
  whole answer, which is the same class of mistake as omitting the
  approver disclosure entirely."
  4)

(defn- approver-surface-row [{:keys [surface persisted? data]}]
  (let [hits (approver-hits data)
        shown (take approver-hit-sample hits)
        hidden (- (count hits) (count shown))]
    (tr (esc surface)
        (if persisted?
          "<span class=\"ok\">persisted</span>"
          "<span class=\"muted\">in-memory only</span>")
        (if (seq hits)
          (str "<span class=\"ok\">" (count hits) "</span>")
          "<span class=\"err\">0</span>")
        (if (seq hits)
          (str (str/join "<br>" (map #(str (code (pr-str (:path %))) " = " (code (:value %)))
                                     shown))
               (when (pos? hidden)
                 (str "<br><span class=\"muted\">… 他 " (esc hidden) " 箇所（省略）</span>")))
          "<span class=\"muted\">no approver key anywhere in this structure</span>"))))

;; ----------------------------- document -----------------------------

(defn render
  "Pure: `{:db .. :runs ..}` from `run-demo!` -> the console document."
  [{:keys [db runs] :as demo}]
  (let [ledger (vec (store/ledger db))
        holds (hard-holds ledger)
        rejected (rejected-holds ledger)
        commits (filterv #(= :committed (:t %)) ledger)
        by-rule (sort-by key (group-by :rule (violation-rows ledger)))
        ships (mapv #(store/shipment db %) (committed-subjects ledger :coordinate-shipment))
        surfaces (approver-surfaces demo)
        persisted-hits (mapcat #(approver-hits (:data %))
                               (filter :persisted? surfaces))
        transient-hits (mapcat #(approver-hits (:data %))
                               (remove :persisted? surfaces))]
    (str
     "<!doctype html>\n<html lang=\"ja\"><head><meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
     "<title>cloud-itonami-isic-2395 · concrete/cement/plaster plant operations · Operator Console</title>\n"
     "<style>\n" (jp-go-dds.skin/dds+skin) "\n</style></head><body>\n"

     "<header class=\"bar\">\n"
     "  <h1>コンクリート・セメント・石膏製品工場 プラント運用コーディネーター (ISIC 2395) — Operator Console</h1>\n"
     "</header>\n"
     "<p class=\"subtitle\">\n"
     "  <span class=\"badge\">read-only sample</span>\n"
     "  <span class=\"badge\">governor-gated</span>\n"
     "  <span class=\"badge\">propose-only · no mixing/molding-line actuation</span>\n"
     "</p>\n"
     "<main>\n"

     "  <section class=\"banner\">\n"
     "    <p>このページは手書きではない。<code>clojure -M:dev:render-html</code> が実際の actor スタック\n"
     "    (<code>concretemfg.operation</code> の langgraph StateGraph → <code>concretemfg.advisor</code>\n"
     "    → <code>concretemfg.governor</code> → <code>concretemfg.phase</code> → <code>concretemfg.store</code>)\n"
     "    をビルド時に走らせ、その出力だけを描画している。登場する ID・重量・判定・違反理由は\n"
     "    すべて <code>concretemfg.store/sample-data!</code> の seed とその実行結果に由来する。</p>\n"
     "    <table>\n"
     "      <thead><tr><th>要求</th><th>台帳 fact</th><th>commit</th><th>HARD hold</th><th>人が却下</th><th>発火した rule 種別</th></tr></thead>\n"
     "      <tbody>\n"
     (tr (num-cell (count runs)) (num-cell (count ledger)) (num-cell (count commits))
         (str "<span class=\"critical\">" (count holds) "</span>")
         (num-cell (count rejected))
         (num-cell (count by-rule)))
     "\n      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     (section "実行マトリクス (この build が走らせた全要求)"
              (str "1 行 = 1 graph run。<code>governor</code> 列は "
                   "<code>concretemfg.governor/check</code> の verdict、<code>gate</code> 列は "
                   "<code>concretemfg.phase/gate</code> が返した reason。HARD hold は "
                   "<code>interrupt-before</code> の手前で止まるので、人間の承認画面には一度も出ない。")
              (table ["thread" "phase" "op" "subject" "request :effect"
                      "advisor confidence" "governor" "phase gate" "disposition" "basis"]
                     (map run-row runs)))

     (section "生産バッチ (SSoT / seed + この実行のコミット)"
              (str "<code>concretemfg.store/all-batches</code> の現在値。"
                   "<code>headroom</code> は <code>:weight-kg - :shipped-weight-kg</code> — "
                   "governor は出荷提案のたびにこれを提案の自己申告ではなくバッチ自身の記録から再計算する "
                   "(<code>concretemfg.registry/shipment-weight-exceeded?</code>)。")
              (table ["batch" "product-type" "material" "weight-kg" "shipped-kg" "headroom"
                      "寸法偏差%" "不良率%" "verified?" "registered?" "last-assessed"
                      "このバッチを名指しした HARD hold"]
                     (map (partial batch-row runs) (store/all-batches db))))

     (section "設備 (SSoT / seed + この実行のコミット)"
              (str "<code>concretemfg.store/all-equipment</code> の現在値。"
                   "<code>verified?</code> と <code>registered?</code> の両方が true でなければ "
                   "保守作業予定は一切スケジュールできない (<code>registry/equipment-ready?</code>、"
                   "governor が advisor の申告とは独立に再検証する)。")
              (table ["equipment" "kind" "verified?" "registered?"
                      "last-maintenance-date" "last-scheduled-maintenance-date"
                      "この設備を名指しした HARD hold"]
                     (map (partial equipment-row runs) (store/all-equipment db))))

     (section "保守作業予定ドラフト (コミット済み)"
              (str "コミットされたのは DRAFT であって実行ではない。"
                   "<code>actuate?</code> が true の提案は "
                   "<code>mixing-line-actuate-blocked</code> で恒久的に HARD hold され、"
                   "ここには決して現れない。<code>maintenance-number</code> は "
                   "<code>concretemfg.registry/register-maintenance</code> が採番した実値。")
              (table ["maintenance" "equipment" "type" "scheduled-date" "actuate?" "scheduled?" "record"]
                     (map maintenance-row (store/all-maintenance db))))

     (section "出荷調整ドラフト (コミット済み)"
              (str "実際の配車は行わない — プラント運用側が保持する RECORD を作るだけ。"
                   "<code>shipment-number</code> は <code>registry/register-shipment</code> の採番。"
                   "<code>ship-2</code> はバッチをちょうど記録重量ぴったりまで満たす — これは合法。")
              (table ["shipment" "batch" "weight-kg" "destination" "record"]
                     (map shipment-row ships)))

     (section "安全懸念 (追記のみ)"
              (str "<code>:flag-safety-concern</code> は <code>:coordination/safety-concern</code> "
                   "stake が常に立つため、confidence がどれだけ高くても必ず人間に上がる "
                   "(<code>governor/high-stakes</code>)。どの phase の <code>:auto</code> 集合にも入らない。"
                   "未検証設備についても報告を止めない設計。")
              (table ["concern" "equipment" "severity" "description"]
                     (map concern-row (store/safety-concerns db))))

     (section "段階的ロールアウト (phase 0→3)"
              (str "この表は <code>concretemfg.phase/phases</code> をそのまま読んで描画している。"
                   "<code>:schedule-maintenance</code> はどの phase の <code>auto</code> 列にも無い — "
                   "ロールアウトの未達ではなく恒久的な構造。既定 phase は "
                   (code phase/default-phase) "。")
              (table ["phase" "label" "writes" "auto-commit"]
                     (map phase-row (sort-by key phase/phases))))

     (section "閉じた許可リストと閾値 (コードから読み出した実値)"
              (str "この節は runtime telemetry ではなく、この actor の権限境界そのもの。"
                   "値は <code>concretemfg.governor</code> / <code>concretemfg.registry</code> の "
                   "var を build 時に読んで並べている。")
              (table ["境界" "値"]
                     [(tr (code "governor/allowed-ops") (joined-codes governor/allowed-ops))
                      (tr (code "governor/allowed-proposal-effects") (joined-codes governor/allowed-proposal-effects))
                      (tr (code "governor/high-stakes") (joined-codes governor/high-stakes))
                      (tr (code "governor/confidence-floor") (num-cell governor/confidence-floor))
                      (tr (code "phase/write-ops") (joined-codes phase/write-ops))
                      (tr (code "registry/valid-product-types") (joined-codes registry/valid-product-types))
                      (tr (code "registry/dimensional-deviation-min/max-percent")
                          (str (num-cell registry/dimensional-deviation-min-percent) " … "
                               (num-cell registry/dimensional-deviation-max-percent)))
                      (tr (code "registry/defect-rate-min/max-percent")
                          (str (num-cell registry/defect-rate-min-percent) " … "
                               (num-cell registry/defect-rate-max-percent)))]))

     (section "governor rule カバレッジ (この実行で実際に発火したもの)"
              (str "台帳上の <code>:governor-hold</code> fact の <code>:violations</code> を "
                   "rule ごとに集計したもの。「この rule はある」という主張ではなく、"
                   "「この build でこの rule が実際にこの subject を止めた」という記録。")
              (table ["rule" "発火回数" "subject" "governor が書いた理由 (1 件目)"]
                     (map rule-row by-rule)))

     (section "HARD hold の明細 — どれも人間に届かない"
              (str "<code>concretemfg.operation</code> の <code>:decide</code> ノードは、"
                   "governor が HARD 違反を返した時点で <code>:hold</code> へ分岐する。"
                   "<code>:request-approval</code>(= <code>interrupt-before</code> で人間に渡す唯一のノード) "
                   "には到達しない。phase gate 由来の hold は governor rule を持たないが、"
                   "同じく override できない。")
              (table ["op" "subject" "confidence" "basis" "理由"]
                     (map hold-row holds)))

     (section "監査台帳 (この実行の全 fact / 追記のみ)"
              (str "<code>concretemfg.store/ledger</code> の全内容。"
                   "SSoT に書き込むノードは <code>:commit</code> ただ 1 つで、"
                   "<code>:hold</code> は台帳にだけ書いて SSoT を一切変えない。")
              (table ["fact" "op" "subject" "actor" "disposition" "basis" "summary"]
                     (map ledger-row ledger)))

     (section "承認者 ID はどこまで残るか (build 時に実測)"
              (str "この節は「壊れている」と決め打ちで書いたものではなく、"
                   "実行後のストアと台帳を実際に走査して "
                   (code (str/join " " (sorted-names approver-key?)))
                   " というキーを探した結果を描画している。"
                   "この実行で人間が渡した承認者 ID は "
                   (code supervisor-id) " と " (code shipping-approver-id)
                   " (呼び出し側 actor の <code>:actor-id</code> である " (code (:actor-id coordinator))
                   " とは意図的に別文字列にしてある)。")
              (str
               (table ["surface" "永続性" "approver key の数" "見つかった場所"]
                      (map approver-surface-row surfaces))
               "    <p>"
               (if (seq persisted-hits)
                 (str "<span class=\"ok\">承認者 ID は永続面に残っている</span>（"
                      (esc (count persisted-hits)) " 箇所）。")
                 (str "<span class=\"critical\">承認者 ID は、この actor の永続面のどこにも残っていない。</span> "
                      "<code>concretemfg.operation</code> の <code>:request-approval</code> ノードは "
                      "<code>:payload</code> にだけ <code>:approved-by</code> を載せるが、"
                      "<code>concretemfg.store/commit-record!</code> が読むのは <code>:value</code> のみ。"
                      "<code>{:t :approval-granted :by ..}</code> という audit fact も "
                      "graph の <code>:audit</code> チャネルに留まり、台帳には追記されない"
                      "（台帳に書くのは <code>:commit</code> ノードの <code>:committed</code> fact と "
                      "<code>:hold</code> ノードの hold fact だけ）。"
                      "実行中のメモリ上には " (esc (count transient-hits))
                      " 箇所現れるが、プロセスが終われば消える。"))
               "</p>\n"
               "    <p class=\"muted\">読み手が「誰も承認していない」と「ストアが承認者を保持していない」を"
               "取り違えないよう、黙って省略せずここに書いている。上の実行マトリクスの通り、"
               "承認は実際に行われている（<code>disposition</code> が "
               "<code>approved &amp; committed</code> の行）。"
               "この build はデモ生成であって actor の SSoT 意味論の変更ではないので、"
               "<code>commit-record!</code> / <code>phase-gate</code> の挙動はここでは直していない。</p>\n"))

     "</main>\n"
     "<footer>\n"
     "  <p>Generated at build time by <code>concretemfg.render-html</code> "
     "(<code>clojure -M:dev:render-html</code>) from "
     "<code>concretemfg.store/sample-data!</code>. Deterministic: no timestamps, no clock, no randomness — "
     "two runs are byte-identical. Regenerate after changing the actor, the governor or the seed.</p>\n"
     "  <p>cloud-itonami-isic-2395 · AGPL-3.0-or-later · この actor は提案のみを行い、"
     "ミキサー・バッチングプラント・成形ラインの直接操作も実際の配車も一切行わない。</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db] :as demo} (run-demo!)
        hs (hard-holds (store/ledger db))]
    (when (empty? hs)
      (throw (ex-info "no governor hold fact on the ledger — refusing to write a console that shows no real hold"
                      {:ledger-facts (count (store/ledger db))})))
    (let [html (render demo)]
      (spit out html)
      (println "wrote" out
               "(" (count (store/ledger db)) "ledger facts,"
               (count hs) "HARD holds,"
               (count (distinct (mapcat :basis hs))) "distinct rules,"
               (count (store/maintenance-history db)) "maintenance drafts,"
               (count (store/shipment-history db)) "shipment drafts )"))))
