(ns infrainsp.governor
  "Infrastructure Inspection Governor -- the independent compliance
  layer that earns the Inspection Advisor the right to commit. The
  advisor has no notion of whether a structure it wants to scan or
  schedule a reinspection against has actually been inspected/
  registered, whether its own claimed acceptable/deficient finding
  actually matches what the raw condition-rating sensor reading and
  the structure's own registered threshold say, whether a reading
  crosses the REAL, externally-published critical boundary (FHWA/AASHTO
  'Imminent Failure' or ASTM D6433 'Failed') a local threshold cannot
  loosen, whether a proposal secretly tries to ACTUATE the inspection
  robot beyond passive sensing, whether a proposal secretly tries to
  self-report that a real closure was ordered (an authority this actor
  never holds), or whether a reinspection is being scheduled (and
  billed) against a structure with no on-file deficient finding to
  justify it -- so this MUST be a separate system able to *reject* a
  proposal and fall back to HOLD.

  `:itonami.blueprint/governor` is `:infrastructure-inspection-governor`
  (see blueprint.edn).

  `:escalate-structural-concern` -- a structural defect requiring
  immediate closure -- is the one always-escalate op this governor
  treats as high-stakes regardless of confidence; the README names
  this exact scenario. This governor does not itself order a closure
  (see `infrainsp.registry`'s ns docstring); it only ensures a human
  public-works authority, never an LLM confidence score, is the one
  who acts on it.

  Checks below, ALL HARD violations except the confidence/high-stakes
  gate (SOFT -- asks a human to look, and the human may approve):

    1. Request-level propose-only  -- did the CALLER's own request
                                       actually declare `:effect
                                       :propose`? Any other value is a
                                       mis-wired/compromised caller
                                       trying to bypass proposal-only
                                       mode -- HARD, unconditional,
                                       evaluated BEFORE anything else.
    2. Closed op allowlist         -- is `:op` one of the four ops this
                                       actor is authorized to
                                       coordinate? Anything else -- HARD
                                       hold.
    3. Closed effect allowlist     -- is the PROPOSAL's own `:effect`
                                       (what would actually commit) one
                                       of the four propose-shaped
                                       effects? A proposal effect
                                       outside this set (e.g. a
                                       hallucinated `:gate/actuate` or
                                       `:lane/close`) is the 'direct
                                       hardware/closure control' scope
                                       violation this actor must NEVER
                                       perform -- HARD, PERMANENT,
                                       unconditional.
    4. Equipment-actuate blocked   -- does any proposal's own `:value`
                                       declare `:actuate-equipment?
                                       true`? Directly actuating the
                                       inspection robot beyond passive
                                       sensing is this actor's permanent
                                       scope boundary (see README
                                       `Robotics premise`) -- HARD,
                                       PERMANENT, unconditional. No
                                       phase and no human approval can
                                       ever override this (see
                                       `infrainsp.phase`: no op is ever
                                       a member of any phase's `:auto`
                                       set for this reason either --
                                       two independent layers agree).
    5. Closure authority blocked   -- ANY proposal (any op) whose own
                                       `:value`/`:patch` declares
                                       `:closed? true` OUTSIDE the
                                       gated `:escalate-structural-
                                       concern` path is attempting to
                                       self-report that a real closure
                                       was ordered through a side
                                       channel (e.g. slipped into a
                                       routine `:log-inspection`
                                       patch) -- an authority this
                                       actor never holds regardless of
                                       which op carries the claim --
                                       HARD, PERMANENT, unconditional.
    6. Structure not verified/
       registered                  -- for `:structural-scan` and
                                       `:schedule-reinspection`,
                                       INDEPENDENTLY verify the
                                       referenced structure's own
                                       `:verified?` AND `:registered?`
                                       are both true
                                       (`infrainsp.registry/structure-
                                       ready?`) -- never trust the
                                       advisor's own rationale about
                                       verification/registration
                                       status.
    7. Finding mismatch            -- for `:structural-scan`,
                                       INDEPENDENTLY recompute the
                                       acceptable/deficient finding
                                       from the proposal's own
                                       `:condition-rating` reading
                                       against the structure's own
                                       registered `:condition-
                                       threshold` (`infrainsp.registry/
                                       condition-finding`) and compare
                                       it against the proposal's own
                                       claimed `:finding` -- a mismatch
                                       (e.g. an advisor rubber-stamping
                                       `:acceptable` when the sensor
                                       reading itself would be
                                       deficient) is HARD-held: never
                                       let a self-reported finding
                                       stand against contradicting
                                       sensor evidence.
    8. Invalid condition rating    -- for `:structural-scan`, if
                                       `:condition-rating` is not a
                                       physically plausible reading for
                                       its own declared `:rating-scale`
                                       (`infrainsp.registry/condition-
                                       rating-valid?`), the proposal is
                                       rejected rather than let
                                       fabricated/sensor-error data
                                       drive a finding.
    9. Invalid rating scale        -- for `:structural-scan`, if the
                                       structure's own declared
                                       `:rating-scale` is not one of
                                       the closed known conventions
                                       (`infrainsp.registry/rating-
                                       scale-valid?`), rejected -- an
                                       unrecognized scale cannot ground
                                       an arithmetic finding.
   10. No deficient finding on
       file                        -- for `:schedule-reinspection`,
                                       INDEPENDENTLY verify the
                                       structure's own `:last-finding`
                                       on file is `:deficient` -- never
                                       trust the advisor's own claim
                                       that a reinspection is
                                       warranted. Scheduling (and
                                       billing) a reinspection against
                                       a structure with no on-file
                                       deficient finding is a
                                       fabrication this governor
                                       rejects.
   11. Already scheduled           -- for `:schedule-reinspection`,
                                       refuses to schedule the SAME
                                       reinspection record twice, off a
                                       dedicated `:scheduled?` fact
                                       (never a `:status` value).
   12. Invalid survey type         -- for `:log-inspection`, if the
                                       patch declares a `:survey-type`
                                       outside the closed known set
                                       (`infrainsp.registry/survey-
                                       type-valid?`), the inspection
                                       record is rejected rather than
                                       let a fabricated category
                                       through.
   13. Confidence floor / high-
       stakes gate                  -- LLM confidence below threshold,
                                       OR the proposal's own `:stake` is
                                       in `high-stakes`
                                       (`:coordination/structural-
                                       concern`, ALWAYS set for
                                       `:escalate-structural-concern`),
                                       OR the reading independently
                                       crosses the real external
                                       critical boundary
                                       (`infrainsp.registry/critical-
                                       reading?`) -- escalate to a
                                       human public-works authority.
                                       SOFT: the human may approve."
  (:require [infrainsp.registry :as registry]
            [infrainsp.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed allowlist of coordination proposals this actor may ever
  route -- see README `What this actor does`."
  #{:log-inspection :structural-scan
    :escalate-structural-concern :schedule-reinspection})

(def allowed-proposal-effects
  "The closed allowlist of SSoT-mutation effects a proposal may declare
  -- all four are propose-shaped drafts, NEVER a direct
  hardware/closure-control effect."
  #{:inspection-record/upsert :condition-finding/decide
    :concern/escalate :reinspection/schedule})

(def high-stakes
  "Stakes grave enough to always require a human, even when clean. A
  structural defect requiring immediate closure is the one op in this
  domain that always demands human eyes regardless of confidence."
  #{:coordination/structural-concern})

;; ----------------------------- checks -----------------------------

(defn- no-propose-effect-violations
  "HARD, unconditional, evaluated first: the caller's own request MUST
  declare `:effect :propose` -- any other value is a mis-wired or
  compromised caller trying to bypass proposal-only mode."
  [{:keys [effect]}]
  (when (not= effect :propose)
    [{:rule :not-propose-effect
      :detail (str "request :effect は :propose のみ許可 (受信値: " (pr-str effect) ")")}]))

(defn- unknown-op-violations
  "HARD: `:op` must be one of the closed allowlist this actor
  coordinates -- never route an unrecognized operation."
  [{:keys [op]}]
  (when-not (contains? allowed-ops op)
    [{:rule :unknown-op
      :detail (str op " はこの actor が扱う操作の許可リストに無い")}]))

(defn- equipment-control-blocked-violations
  "HARD, PERMANENT: the proposal's own `:effect` -- what would actually
  commit -- must be within the closed propose-shaped effect allowlist.
  Anything else (direct hardware/closure control, a fabricated
  actuation effect) is this actor's central scope boundary."
  [proposal]
  (when-not (contains? allowed-proposal-effects (:effect proposal))
    [{:rule :equipment-control-blocked
      :detail (str "proposal :effect (" (pr-str (:effect proposal))
                   ") は検査ロボットや閉鎖ゲート等の直接操作に該当する可能性があり、恒久的に禁止")}]))

(defn- equipment-actuate-blocked-violations
  "HARD, PERMANENT, unconditional: a proposal whose own `:value`
  declares `:actuate-equipment? true` is attempting to directly actuate
  the inspection robot beyond passive sensing -- this actor may only
  ever propose/schedule a DRAFT (an inspection log, a condition
  finding, a reinspection window), never actuate hardware directly. No
  override, ever."
  [proposal]
  (when (true? (:actuate-equipment? (:value proposal)))
    [{:rule :equipment-actuate-blocked
      :detail "検査ロボットの受動的センシングを超える直接操作(actuate)提案は恒久的に禁止 -- 提案(draft)のみ許可"}]))

(defn- closure-authority-blocked-violations
  "HARD, PERMANENT, unconditional: ANY proposal (any op) whose own
  `:value`/`:patch` declares `:closed? true` OUTSIDE the gated
  `:concern/escalate` path is attempting to self-report that a real
  closure was ordered through a side channel -- an authority this
  actor never holds. No phase and no human approval can ever override
  this."
  [proposal]
  (let [payload (or (:value proposal) (:patch proposal))]
    (when (and (true? (:closed? payload))
               (not= :concern/escalate (:effect proposal)))
      [{:rule :closure-authority-blocked
        :detail "構造物の閉鎖(closure)の自己申告は恒久的に禁止 -- 閉鎖は :escalate-structural-concern 経路でのみ、かつ人間確認後にのみ成立"}])))

(defn- structure-not-verified-violations
  "For `:structural-scan` and `:schedule-reinspection`, INDEPENDENTLY
  verify the referenced structure exists and is both `:verified?` AND
  `:registered?` -- never trust the advisor's own report."
  [{:keys [op]} proposal st]
  (when (contains? #{:structural-scan :schedule-reinspection} op)
    (let [structure-id (:structure-id (:value proposal))
          st-structure (and structure-id (store/structure st structure-id))]
      (when-not (and st-structure (registry/structure-ready? st-structure))
        [{:rule :structure-not-verified
          :detail (str structure-id " は未検証または未登録、もしくは存在しない -- 検証済み・登録済み構造物記録が無い状態での提案")}]))))

(defn- finding-mismatch-violations
  "For `:structural-scan`, INDEPENDENTLY recompute the
  acceptable/deficient finding from the proposal's own
  `:condition-rating` reading against the structure's own registered
  threshold, and compare it against the proposal's own claimed
  `:finding` -- never let a self-reported finding stand against
  contradicting sensor evidence."
  [{:keys [op]} proposal st]
  (when (= op :structural-scan)
    (let [{:keys [structure-id condition-rating finding]} (:value proposal)
          st-structure (and structure-id (store/structure st structure-id))]
      (when (and st-structure finding)
        (let [truth (registry/condition-finding st-structure condition-rating)]
          (when-not (= truth finding)
            [{:rule :finding-mismatch
              :detail (str structure-id " のcondition-rating(" condition-rating
                           ")から独立算出した判定は " truth
                           " -- 提案の自己申告判定 " finding " と不一致")}]))))))

(defn- invalid-condition-rating-violations
  "For `:structural-scan`, if `:condition-rating` is not a physically
  plausible reading for its own declared `:rating-scale`, reject
  rather than let fabricated/sensor-error data drive a finding."
  [{:keys [op]} proposal st]
  (when (= op :structural-scan)
    (let [{:keys [structure-id condition-rating]} (:value proposal)
          st-structure (and structure-id (store/structure st structure-id))
          rating-scale (:rating-scale st-structure)]
      (when-not (registry/condition-rating-valid? rating-scale condition-rating)
        [{:rule :invalid-condition-rating
          :detail (str (pr-str condition-rating) " は " (pr-str rating-scale) " 上で物理的に妥当な condition-rating ではない")}]))))

(defn- invalid-rating-scale-violations
  "For `:structural-scan`, if the structure's own declared
  `:rating-scale` is not one of the closed known conventions, reject
  -- an unrecognized scale cannot ground an arithmetic finding."
  [{:keys [op]} proposal st]
  (when (= op :structural-scan)
    (let [structure-id (:structure-id (:value proposal))
          st-structure (and structure-id (store/structure st structure-id))]
      (when (and st-structure (not (registry/rating-scale-valid? (:rating-scale st-structure))))
        [{:rule :invalid-rating-scale
          :detail (str structure-id " の rating-scale (" (pr-str (:rating-scale st-structure)) ") は既知の値ではない")}]))))

(defn- no-deficient-finding-violations
  "For `:schedule-reinspection`, INDEPENDENTLY verify the structure's
  own `:last-finding` on file is `:deficient` -- never trust the
  advisor's own claim that a reinspection is warranted. Scheduling
  (and billing) a reinspection with no on-file deficient finding is a
  fabrication this governor rejects."
  [{:keys [op]} proposal st]
  (when (= op :schedule-reinspection)
    (let [structure-id (:structure-id (:value proposal))
          st-structure (and structure-id (store/structure st structure-id))]
      (when (and st-structure (not= :deficient (:last-finding st-structure)))
        [{:rule :no-deficient-finding
          :detail (str structure-id " に未合格(:deficient)の登録済み判定が無い -- 再調査の予定提案には deficient 判定の記録が必要")}]))))

(defn- already-scheduled-violations
  "For `:schedule-reinspection`, refuses to schedule the SAME
  reinspection record twice, off a dedicated `:scheduled?` fact (never
  a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :schedule-reinspection)
    (when (store/reinspection-already-scheduled? st subject)
      [{:rule :already-scheduled
        :detail (str subject " は既にスケジュール済み")}])))

(defn- invalid-survey-type-violations
  "For `:log-inspection`, if the patch declares a `:survey-type`
  outside the closed known set, reject rather than let a fabricated
  category through."
  [{:keys [op]} proposal]
  (when (= op :log-inspection)
    (let [survey-type (:survey-type (:value proposal))]
      (when (and (some? survey-type) (not (registry/survey-type-valid? survey-type)))
        [{:rule :invalid-survey-type
          :detail (str survey-type " は既知の survey-type 値ではない")}]))))

(defn- critical-reading?
  "For `:structural-scan`, INDEPENDENTLY recompute whether the raw
  reading crosses the real external scale's own critical boundary
  (`infrainsp.registry/critical-reading?`) -- used to force escalation
  regardless of confidence, on top of (never instead of) the ordinary
  finding-mismatch/plausibility checks above."
  [{:keys [op]} proposal st]
  (when (= op :structural-scan)
    (let [{:keys [structure-id condition-rating]} (:value proposal)
          st-structure (and structure-id (store/structure st structure-id))]
      (when st-structure
        (registry/critical-reading? (:rating-scale st-structure) condition-rating)))))

(defn check
  "Censors an Inspection Advisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool :critical? bool}. `:critical?` is a
  triage signal grounded in the real external scale's own boundary
  (see `critical-reading?`) -- it forces escalation but never changes
  which node the graph commits through (`:structural-scan` is already
  never auto-eligible at any phase)."
  [request _context proposal st]
  (let [hard (into []
                   (concat (no-propose-effect-violations request)
                           (unknown-op-violations request)
                           (equipment-control-blocked-violations proposal)
                           (equipment-actuate-blocked-violations proposal)
                           (closure-authority-blocked-violations proposal)
                           (structure-not-verified-violations request proposal st)
                           (finding-mismatch-violations request proposal st)
                           (invalid-condition-rating-violations request proposal st)
                           (invalid-rating-scale-violations request proposal st)
                           (no-deficient-finding-violations request proposal st)
                           (already-scheduled-violations request st)
                           (invalid-survey-type-violations request proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))
        critical? (boolean (and (not hard?) (critical-reading? request proposal st)))]
    {:ok?          (and (not hard?) (not low?) (not stakes?) (not critical?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes? critical?))
     :high-stakes? stakes?
     :critical?    critical?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
