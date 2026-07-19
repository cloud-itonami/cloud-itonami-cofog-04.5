(ns infrainsp.operation-test
  "Smoke tests for the compiled InspectionOperationActor graph itself
  (build + one happy path per op). The governor's full rule contract
  (HARD holds, escalation, phase gating) is exercised in
  `infrainsp.governor-contract-test` (full graph) and
  `infrainsp.governor-test` (direct unit); the Store contract in
  `infrainsp.store-contract-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [infrainsp.operation :as op]
            [infrainsp.store :as store]))

(def coordinator {:actor-id "coord-1" :actor-role :inspection-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(deftest test-actor-builds
  (testing "InspectionOperationActor can be built with a store"
    (let [s (store/mem-store)
          actor (op/build s)]
      (is (not (nil? actor))))))

(deftest test-inspection-logging-proposal
  (testing "Proposing an inspection log auto-commits when clean (phase 3, no physical/financial risk)"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          initial-ledger-size (count (store/get-ledger s))
          result (exec-op actor "t1"
                          {:op :log-inspection :effect :propose :subject "insp-001"
                           :patch {:structure-id "bridge-001" :survey-type :bridge-deck}}
                          coordinator)
          final-ledger-size (count (store/get-ledger s))]
      (is (> final-ledger-size initial-ledger-size))
      (is (= :commit (get-in result [:state :disposition]))))))

(deftest test-structural-scan
  (testing "Structural-scan condition findings always escalate for human approval"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          result (exec-op actor "t2"
                          {:op :structural-scan :effect :propose :subject "find-1"
                           :value {:structure-id "bridge-001" :condition-rating 8.0}}
                          coordinator)]
      (is (= :interrupted (:status result)))
      (is (= :commit (get-in (approve! actor "t2") [:state :disposition]))))))

(deftest test-structural-concern-escalation
  (testing "Structural-concern escalations always escalate"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          result (exec-op actor "t3"
                          {:op :escalate-structural-concern :effect :propose :subject "conc-1"
                           :value {:structure-id "bridge-001" :concern-type :section-loss
                                   :severity :critical :description "section loss"}}
                          coordinator)]
      (is (= :interrupted (:status result))))))

(deftest test-schedule-reinspection-proposal
  (testing "Reinspection scheduling proposal is submitted and (when the structure has a deficient finding on file) escalates for approval"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          result (exec-op actor "t4"
                          {:op :schedule-reinspection :effect :propose :subject "rei-1"
                           :value {:structure-id "pavement-002" :scheduled-date "2026-08-01"
                                   :actuate-equipment? false}}
                          coordinator)]
      (is (some? result))
      (is (= :interrupted (:status result))))))

(deftest test-ledger-is-append-only
  (testing "Audit ledger is append-only"
    (let [s (store/mem-store)
          initial-count (count (store/get-ledger s))]
      (store/append-ledger! s {:t :test-entry})
      (is (= (inc initial-count) (count (store/get-ledger s)))))))

(deftest test-records-are-committed
  (testing "The domain-agnostic commit-record! path stores a raw record by :id"
    (let [s (store/mem-store)
          record {:id "test-001" :data "test"}]
      (store/commit-record! s record)
      (is (= record (get (store/get-records s) "test-001"))))))
