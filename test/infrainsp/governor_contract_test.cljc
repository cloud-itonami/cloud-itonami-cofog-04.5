(ns infrainsp.governor-contract-test
  "The governor contract as executable tests -- this vertical's own
  scope boundary ('does NOT actuate the inspection robot beyond
  passive sensing... does NOT order a real structure closure')
  implemented faithfully through the FULL compiled graph. The single
  invariant under test:

    InspectionAdvisor never decides a condition finding, escalates a
    structural concern, or schedules a reinspection the Infrastructure
    Inspection Governor would reject; `:structural-scan`/
    `:escalate-structural-concern`/`:schedule-reinspection` NEVER
    auto-commit at any phase; `:log-inspection` (no physical/financial
    risk) MAY auto-commit when clean; and every decision (commit OR
    hold) leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [infrainsp.store :as store]
            [infrainsp.operation :as op]))

(defn- fresh []
  (let [db (-> (store/mem-store) (store/sample-data!))]
    [db (op/build db)]))

(def coordinator {:actor-id "coord-1" :actor-role :inspection-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(defn- reject! [actor tid]
  (g/run* actor {:approval {:status :rejected :by "coord-1"}} {:thread-id tid :resume? true}))

(deftest clean-log-inspection-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :log-inspection :effect :propose :subject "insp-001"
                   :patch {:structure-id "bridge-001" :survey-type :bridge-deck}} coordinator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= :bridge-deck (:survey-type (store/inspection-record db "insp-001"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest structural-scan-always-needs-approval
  (testing "condition-finding decisions are never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2"
                    {:op :structural-scan :effect :propose :subject "find-1"
                     :value {:structure-id "bridge-001" :condition-rating 8.0}}
                    coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= :acceptable (:last-finding (store/structure db "bridge-001"))))
        (is (= 1 (count (store/finding-history db))))))))

(deftest structural-scan-imminent-failure-reading-still-just-escalates
  (testing "a real FHWA/AASHTO Imminent Failure reading (NBI rating <= 1) is flagged :critical? in the verdict but does not bypass the human-approval interrupt -- it was already never auto-eligible"
    (let [[db actor] (fresh)
          res (exec-op actor "t2c"
                    {:op :structural-scan :effect :propose :subject "find-1c"
                     :value {:structure-id "bridge-001" :condition-rating 1.0}}
                    coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2c")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= :deficient (:last-finding (store/structure db "bridge-001"))))))))

(deftest effect-not-propose-is-held
  (testing "a request whose own :effect is not :propose -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :log-inspection :effect :direct-write :subject "insp-001"
                     :patch {:structure-id "bridge-001"}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:not-propose-effect} (-> (store/ledger db) first :basis))))))

(deftest unknown-op-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t4" {:op :close-lane-directly :effect :propose :subject "x"} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:unknown-op} (-> (store/ledger db) first :basis)))))

(deftest structure-not-verified-is-held-and-unoverridable
  (testing "scanning an unverified/unregistered bridge joint -> HOLD, settles immediately, no interrupt"
    (let [[db actor] (fresh)
          res (exec-op actor "t5"
                    {:op :structural-scan :effect :propose :subject "find-2"
                     :value {:structure-id "bridge-003" :condition-rating 6.0}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:structure-not-verified} (-> (store/ledger db) last :basis)))
      (is (empty? (store/finding-history db))))))

(deftest invalid-condition-rating-is-held
  (testing "an implausible condition-rating for its own declared NBI 0-9 scale -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t6"
                    {:op :structural-scan :effect :propose :subject "find-3"
                     :value {:structure-id "bridge-001" :condition-rating 55.0}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:invalid-condition-rating} (-> (store/ledger db) last :basis)))
      (is (empty? (store/finding-history db))))))

(deftest no-deficient-finding-is-held-and-unoverridable
  (testing "scheduling a reinspection with no on-file deficient finding -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t7"
                    {:op :schedule-reinspection :effect :propose :subject "rei-1"
                     :value {:structure-id "bridge-001" :scheduled-date "2026-08-01"
                             :actuate-equipment? false}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:no-deficient-finding} (-> (store/ledger db) last :basis)))
      (is (empty? (store/reinspection-history db))))))

