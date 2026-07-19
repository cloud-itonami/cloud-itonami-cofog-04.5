(ns infrainsp.store
  "SSoT for the road/bridge-infrastructure-inspection back-office
  coordination actor, behind a `Store` protocol so the backend is a
  swap, not a rewrite -- the same seam every `cloud-itonami-*` actor in
  this fleet uses.

  Scope note: like its siblings (`cloud-itonami-isic-3091`'s own
  `motomfg.store`, `cloud-itonami-cofog-03.2`'s own `firerisk.store`),
  this build ships a single `MemStore` backend only (atom of EDN) --
  the deterministic default for dev/tests/demo, no deps.

  Three kinds of entity live here:
    - `structures`         -- an inspectable structure's own record
                              (a pavement segment or a bridge deck/
                              joint). `:verified?`/`:registered?` track
                              whether it has actually been inspected/
                              commissioned and is on file;
                              `:rating-scale` (`:nbi-0-9`/`:pci-0-100`)
                              declares which real published
                              condition-rating convention its readings
                              are reported on (see
                              `infrainsp.registry` ns docstring);
                              `:condition-threshold` is the operator's
                              own registered maintenance-trigger point
                              a routine finding is judged against;
                              `:last-finding`
                              (`:acceptable`/`:deficient`/nil) is the
                              structure's own ground-truth cumulative
                              finding state.
    - `reinspections`      -- a scheduled follow-up-survey-window DRAFT
                              against a structure (`infrainsp.registry`'s
                              `register-reinspection`). Dedicated
                              `:scheduled?` double-schedule guard (never
                              a `:status` value -- the same discipline
                              every prior governor's guards establish).
    - `inspection-records` -- a logged completed-inspection record
                              (survey-type/notes), keyed by id.

  Plus a generic `records` map (id -> raw record) used only for
  direct, domain-agnostic `commit-record!` calls (a record with no
  `:effect` key) -- the store-level primitive every sibling actor's
  own MemStore exposes underneath its domain-specific commit dispatch.

  The ledger stays append-only: 'which inspection was logged, which
  condition finding was decided against a verified/registered structure
  and at what independently-recomputed condition-rating verdict
  (flagged critical or not against the real external scale's own
  boundary), which reinspection was scheduled against a structure with
  an on-file deficient finding, which structural concern was
  escalated' is always a query over an immutable log -- the audit
  trail a public-works authority or downstream taxpayer trusting this
  coordinator needs."
  (:require [infrainsp.registry :as registry]))

(defprotocol Store
  (structure [s id])
  (all-structures [s])
  (inspection-record [s id])
  (condition-finding [s structure-id] "the structure's own latest committed condition-finding record, or nil")
  (reinspection [s id])
  (structural-concerns [s] "the append-only structural-concern log")
  (ledger [s])
  (finding-history [s] "the append-only condition-finding history (infrainsp.registry drafts)")
  (reinspection-history [s] "the append-only reinspection-schedule history (infrainsp.registry drafts)")
  (next-finding-sequence [s] "next finding-number sequence")
  (next-reinspection-sequence [s] "next reinspection-number sequence")
  (reinspection-already-scheduled? [s reinspection-id] "has this reinspection window already been scheduled?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (get-records [s] "the generic id -> raw-record map (domain-agnostic commit-record! path)")
  (with-structures [s structures] "replace/seed the structure directory (map id->structure)"))

;; ----------------------------- demo/sample data -----------------------------

(defn- sample-structures []
  {"bridge-001" {:id "bridge-001" :kind :bridge-deck
                 :verified? true :registered? true
                 :rating-scale :nbi-0-9
                 :condition-threshold 5.0
                 :last-finding nil}
   "pavement-002" {:id "pavement-002" :kind :pavement
                   :verified? true :registered? true
                   :rating-scale :pci-0-100
                   :condition-threshold 55.0
                   :last-finding :deficient}
   "bridge-003" {:id "bridge-003" :kind :bridge-joint
                 :verified? false :registered? false
                 :rating-scale :nbi-0-9
                 :condition-threshold 5.0
                 :last-finding nil}})

;; ----------------------------- shared commit logic -----------------------------

(defn- decide-condition-finding!
  "Backend-agnostic `:condition-finding/decide` -- drafts the
  condition-finding record via `infrainsp.registry` and returns
  {:result .. :patch ..} for the caller to persist."
  [s finding-id structure-id finding]
  (let [seq-n (next-finding-sequence s)
        result (registry/register-condition-finding finding-id structure-id finding seq-n)]
    {:result result
     :patch {:finding finding
             :finding-number (get result "finding_number")}}))

(defn- schedule-reinspection!
  "Backend-agnostic `:reinspection/schedule` -- drafts the
  reinspection-schedule record via `infrainsp.registry` and returns
  {:result .. :patch ..} for the caller to persist."
  [s reinspection-id structure-id]
  (let [seq-n (next-reinspection-sequence s)
        result (registry/register-reinspection reinspection-id structure-id seq-n)]
    {:result result
     :patch {:scheduled? true
             :reinspection-number (get result "reinspection_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (structure [_ id] (get-in @a [:structures id]))
  (all-structures [_] (sort-by :id (vals (:structures @a))))
  (inspection-record [_ id] (get-in @a [:inspection-records id]))
  (condition-finding [_ structure-id] (get-in @a [:finding-by-structure structure-id]))
  (reinspection [_ id] (get-in @a [:reinspections id]))
  (structural-concerns [_] (:structural-concerns @a))
  (ledger [_] (:ledger @a))
  (finding-history [_] (:finding-history @a))
  (reinspection-history [_] (:reinspection-history @a))
  (next-finding-sequence [_] (:finding-sequence @a 0))
  (next-reinspection-sequence [_] (:reinspection-sequence @a 0))
  (reinspection-already-scheduled? [_ reinspection-id]
    (boolean (get-in @a [:reinspections reinspection-id :scheduled?])))
  (get-records [_] (:records @a))
  (commit-record! [s {:keys [effect path value] :as record}]
    (cond
      (= effect :inspection-record/upsert)
      (swap! a update-in [:inspection-records (first path)] merge (assoc value :id (first path)))

      (= effect :condition-finding/decide)
      (let [finding-id (first path)
            structure-id (:structure-id value)
            finding (:finding value)
            {:keys [result patch]} (decide-condition-finding! s finding-id structure-id finding)]
        (swap! a (fn [state]
                   (-> state
                       (update :finding-sequence (fnil inc 0))
                       (update :finding-history registry/append result)
                       (assoc-in [:finding-by-structure structure-id] (merge value patch))
                       (assoc-in [:structures structure-id :last-finding] finding))))
        result)

      (= effect :concern/escalate)
      (let [concern-id (first path)
            concern (assoc value :id concern-id)]
        (swap! a update :structural-concerns conj concern)
        concern)

      (= effect :reinspection/schedule)
      (let [reinspection-id (first path)
            structure-id (:structure-id value)
            {:keys [result patch]} (schedule-reinspection! s reinspection-id structure-id)]
        (swap! a (fn [state]
                   (-> state
                       (update :reinspection-sequence (fnil inc 0))
                       (update-in [:reinspections reinspection-id] merge (assoc value :id reinspection-id) patch)
                       (update :reinspection-history registry/append result))))
        result)

      ;; Domain-agnostic path: a raw record with an :id and no :effect
      ;; is written verbatim into the generic `records` map -- the
      ;; store-level primitive underneath the domain-specific dispatch
      ;; above.
      (and (nil? effect) (:id record))
      (swap! a assoc-in [:records (:id record)] record)

      :else nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-structures [s structures] (when (seq structures) (swap! a assoc :structures structures)) s))

(defn mem-store
  "A fresh, empty MemStore."
  []
  (->MemStore (atom {:structures {} :inspection-records {} :reinspections {}
                      :records {} :structural-concerns []
                      :finding-by-structure {}
                      :ledger [] :finding-sequence 0 :finding-history []
                      :reinspection-sequence 0 :reinspection-history []})))

(defn sample-data!
  "Seeds `s` (a MemStore) with a small, self-contained structure set --
  one verified+registered NBI-scale bridge deck never yet scanned
  (clean structural-scan happy path, condition-rating above its own
  threshold), one verified+registered PCI-scale pavement segment with
  an on-file DEFICIENT finding (schedule-reinspection happy path), one
  UNVERIFIED/unregistered NBI-scale bridge joint (blocks any scan or
  reinspection scheduling proposed against it) -- so the actor + demo
  + tests run offline. Returns `s` (thread-friendly with `->`)."
  [s]
  (with-structures s (sample-structures))
  s)

;; ----------------------------- back-compat aliases -----------------------------
;; `get-ledger` mirrors `ledger` under the name several sibling actors'
;; own demo/test harnesses already call.

(defn get-ledger [s] (ledger s))
