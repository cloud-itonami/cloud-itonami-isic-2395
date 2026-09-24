(ns kotoba.edn.matching-close
  "matching-close -- addressed on its own.

  Split out of kotoba.lang.edn on 2026-09-09 (ADR-2609091200). The unit
  here is the DEFINITION, and this repo's deps.edn names exactly the
  definitions it reaches -- nothing else.
"
  (:export [matching-close]))

(defn matching-close [ch]
  (case ch \( \) \[ \] \{ \}))
