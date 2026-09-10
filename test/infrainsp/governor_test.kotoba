(ns infrainsp.governor-test
  "Direct unit tests against `infrainsp.governor/check` with hand-crafted
  proposals -- including the finding-mismatch case a well-behaved
  deterministic advisor can never itself produce (see
  `infrainsp.advisor/structural-scan`, which always recomputes its own
  :finding honestly). This governor's INDEPENDENT recompute exists
  precisely for a compromised/hallucinating advisor or the LLM-advisor
  path -- exercised here directly, the same discipline
  `infrainsp.governor-contract-test` applies at the full-graph level
  for every other rule."
  (:require [clojure.test :refer [deftest is testing]]
            [infrainsp.store :as store]
            [infrainsp.governor :as governor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/with-structures st {"bridge-001" {:id "bridge-001" :kind :bridge-deck
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
    st))

(def ^:private req {:op :structural-scan :effect :propose :subject "find-x"})

(defn- scan [structure-id condition-rating finding]
  {:effect :condition-finding/decide
   :value {:structure-id structure-id :condition-rating condition-rating :finding finding}
   :confidence 0.9 :stake nil})

(deftest ok-when-finding-matches-independent-recompute
  (let [st (fresh-store)
        v (governor/check req {} (scan "bridge-001" 8.0 :acceptable) st)]
    (is (not (:hard? v)))))

(deftest hard-on-finding-mismatch-acceptable-when-truth-is-deficient
  (testing "an advisor rubber-stamping :acceptable when the sensor reading itself would be deficient -- HARD, never let a self-report stand against contradicting evidence"
    (let [st (fresh-store)
          v (governor/check req {} (scan "bridge-001" 2.0 :acceptable) st)]
      (is (:hard? v))
      (is (some #(= :finding-mismatch (:rule %)) (:violations v))))))

(deftest hard-on-finding-mismatch-deficient-when-truth-is-acceptable
  (let [st (fresh-store)
        v (governor/check req {} (scan "bridge-001" 8.0 :deficient) st)]
    (is (:hard? v))
    (is (some #(= :finding-mismatch (:rule %)) (:violations v)))))

(deftest hard-on-unverified-structure-for-structural-scan
  (let [st (fresh-store)
        v (governor/check req {} (scan "bridge-003" 6.0 :acceptable) st)]
    (is (:hard? v))
    (is (some #(= :structure-not-verified (:rule %)) (:violations v)))))

(deftest hard-on-invalid-condition-rating-for-nbi-scale
  (let [st (fresh-store)
        v (governor/check req {} (scan "bridge-001" 55.0 :deficient) st)]
    (is (:hard? v))
    (is (some #(= :invalid-condition-rating (:rule %)) (:violations v)))))

(deftest hard-on-non-propose-effect
  (let [st (fresh-store)
        v (governor/check {:op :structural-scan :effect :direct-write :subject "x"} {}
                          (scan "bridge-001" 8.0 :acceptable) st)]
    (is (:hard? v))
    (is (some #(= :not-propose-effect (:rule %)) (:violations v)))))

(deftest hard-on-unknown-op
  (let [st (fresh-store)
        v (governor/check {:op :close-lane-directly :effect :propose :subject "x"} {}
                          {:effect :inspection-record/upsert :confidence 0.9} st)]
    (is (:hard? v))
    (is (some #(= :unknown-op (:rule %)) (:violations v)))))

(deftest hard-on-proposal-effect-outside-allowlist
  (let [st (fresh-store)
        v (governor/check req {} (assoc (scan "bridge-001" 8.0 :acceptable) :effect :gate/actuate) st)]
    (is (:hard? v))
    (is (some #(= :equipment-control-blocked (:rule %)) (:violations v)))))

(deftest hard-on-actuate-equipment-permanent
  (let [st (fresh-store)
        v (governor/check {:op :schedule-reinspection :effect :propose :subject "rei-x"} {}
                          {:effect :reinspection/schedule :confidence 0.9
                           :value {:structure-id "pavement-002" :actuate-equipment? true}} st)]
    (is (:hard? v))
    (is (some #(= :equipment-actuate-blocked (:rule %)) (:violations v)))))

(deftest hard-on-closure-authority-side-channel
  (let [st (fresh-store)
        v (governor/check {:op :log-inspection :effect :propose :subject "insp-x"} {}
                          {:effect :inspection-record/upsert :confidence 0.9
                           :value {:structure-id "bridge-001" :closed? true}} st)]
    (is (:hard? v))
    (is (some #(= :closure-authority-blocked (:rule %)) (:violations v)))))

(deftest concern-escalate-effect-with-closed-flag-is-not-a-side-channel
  (testing "the gated :concern/escalate effect itself is exempt from the side-channel block"
    (let [st (fresh-store)
          v (governor/check {:op :escalate-structural-concern :effect :propose :subject "conc-x"} {}
                            {:effect :concern/escalate :confidence 0.9 :stake :coordination/structural-concern
                             :value {:structure-id "bridge-001" :closed? true}} st)]
      (is (not (some #(= :closure-authority-blocked (:rule %)) (:violations v)))))))

(deftest hard-on-no-deficient-finding-for-reinspection
  (let [st (fresh-store)
        v (governor/check {:op :schedule-reinspection :effect :propose :subject "rei-x"} {}
                          {:effect :reinspection/schedule :confidence 0.9
                           :value {:structure-id "bridge-001" :actuate-equipment? false}} st)]
    (is (:hard? v))
    (is (some #(= :no-deficient-finding (:rule %)) (:violations v)))))

(deftest ok-reinspection-against-deficient-structure
  (let [st (fresh-store)
        v (governor/check {:op :schedule-reinspection :effect :propose :subject "rei-x"} {}
                          {:effect :reinspection/schedule :confidence 0.9
                           :value {:structure-id "pavement-002" :actuate-equipment? false}} st)]
    (is (not (:hard? v)))))

(deftest hard-on-invalid-survey-type
  (let [st (fresh-store)
        v (governor/check {:op :log-inspection :effect :propose :subject "insp-x"} {}
                          {:effect :inspection-record/upsert :confidence 0.9
                           :value {:structure-id "bridge-001" :survey-type :satellite-flyover}} st)]
    (is (:hard? v))
    (is (some #(= :invalid-survey-type (:rule %)) (:violations v)))))

(deftest escalates-structural-concern-regardless-of-confidence
  (let [st (fresh-store)
        v (governor/check {:op :escalate-structural-concern :effect :propose :subject "conc-x"} {}
                          {:effect :concern/escalate :confidence 0.99
                           :stake :coordination/structural-concern
                           :value {:structure-id "bridge-001" :concern-type :section-loss}} st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))

(deftest escalates-low-confidence
  (let [st (fresh-store)
        v (governor/check req {} (assoc (scan "bridge-001" 8.0 :acceptable) :confidence 0.2) st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))

;; ----------------------------- critical-reading real-scale grounding -----------------------------

(deftest critical-flag-set-for-nbi-imminent-failure-reading
  (testing "FHWA/AASHTO NBI rating 1 (Imminent Failure) -- real external boundary, forces escalation regardless of confidence"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (scan "bridge-001" 1.0 :deficient) :confidence 0.99) st)]
      (is (not (:hard? v)))
      (is (true? (:critical? v)))
      (is (:escalate? v)))))

(deftest critical-flag-not-set-for-routine-deficient-reading
  (let [st (fresh-store)
        v (governor/check req {} (scan "bridge-001" 3.0 :deficient) st)]
    (is (false? (:critical? v)))))

(deftest critical-flag-set-for-pci-failed-reading
  (testing "ASTM D6433 PCI rating 5 (Failed category, 0-10) -- real external boundary"
    (let [pav-req {:op :structural-scan :effect :propose :subject "find-y"}
          st (fresh-store)
          v (governor/check pav-req {} (assoc (scan "pavement-002" 5.0 :deficient) :confidence 0.99) st)]
      (is (not (:hard? v)))
      (is (true? (:critical? v))))))
