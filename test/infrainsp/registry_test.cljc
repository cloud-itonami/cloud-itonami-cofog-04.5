(ns infrainsp.registry-test
  (:require [clojure.test :refer [deftest is]]
            [infrainsp.registry :as r]))

;; ----------------------------- structure-verified? / structure-registered? / structure-ready? -----------------------------

(deftest structure-is-verified-when-flagged
  (is (true? (r/structure-verified? {:id "s1" :verified? true}))))

(deftest structure-is-not-verified-when-false-or-missing
  (is (false? (r/structure-verified? {:id "s1" :verified? false})))
  (is (false? (r/structure-verified? {:id "s1"}))))

(deftest structure-is-registered-when-flagged
  (is (true? (r/structure-registered? {:registered? true}))))

(deftest structure-is-not-registered-when-false-or-missing
  (is (false? (r/structure-registered? {:registered? false})))
  (is (false? (r/structure-registered? {}))))

(deftest structure-ready-requires-both
  (is (true? (r/structure-ready? {:verified? true :registered? true})))
  (is (false? (r/structure-ready? {:verified? true :registered? false})))
  (is (false? (r/structure-ready? {:verified? false :registered? true})))
  (is (false? (r/structure-ready? {}))))

;; ----------------------------- condition-finding -----------------------------

(deftest reading-above-threshold-is-acceptable
  (is (= :acceptable (r/condition-finding {:condition-threshold 5.0} 8.0))))

(deftest reading-at-or-below-threshold-is-deficient
  (is (= :deficient (r/condition-finding {:condition-threshold 5.0} 5.0))
      "exactly at threshold is not above it, only strictly over")
  (is (= :deficient (r/condition-finding {:condition-threshold 5.0} 4.0))))

(deftest missing-inputs-fail-closed-to-deficient
  (is (= :deficient (r/condition-finding {} 8.0)))
  (is (= :deficient (r/condition-finding {:condition-threshold 5.0} nil)))
  (is (= :deficient (r/condition-finding {:condition-threshold 5.0} "8"))))

;; ----------------------------- critical-reading? -----------------------------

(deftest nbi-imminent-failure-is-critical
  (is (true? (r/critical-reading? :nbi-0-9 1.0)) "FHWA/AASHTO Rating 1 = Imminent Failure")
  (is (true? (r/critical-reading? :nbi-0-9 0.0)) "Rating 0 = Failed"))

(deftest nbi-above-imminent-failure-is-not-critical
  (is (false? (r/critical-reading? :nbi-0-9 2.0)))
  (is (false? (r/critical-reading? :nbi-0-9 8.0))))

(deftest pci-failed-is-critical
  (is (true? (r/critical-reading? :pci-0-100 10.0)) "ASTM D6433 0-10 = Failed")
  (is (true? (r/critical-reading? :pci-0-100 0.0))))

(deftest pci-above-failed-is-not-critical
  (is (false? (r/critical-reading? :pci-0-100 11.0)))
  (is (false? (r/critical-reading? :pci-0-100 90.0))))

(deftest unknown-scale-or-missing-reading-is-never-critical
  (is (false? (r/critical-reading? :unknown-scale 0.0)))
  (is (false? (r/critical-reading? :nbi-0-9 nil)))
  (is (false? (r/critical-reading? nil 0.0))))

;; ----------------------------- condition-rating-valid? -----------------------------

(deftest typical-nbi-rating-is-valid
  (is (r/condition-rating-valid? :nbi-0-9 0.0))
  (is (r/condition-rating-valid? :nbi-0-9 5.0))
  (is (r/condition-rating-valid? :nbi-0-9 9.0)))

(deftest excessive-nbi-rating-is-invalid
  (is (not (r/condition-rating-valid? :nbi-0-9 -1.0)))
  (is (not (r/condition-rating-valid? :nbi-0-9 55.0)))
  (is (not (r/condition-rating-valid? :nbi-0-9 9.01))))

(deftest typical-pci-rating-is-valid
  (is (r/condition-rating-valid? :pci-0-100 0.0))
  (is (r/condition-rating-valid? :pci-0-100 55.0))
  (is (r/condition-rating-valid? :pci-0-100 100.0)))

(deftest excessive-pci-rating-is-invalid
  (is (not (r/condition-rating-valid? :pci-0-100 -1.0)))
  (is (not (r/condition-rating-valid? :pci-0-100 100.01))))

(deftest unknown-scale-or-missing-rating-is-invalid
  (is (not (r/condition-rating-valid? :unknown-scale 5.0)))
  (is (not (r/condition-rating-valid? nil 5.0)))
  (is (not (r/condition-rating-valid? :nbi-0-9 nil)))
  (is (not (r/condition-rating-valid? :nbi-0-9 "5"))))

;; ----------------------------- survey-type-valid? / rating-scale-valid? -----------------------------

(deftest known-survey-types-are-valid
  (doseq [t [:pavement :bridge-deck :bridge-joint]]
    (is (r/survey-type-valid? t))))

(deftest fabricated-survey-type-is-invalid
  (is (not (r/survey-type-valid? :satellite-flyover)))
  (is (not (r/survey-type-valid? nil))))

(deftest known-rating-scales-are-valid
  (doseq [sc [:nbi-0-9 :pci-0-100]]
    (is (r/rating-scale-valid? sc))))

(deftest fabricated-rating-scale-is-invalid
  (is (not (r/rating-scale-valid? :made-up-scale)))
  (is (not (r/rating-scale-valid? nil))))

;; ----------------------------- register-condition-finding -----------------------------

(deftest finding-is-a-draft-not-a-signed-clearance
  (let [result (r/register-condition-finding "find-1" "bridge-001" :acceptable 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest finding-assigns-finding-number
  (let [result (r/register-condition-finding "find-1" "bridge-001" :deficient 7)]
    (is (= (get result "finding_number") "CF-000007"))
    (is (= (get-in result ["record" "finding_id"]) "find-1"))
    (is (= (get-in result ["record" "structure_id"]) "bridge-001"))
    (is (= (get-in result ["record" "finding"]) "deficient"))
    (is (= (get-in result ["record" "kind"]) "condition-finding-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest finding-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-condition-finding "" "bridge-001" :acceptable 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-condition-finding "find-1" "" :acceptable 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-condition-finding "find-1" "bridge-001" :maybe 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-condition-finding "find-1" "bridge-001" :acceptable -1))))

;; ----------------------------- register-reinspection -----------------------------

(deftest reinspection-is-a-draft-not-a-real-closure
  (let [result (r/register-reinspection "rei-1" "pavement-002" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest reinspection-assigns-reinspection-number
  (let [result (r/register-reinspection "rei-1" "pavement-002" 7)]
    (is (= (get result "reinspection_number") "REI-000007"))
    (is (= (get-in result ["record" "reinspection_id"]) "rei-1"))
    (is (= (get-in result ["record" "kind"]) "reinspection-schedule-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest reinspection-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-reinspection "" "pavement-002" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-reinspection "rei-1" "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-reinspection "rei-1" "pavement-002" -1))))

(deftest history-is-append-only
  (let [c1 (r/register-condition-finding "find-1" "bridge-001" :acceptable 0)
        hist (r/append [] c1)
        c2 (r/register-condition-finding "find-2" "bridge-001" :deficient 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "CF-000000" (get-in hist2 [0 "record_id"])))
    (is (= "CF-000001" (get-in hist2 [1 "record_id"])))))
