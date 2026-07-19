(ns infrainsp.phase
  "Phase 0->3 staged rollout for the road/bridge-infrastructure-
  inspection back-office coordination actor.

    Phase 0  read-only          -- no writes, still governor-gated.
    Phase 1  assisted-intake    -- inspection logging allowed, every
                                    write needs human approval.
    Phase 2  assisted-report    -- adds structural-concern escalation,
                                    still approval.
    Phase 3  supervised-auto    -- adds condition-finding decisions and
                                    reinspection scheduling (still
                                    always approval -- see below);
                                    governor-clean, high-confidence
                                    `:log-inspection` (no physical/
                                    financial risk) may auto-commit.

  `:structural-scan` and `:schedule-reinspection` are deliberately
  ABSENT from every phase's `:auto` set, including phase 3 -- a
  permanent structural fact, not a rollout milestone still to come. A
  condition finding is a real liability-bearing claim (per README, a
  structural defect can require immediate closure), and scheduling a
  reinspection means the robot actually surveys the structure again;
  both are always a human public-works authority's call.
  `infrainsp.governor`'s `equipment-actuate-blocked-violations`
  HARD-blocks actuate attempts unconditionally, and the confidence/
  high-stakes/critical-reading gate independently never lets
  `:escalate-structural-concern` auto-commit either -- multiple
  independent layers agree on where this actor's authority ends. Like
  every prior sibling's phase-3 `:auto` set, this domain has only ONE
  member (`:log-inspection`) -- no separate no-risk lifecycle distinct
  from ordinary record logging.")

(def write-ops
  #{:log-inspection :structural-scan
    :escalate-structural-concern :schedule-reinspection})

;; NOTE the invariant: `:structural-scan` and `:schedule-reinspection`
;; are members of `write-ops` (governor-gated like any write) but are
;; NEVER members of any phase's `:auto` set below. Do not add them
;; there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed
  to auto-commit when governor-clean>}."
  {0 {:label "read-only"        :writes #{}                                                    :auto #{}}
   1 {:label "assisted-intake"  :writes #{:log-inspection}                                      :auto #{}}
   2 {:label "assisted-report"  :writes #{:log-inspection :escalate-structural-concern}          :auto #{}}
   3 {:label "supervised-auto"  :writes write-ops
      :auto #{:log-inspection}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:structural-scan`/`:schedule-reinspection` are never auto-eligible
    at any phase, so they always escalate once the governor clears
    them (or hold if the governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map an Infrastructure Inspection Governor verdict to a base
  disposition before the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
