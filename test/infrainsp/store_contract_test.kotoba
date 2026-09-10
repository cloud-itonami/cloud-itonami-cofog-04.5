(ns infrainsp.store-contract-test
  "The Store contract as executable tests. Single MemStore backend --
  see `infrainsp.store` ns docstring."
  (:require [clojure.test :refer [deftest is testing]]
            [infrainsp.store :as store]))

(defn- seeded [] (-> (store/mem-store) (store/sample-data!)))

(deftest sample-data-read-basics
  (let [s (seeded)]
    (is (true? (:verified? (store/structure s "bridge-001"))))
    (is (true? (:registered? (store/structure s "bridge-001"))))
    (is (= :nbi-0-9 (:rating-scale (store/structure s "bridge-001"))))
    (is (nil? (:last-finding (store/structure s "bridge-001"))))
    (is (true? (:verified? (store/structure s "pavement-002"))))
    (is (= :pci-0-100 (:rating-scale (store/structure s "pavement-002"))))
    (is (= :deficient (:last-finding (store/structure s "pavement-002"))))
    (is (false? (:verified? (store/structure s "bridge-003"))))
    (is (false? (:registered? (store/structure s "bridge-003"))))
    (is (= ["bridge-001" "bridge-003" "pavement-002"] (mapv :id (store/all-structures s))))
    (is (= [] (store/ledger s)))
    (is (= [] (store/finding-history s)))
    (is (= [] (store/reinspection-history s)))
    (is (= [] (store/structural-concerns s)))
    (is (zero? (store/next-finding-sequence s)))
    (is (zero? (store/next-reinspection-sequence s)))
    (is (false? (store/reinspection-already-scheduled? s "rei-1")))
    (is (nil? (store/reinspection s "rei-1")))))

(deftest fresh-store-has-no-structures
  (let [s (store/mem-store)]
    (is (= [] (store/all-structures s)))
    (is (nil? (store/structure s "bridge-001")))))

(deftest inspection-record-upsert-merges-preserving-untouched-fields
  (let [s (seeded)]
    (store/commit-record! s {:effect :inspection-record/upsert :path ["insp-001"]
                             :value {:structure-id "bridge-001" :survey-type :bridge-deck
                                     :notes "初回"}})
    (is (= :bridge-deck (:survey-type (store/inspection-record s "insp-001"))))
    (is (= "bridge-001" (:structure-id (store/inspection-record s "insp-001"))))
    (store/commit-record! s {:effect :inspection-record/upsert :path ["insp-001"]
                             :value {:notes "更新"}})
    (is (= :bridge-deck (:survey-type (store/inspection-record s "insp-001"))) "unrelated field preserved")
    (is (= "更新" (:notes (store/inspection-record s "insp-001"))))))

(deftest condition-finding-decide-commits-and-advances-sequence-and-updates-structure
  (testing "commit-record! (like every sibling actor's own MemStore) returns the store `s`, not the domain result -- inspect the store directly"
    (let [s (seeded)]
      (store/commit-record! s {:effect :condition-finding/decide :path ["find-1"]
                               :value {:structure-id "bridge-001" :condition-rating 8.0 :finding :acceptable}})
      (is (= "CF-000000" (get (first (store/finding-history s)) "record_id")))
      (is (= "condition-finding-draft" (get (first (store/finding-history s)) "kind")))
      (is (= 1 (count (store/finding-history s))))
      (is (= 1 (store/next-finding-sequence s)))
      (is (= :acceptable (:last-finding (store/structure s "bridge-001")))
          "ground-truth structure record updated by the commit")
      (is (= :acceptable (:finding (store/condition-finding s "bridge-001")))))))

(deftest concern-escalate-appends
  (let [s (seeded)]
    (store/commit-record! s {:effect :concern/escalate :path ["conc-1"]
                             :value {:structure-id "bridge-001" :concern-type :section-loss :severity :critical}})
    (is (= 1 (count (store/structural-concerns s))))
    (is (= :section-loss (:concern-type (first (store/structural-concerns s)))))
    (store/commit-record! s {:effect :concern/escalate :path ["conc-2"]
                             :value {:structure-id "pavement-002" :concern-type :pothole-cluster :severity :moderate}})
    (is (= 2 (count (store/structural-concerns s))) "append-only")))

(deftest reinspection-schedule-commits-and-advances-sequence
  (let [s (seeded)]
    (store/commit-record! s {:effect :reinspection/schedule :path ["rei-1"]
                             :value {:structure-id "pavement-002" :scheduled-date "2026-08-01"}})
    (is (= "REI-000000" (get (first (store/reinspection-history s)) "record_id")))
    (is (= "reinspection-schedule-draft" (get (first (store/reinspection-history s)) "kind")))
    (is (true? (:scheduled? (store/reinspection s "rei-1"))))
    (is (= "pavement-002" (:structure-id (store/reinspection s "rei-1"))))
    (is (= 1 (count (store/reinspection-history s))))
    (is (= 1 (store/next-reinspection-sequence s)))
    (is (true? (store/reinspection-already-scheduled? s "rei-1")))
    (is (= "REI-000000" (:reinspection-number (store/reinspection s "rei-1"))))))

(deftest ledger-is-append-only-and-order-preserving
  (let [s (store/mem-store)]
    (store/append-ledger! s {:op :a :disposition :commit})
    (store/append-ledger! s {:op :b :disposition :hold})
    (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))

(deftest generic-commit-record-path-writes-a-raw-record-by-id
  (testing "a record with no :effect key is written verbatim into the generic records map -- the store-level primitive underneath the domain-specific dispatch"
    (let [s (store/mem-store)
          record {:id "test-001" :data "test"}]
      (store/commit-record! s record)
      (is (= record (get (store/get-records s) "test-001"))))))

(deftest get-ledger-alias-matches-ledger
  (let [s (store/mem-store)]
    (store/append-ledger! s {:t :x})
    (is (= (store/ledger s) (store/get-ledger s)))))
