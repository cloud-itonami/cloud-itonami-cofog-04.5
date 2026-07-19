(ns infrainsp.registry
  "Pure-function domain logic for the road/bridge-infrastructure-
  inspection back-office coordination actor -- structure verification,
  the condition-finding verdict (a raw condition-rating sensor reading
  vs a structure's own registered maintenance-trigger threshold),
  the INDEPENDENT critical-reading recompute grounded in real published
  bridge/pavement condition-rating scales, reading-plausibility
  validation, and draft finding/reinspection-schedule record
  construction.

  No pre-existing `kotoba-lang/infrainsp`-style capability library
  exists for this vertical, so the domain logic lives here as pure
  functions, re-verified INDEPENDENTLY by `infrainsp.governor` -- the
  same 'ground truth, not self-report' discipline every sibling
  actor's own registry establishes: never trust a proposal's own
  self-reported finding when the inputs needed to recompute it
  independently are already on record.

  This namespace is pure data + pure functions -- no I/O, no network
  call to a real sensor/closure-order system. It builds the DRAFT
  record a road/bridge inspection contractor would keep (a condition
  finding, a scheduled reinspection window), not the act of actuating
  the inspection robot beyond passive sensing and never the act of
  ordering a real structure closure (see README `Robotics premise` and
  `infrainsp.governor`'s permanent scope blocks).

  SCOPE: COFOG 04.5 (Transport) -- here illustrated as an independent
  road/bridge infrastructure inspection contractor: a road/bridge
  inspection robot (crack/pothole imaging, bridge deck and joint
  scanning) performs the on-site survey; this actor coordinates the
  back-office record-keeping around that survey (inspection logging,
  condition-finding decisions, structural-concern escalation,
  reinspection scheduling). It never actuates the robot beyond passive
  sensing and never orders a real closure -- only a human public-works
  authority does that, off this actor's escalation record.

  `:rating-scale` is a closed two-value taxonomy, each grounded in a
  REAL, verified published condition-rating convention this actor does
  not implement but whose EXACT numeric boundaries this actor's
  `critical-reading?` cites directly (never fabricated):

    `:nbi-0-9`   -- the FHWA National Bridge Inspection Standards (23
                    CFR 650 Subpart C) / AASHTO Manual for Bridge
                    Evaluation condition-rating scale for a bridge
                    deck/superstructure/substructure component: 0-9,
                    where a component rating of 7+ is 'Good', 5-6 is
                    'Fair', 4 or below is 'Poor', and a rating of 1
                    ('Imminent Failure') means the structure is closed
                    to traffic; 0 is 'Failed' (out of service beyond
                    corrective action).
    `:pci-0-100` -- the ASTM D6433 Pavement Condition Index (PCI) scale
                    for a road-surface/parking-lot segment: 0-100,
                    where 86-100 is 'Good' (routine monitoring only)
                    and 0-10 is 'Failed' (demands immediate
                    reconstruction).

  A structure's own `:condition-threshold` (the OPERATOR's own
  registered maintenance-trigger point, distinct from these external
  scales' own category boundaries) is what a routine `:acceptable`/
  `:deficient` finding is judged against -- NBI/PCI do not themselves
  mandate a maintenance-trigger threshold, only the inspection and
  rating discipline; individual authorities set their own trigger
  policy. `critical-reading?` below is the one place this actor cites
  the EXTERNAL scale's own fixed boundary directly, because 'imminent
  failure closed to traffic' (NBI) and 'failed, demands immediate
  reconstruction' (PCI) are not policy choices a local operator's own
  threshold can loosen."
  )

;; ----------------------------- constants -----------------------------

(def valid-survey-types
  "The closed set of survey-type values an inspection record may
  declare -- `:pavement` and the two named bridge-component premises
  (see README `Robotics premise`: 'bridge deck and joint scanning')."
  #{:pavement :bridge-deck :bridge-joint})

(def valid-rating-scales
  "The closed set of condition-rating scales a structure may declare
  -- see ns docstring for the real, verified convention each grounds."
  #{:nbi-0-9 :pci-0-100})

(def rating-bounds
  "Physical [min max] bounds for a raw condition-rating reading, keyed
  by its declared `:rating-scale`. A reading outside its own scale's
  bounds is implausible sensor/QC data, never a real scan result."
  {:nbi-0-9 [0.0 9.0]
   :pci-0-100 [0.0 100.0]})

;; ----------------------------- structure checks -----------------------------

(defn structure-verified?
  "Ground-truth check: has `structure`'s own record been marked
  verified (i.e. it has actually been inspected/commissioned, not
  merely referenced from an unverified request)? A pure predicate over
  the structure's own permanent field -- no proposal inspection
  needed."
  [structure]
  (true? (:verified? structure)))

(defn structure-registered?
  "Ground-truth check: does `structure`'s own record carry a
  `:registered?` true flag (i.e. it is on file in the contractor's own
  structure registry)? Surveying or scheduling work against a
  structure that is not on file and registered is the exact scope
  violation this actor's HARD invariant ('structure record must be
  independently verified/registered before any action') exists to
  block."
  [structure]
  (true? (:registered? structure)))

(defn structure-ready?
  "Combined ground-truth gate: the structure must be both `verified?`
  AND `registered?` before ANY structural scan or reinspection
  schedule may be proposed against it. Two independent facts on the
  structure's own permanent record, neither inferred from the
  advisor's own rationale."
  [structure]
  (and (structure-verified? structure) (structure-registered? structure)))

;; ----------------------------- condition finding verdict -----------------------------

(defn condition-finding
  "INDEPENDENT recompute of the :acceptable/:deficient finding from a
  raw `condition-rating` reading against `structure`'s own registered
  `:condition-threshold` -- the arithmetic ground truth
  `infrainsp.governor` cross-checks a proposal's own claimed `:finding`
  against. Both `:nbi-0-9` and `:pci-0-100` are 'lower is worse' scales
  (see ns docstring), so the comparison direction is the same
  regardless of the structure's own declared `:rating-scale`:
  `:deficient` whenever the reading is missing, non-numeric, or at or
  below the structure's own threshold (fail-safe: an unreadable sensor
  is never silently treated as :acceptable); `:acceptable` only for a
  plausible reading strictly above it. Never trusts a proposal's own
  claimed finding."
  [structure condition-rating]
  (let [threshold (:condition-threshold structure)]
    (if (and (number? condition-rating) (number? threshold)
             (> (double condition-rating) (double threshold)))
      :acceptable
      :deficient)))

(defn critical-reading?
  "INDEPENDENT recompute, grounded in the REAL external scale's own
  fixed boundary (never the structure's own local threshold): does
  `condition-rating` cross the line that scale's own publishing body
  treats as beyond a local operator's discretion? `:nbi-0-9` rating <=
  1.0 is FHWA/AASHTO's own 'Imminent Failure' rating (closed to
  traffic); `:pci-0-100` rating <= 10.0 is ASTM D6433's own 'Failed'
  category (demands immediate reconstruction). This flag does not by
  itself change this actor's disposition -- `:structural-scan` is
  already never auto-eligible at any phase regardless (see
  `infrainsp.phase`) -- it exists so a human reviewing a queue of
  escalated findings can triage the ones an external, non-negotiable
  standard already calls critical ahead of routine ones. Unknown/
  missing `:rating-scale` or reading -> false (never silently marks
  unreadable data as critical)."
  [rating-scale condition-rating]
  (boolean
   (when (number? condition-rating)
     (case rating-scale
       :nbi-0-9   (<= (double condition-rating) 1.0)
       :pci-0-100 (<= (double condition-rating) 10.0)
       false))))

(defn condition-rating-valid?
  "Is `condition-rating` a physically plausible reading for its own
  declared `:rating-scale`? Rejects nil, non-numbers, an unrecognized
  scale, and values outside that scale's own `rating-bounds` -- a
  fabricated or sensor-error reading, never let through as a real scan
  fact."
  [rating-scale condition-rating]
  (boolean
   (when-let [[lo hi] (get rating-bounds rating-scale)]
     (and (number? condition-rating)
          (>= (double condition-rating) (double lo))
          (<= (double condition-rating) (double hi))))))

(defn survey-type-valid?
  "Is `survey-type` one of the closed, known survey-type values
  (`:pavement`, `:bridge-deck`, `:bridge-joint`)? nil is treated as
  invalid (an inspection record must declare a real survey type, not
  omit it silently)."
  [survey-type]
  (contains? valid-survey-types survey-type))

(defn rating-scale-valid?
  "Is `rating-scale` one of the closed, known scale values? nil is
  treated as invalid."
  [rating-scale]
  (contains? valid-rating-scales rating-scale))

;; ----------------------------- draft record construction -----------------------------

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the human public-works authority's act, not this actor's. This actor
  NEVER orders a real structure closure or self-issues an official
  condition clearance (see README `What this actor does NOT do`)."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-condition-finding
  "Validate + construct the CONDITION-FINDING DRAFT -- a
  condition-rating-grounded acceptable/deficient finding against a
  verified, registered structure. Pure function -- does not order any
  closure or sign anything; it builds the RECORD a road/bridge
  inspection contractor would keep. `infrainsp.governor` independently
  re-verifies the structure's own verified/registered ground truth and
  the finding's own arithmetic (`condition-finding`) before this is
  ever allowed to commit."
  [finding-id structure-id finding sequence]
  (when-not (and finding-id (not= finding-id ""))
    (throw (ex-info "condition-finding: finding_id required" {})))
  (when-not (and structure-id (not= structure-id ""))
    (throw (ex-info "condition-finding: structure_id required" {})))
  (when-not (#{:acceptable :deficient} finding)
    (throw (ex-info "condition-finding: finding must be :acceptable or :deficient" {})))
  (when (< sequence 0)
    (throw (ex-info "condition-finding: sequence must be >= 0" {})))
  (let [finding-number (str "CF-" (zero-pad sequence 6))
        record {"record_id" finding-number
                "kind" "condition-finding-draft"
                "finding_id" finding-id
                "structure_id" structure-id
                "finding" (name finding)
                "immutable" true}]
    {"record" record "finding_number" finding-number
     "certificate" (unsigned-certificate "ConditionFinding" finding-number finding-number)}))

(defn register-reinspection
  "Validate + construct the REINSPECTION-SCHEDULE DRAFT -- a proposed
  follow-up survey window against a structure with an on-file
  DEFICIENT condition finding. Pure function -- does not actuate the
  inspection robot or order any real closure; it builds the RECORD a
  road/bridge inspection contractor would keep. `infrainsp.governor`
  independently re-verifies the structure's own on-file
  deficient-finding ground truth before this is ever allowed to
  commit."
  [reinspection-id structure-id sequence]
  (when-not (and reinspection-id (not= reinspection-id ""))
    (throw (ex-info "reinspection: reinspection_id required" {})))
  (when-not (and structure-id (not= structure-id ""))
    (throw (ex-info "reinspection: structure_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "reinspection: sequence must be >= 0" {})))
  (let [reinspection-number (str "REI-" (zero-pad sequence 6))
        record {"record_id" reinspection-number
                "kind" "reinspection-schedule-draft"
                "reinspection_id" reinspection-id
                "structure_id" structure-id
                "immutable" true}]
    {"record" record "reinspection_number" reinspection-number
     "certificate" (unsigned-certificate "ReinspectionSchedule" reinspection-number reinspection-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
