(ns infrainsp.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean contractor through
  intake -> structural scan (escalate/approve) -> structural-concern
  escalation (escalate/approve) -> reinspection scheduling
  (escalate/approve), then shows HARD-hold scenarios: a mis-wired
  request whose own `:effect` is not `:propose`, an unrecognized op, a
  structural scan against an UNVERIFIED/unregistered bridge joint, a
  structural scan with an implausible condition-rating sensor reading
  for its own declared rating-scale, a reinspection scheduled against a
  structure with no on-file deficient finding, a proposal that tries to
  ACTUATE the inspection robot directly (permanently blocked, no
  override), a double-schedule of the same reinspection window, an
  inspection-log patch with a fabricated survey-type, and a proposal
  that tries to self-report a structure closure through a side channel
  (permanently blocked, no override) -- plus one CRITICAL reading
  scenario grounded in the real FHWA/AASHTO 'Imminent Failure' (NBI
  rating <= 1) boundary.

  Like every sibling actor's own demo, each check is exercised directly
  and independently below, one request per HARD-hold scenario -- the
  same 'exercise the failure mode directly, never only via a happy-path
  actuation' discipline this fleet establishes."
  (:require [langgraph.graph :as g]
            [infrainsp.store :as store]
            [infrainsp.operation :as op]))

(def coordinator {:actor-id "coord-1" :actor-role :inspection-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(defn -main [& _args]
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]

    (println "== log-inspection insp-001 on bridge-001 (clean patch -> phase-3 auto-commit) ==")
    (println (exec-op actor "t1"
                       {:op :log-inspection :effect :propose :subject "insp-001"
                        :patch {:structure-id "bridge-001" :survey-type :bridge-deck
                                :notes "デッキ目視点検、異常なし"}}
                       coordinator))

    (println "== structural-scan find-1 on bridge-001 (NBI rating 8 > threshold 5 -> :acceptable, escalates, approve) ==")
    (let [r (exec-op actor "t2"
                      {:op :structural-scan :effect :propose :subject "find-1"
                       :value {:structure-id "bridge-001" :condition-rating 8.0}}
                      coordinator)]
      (println r)
      (println "-- human public-works authority approves --")
      (println (approve! actor "t2")))

    (println "== schedule-reinspection rei-2 on bridge-001 (:acceptable on file, not :deficient -> HARD hold) ==")
    (println (exec-op actor "t9"
                       {:op :schedule-reinspection :effect :propose :subject "rei-2"
                        :value {:structure-id "bridge-001" :scheduled-date "2026-08-01"
                                :actuate-equipment? false}}
                       coordinator))

    (println "== structural-scan find-2 on bridge-001 (NBI rating 1 -> :deficient AND critical=true, FHWA/AASHTO 'Imminent Failure', still just escalates -- approve) ==")
    (let [r (exec-op actor "t2b"
                      {:op :structural-scan :effect :propose :subject "find-2"
                       :value {:structure-id "bridge-001" :condition-rating 1.0}}
                      coordinator)]
      (println r)
      (println "-- human public-works authority approves --")
      (println (approve! actor "t2b")))

    (println "== escalate-structural-concern conc-1 on bridge-001 (defect requiring immediate closure, always escalates -- approve) ==")
    (let [r (exec-op actor "t3"
                      {:op :escalate-structural-concern :effect :propose :subject "conc-1"
                       :value {:structure-id "bridge-001" :concern-type :section-loss
                               :severity :critical :description "主桁の断面欠損拡大、即時通行止め要検討"}}
                      coordinator)]
      (println r)
      (println "-- human public-works authority approves --")
      (println (approve! actor "t3")))

    (println "== schedule-reinspection rei-1 on pavement-002 (on-file DEFICIENT finding -- escalates, approve) ==")
    (let [r (exec-op actor "t4"
                      {:op :schedule-reinspection :effect :propose :subject "rei-1"
                       :value {:structure-id "pavement-002" :scheduled-date "2026-08-01"
                               :actuate-equipment? false}}
                      coordinator)]
      (println r)
      (println "-- human public-works authority approves --")
      (println (approve! actor "t4")))

    (println "\n-- HARD-hold scenarios --\n")

    (println "== log-inspection with :effect other than :propose -> HARD hold (structural) ==")
    (println (exec-op actor "t5"
                       {:op :log-inspection :effect :direct-write :subject "insp-001"
                        :patch {:structure-id "bridge-001" :survey-type :bridge-deck}}
                       coordinator))

    (println "== unrecognized op -> HARD hold ==")
    (println (exec-op actor "t6"
                       {:op :close-lane-directly :effect :propose :subject "bridge-001"}
                       coordinator))

    (println "== structural-scan find-3 on bridge-003 (UNVERIFIED/unregistered bridge joint -> HARD hold) ==")
    (println (exec-op actor "t7"
                       {:op :structural-scan :effect :propose :subject "find-3"
                        :value {:structure-id "bridge-003" :condition-rating 6.0}}
                       coordinator))

    (println "== structural-scan find-4 on bridge-001 with an implausible condition-rating for NBI 0-9 -> HARD hold ==")
    (println (exec-op actor "t8"
                       {:op :structural-scan :effect :propose :subject "find-4"
                        :value {:structure-id "bridge-001" :condition-rating 55.0}}
                       coordinator))

    (println "== schedule-reinspection rei-3 on pavement-002 with :actuate-equipment? true -> HARD hold, PERMANENT, never reaches a human ==")
    (println (exec-op actor "t10"
                       {:op :schedule-reinspection :effect :propose :subject "rei-3"
                        :value {:structure-id "pavement-002" :scheduled-date "2026-09-01"
                                :actuate-equipment? true}}
                       coordinator))

    (println "== schedule-reinspection rei-1 AGAIN (double-schedule -> HARD hold) ==")
    (println (exec-op actor "t11"
                       {:op :schedule-reinspection :effect :propose :subject "rei-1"
                        :value {:structure-id "pavement-002" :scheduled-date "2026-08-01"
                                :actuate-equipment? false}}
                       coordinator))

    (println "== log-inspection insp-002 on bridge-001 with a fabricated survey-type -> HARD hold ==")
    (println (exec-op actor "t12"
                       {:op :log-inspection :effect :propose :subject "insp-002"
                        :patch {:structure-id "bridge-001" :survey-type :satellite-flyover}}
                       coordinator))

    (println "== log-inspection insp-003 on bridge-001 attempting to self-report a closure via a side channel -> HARD hold, PERMANENT ==")
    (println (exec-op actor "t13"
                       {:op :log-inspection :effect :propose :subject "insp-003"
                        :patch {:structure-id "bridge-001" :closed? true}}
                       coordinator))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "\n== draft condition-finding records ==")
    (doseq [r (store/finding-history db)] (println r))

    (println "\n== draft reinspection records ==")
    (doseq [r (store/reinspection-history db)] (println r))))