(deftest equipment-actuate-is-held-and-permanently-blocked
  (testing "a proposal that sets :actuate-equipment? true -> HOLD, PERMANENT, never reaches request-approval even though the structure is verified, registered and has a deficient finding on file"
    (let [[db actor] (fresh)
          res (exec-op actor "t8"
                    {:op :schedule-reinspection :effect :propose :subject "rei-2"
                     :value {:structure-id "pavement-002" :scheduled-date "2026-09-01"
                             :actuate-equipment? true}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:equipment-actuate-blocked} (-> (store/ledger db) last :basis)))
      (is (empty? (store/reinspection-history db))))))

(deftest closure-authority-is-held-and-permanently-blocked
  (testing "a proposal that sets :closed? true outside the gated structural-concern-escalation path -> HOLD, PERMANENT, never reaches request-approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t8b"
                    {:op :log-inspection :effect :propose :subject "insp-002"
                     :patch {:structure-id "bridge-001" :closed? true}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:closure-authority-blocked} (-> (store/ledger db) last :basis)))
      (is (not (true? (:closed? (store/inspection-record db "insp-002"))))
          "fabricated self-reported closure never lands in the SSoT"))))

(deftest schedule-reinspection-double-schedule-is-held
  (testing "scheduling the SAME reinspection record twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (exec-op actor "t9a" {:op :schedule-reinspection :effect :propose :subject "rei-3"
                                  :value {:structure-id "pavement-002" :scheduled-date "2026-08-01"
                                          :actuate-equipment? false}} coordinator)
          _ (approve! actor "t9a")
          res (exec-op actor "t9" {:op :schedule-reinspection :effect :propose :subject "rei-3"
                                   :value {:structure-id "pavement-002" :scheduled-date "2026-08-01"
                                           :actuate-equipment? false}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-scheduled} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/reinspection-history db))) "still only the one earlier schedule"))))

(deftest invalid-survey-type-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t10" {:op :log-inspection :effect :propose :subject "insp-003"
                                  :patch {:structure-id "bridge-001" :survey-type :satellite-flyover}} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:invalid-survey-type} (-> (store/ledger db) last :basis)))
    (is (not= :satellite-flyover (:survey-type (store/inspection-record db "insp-003"))) "fabricated survey-type never lands in the SSoT")))

(deftest structural-concern-always-escalates-even-high-confidence
  (testing "escalate-structural-concern always escalates -- never auto-committed, regardless of confidence"
    (let [[db actor] (fresh)
          res (exec-op actor "t11" {:op :escalate-structural-concern :effect :propose :subject "conc-1"
                                    :value {:structure-id "bridge-001" :concern-type :section-loss
                                            :severity :critical :description "section loss"}}
                       coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t11")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/structural-concerns db))))))))

(deftest structural-concern-approval-rejected-leaves-no-record-only-a-hold-fact
  (let [[db actor] (fresh)
        _ (exec-op actor "t12" {:op :escalate-structural-concern :effect :propose :subject "conc-2"
                                :value {:structure-id "bridge-001" :concern-type :pothole-cluster
                                        :severity :low :description "y"}}
                   coordinator)
        r (reject! actor "t12")]
    (is (= :hold (get-in r [:state :disposition])))
    (is (= 0 (count (store/structural-concerns db))) "rejected approval never reaches the commit node")
    (is (= 1 (count (store/ledger db))))))

(deftest schedule-reinspection-always-needs-approval
  (testing "a CLEAN reinspection scheduling proposal is never auto-eligible -- always escalates, even against a structure with a deficient finding on file"
    (let [[db actor] (fresh)
          res (exec-op actor "t13" {:op :schedule-reinspection :effect :propose :subject "rei-4"
                                    :value {:structure-id "pavement-002" :scheduled-date "2026-08-01"
                                            :actuate-equipment? false}}
                       coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t13")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/reinspection-history db))))))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N settled operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :log-inspection :effect :propose :subject "insp-001"
                          :patch {:structure-id "bridge-001" :survey-type :bridge-deck}} coordinator)
      (exec-op actor "b" {:op :log-inspection :effect :propose :subject "insp-002"
                          :patch {:structure-id "bridge-001" :survey-type :fabricated-type}} coordinator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
