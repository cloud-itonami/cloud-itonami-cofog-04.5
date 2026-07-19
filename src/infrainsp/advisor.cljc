(ns infrainsp.advisor
  "InspectionAdvisor -- the *contained intelligence node* for the
  road/bridge-infrastructure-inspection back-office coordination actor.

  It normalizes inspection-log patches (survey-type/notes), drafts a
  condition-finding decision from a robot's raw condition-rating sensor
  reading, drafts a structural-concern escalation, and drafts a
  reinspection scheduling proposal against a structure. CRITICAL: it is
  a smart-but-untrusted advisor. It returns a *proposal* (with a
  rationale + the fields it cited), never a committed record and NEVER
  a real robot actuation beyond passive sensing or a structure closure
  -- see README `What this actor does NOT do`. Every output is censored
  downstream by `infrainsp.governor` before anything touches the SSoT.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- informational only, NOT trusted
                                 ; by the governor for any ground-truth
                                 ; check (see `infrainsp.governor`)
     :cites      [kw|str ..]    ; fields the advisor used
     :effect     kw             ; how a commit would mutate the SSoT --
                                 ; ALWAYS one of the closed
                                 ; #{:inspection-record/upsert
                                 ; :condition-finding/decide
                                 ; :concern/escalate
                                 ; :reinspection/schedule} propose-shaped
                                 ; effects, NEVER a direct
                                 ; hardware/closure-control effect
     :stake      kw|nil         ; :coordination/structural-concern | nil
     :confidence 0..1}

  CRITICAL invariant this advisor upholds: every request it is asked to
  route MUST itself carry `:effect :propose` (the request-level
  contract every caller of this actor agrees to) -- `infrainsp.governor`
  HARD-holds any request that doesn't, so a mis-wired caller can never
  reach a commit path even if this advisor were compromised."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [infrainsp.registry :as registry]
            [infrainsp.store :as store]
            [langchain.model :as model]))

(defn- log-inspection
  "Inspection-log intake upsert -- the advisor only normalizes/
  validates the patch; it does not invent the survey-type or notes.
  High confidence, low stakes -- administrative logging, not an
  operational decision."
  [_db {:keys [patch]}]
  {:summary    (str "現地調査記録更新: " (pr-str (keys patch)))
   :rationale  "入力patchの正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :inspection-record/upsert
   :value      patch
   :stake      nil
   :confidence 0.95})

(defn- structural-scan
  "Draft an acceptable/deficient finding from a robot's raw
  condition-rating sensor reading against a structure. The advisor
  reports what it can see (structure verified?/registered?, the
  independently computed finding, whether the reading crosses the real
  external critical boundary) in its rationale, but
  `infrainsp.governor` NEVER trusts this report -- it independently
  re-derives verified?/registered?, the acceptable/deficient finding,
  and the critical flag from the structure's own stored threshold and
  declared rating-scale before any commit is possible. This mock
  advisor itself always reports the honestly-recomputed finding (it
  has no incentive to lie); the governor's own independent recompute
  exists for the LLM-advisor path and any compromised/hallucinating
  advisor."
  [db {:keys [subject value]}]
  (let [structure-id (:structure-id value)
        condition-rating (:condition-rating value)
        st-structure (store/structure db structure-id)
        ready? (and st-structure (registry/structure-ready? st-structure))
        finding (when st-structure (registry/condition-finding st-structure condition-rating))
        rating-scale (:rating-scale st-structure)
        critical? (and st-structure (registry/critical-reading? rating-scale condition-rating))]
    {:summary    (str subject " 向け構造物状態判定提案 (condition-rating=" condition-rating ")"
                      (when st-structure (str " structure=" structure-id)))
     :rationale  (if st-structure
                   (str "structure-verified?=" (registry/structure-verified? st-structure)
                        " structure-registered?=" (registry/structure-registered? st-structure)
                        " rating-scale=" rating-scale
                        " threshold=" (:condition-threshold st-structure)
                        " finding=" finding
                        " critical?=" critical?)
                   (str structure-id " が見つかりません"))
     :cites      (if st-structure [structure-id] [])
     :effect     :condition-finding/decide
     :value      (assoc value :finding (or finding :deficient))
     :stake      nil
     :confidence (if (and ready? (registry/condition-rating-valid? rating-scale condition-rating)) 0.9 0.3)}))

(defn- escalate-structural-concern
  "Draft a structural-concern escalation requiring immediate closure
  consideration (the exact scenario README `Robotics premise` names).
  ALWAYS `:stake :coordination/structural-concern` -- a structural
  concern is NEVER a proposal the advisor may quietly downgrade to
  low-stakes, and it is never gated on the referenced structure being
  verified (a concern can be raised about ANY structure, verified or
  not -- see README `What this actor does NOT do` re: never blocking
  safety-relevant reporting on an administrative technicality). See
  `infrainsp.phase`: no phase ever adds this op to a phase's `:auto`
  set; `infrainsp.governor` also always escalates on
  `:coordination/structural-concern`. Two independent layers agree,
  deliberately."
  [db {:keys [subject value]}]
  (let [structure-id (:structure-id value)
        st-structure (and structure-id (store/structure db structure-id))]
    {:summary    (str subject " 向け構造的懸念エスカレーション (" (:concern-type value) ")"
                      (when st-structure (str " structure=" structure-id)))
     :rationale  (str "concern-type=" (:concern-type value)
                      " severity=" (:severity value)
                      " description=" (:description value))
     :cites      (if st-structure [structure-id] [])
     :effect     :concern/escalate
     :value      value
     :stake      :coordination/structural-concern
     :confidence 0.9}))

(defn- schedule-reinspection
  "Draft a reinspection scheduling proposal against a structure with an
  on-file DEFICIENT finding. The advisor reports the structure's own
  on-file finding status in its rationale, but `infrainsp.governor`
  NEVER trusts it: it independently re-derives the structure's own
  `:last-finding` before any commit is possible."
  [db {:keys [subject value]}]
  (let [structure-id (:structure-id value)
        st-structure (store/structure db structure-id)
        ready? (and st-structure (registry/structure-ready? st-structure))
        deficient? (and st-structure (= :deficient (:last-finding st-structure)))]
    {:summary    (str subject " 向け再調査予定提案"
                      (when st-structure (str " structure=" structure-id)))
     :rationale  (if st-structure
                   (str "structure-verified?=" (registry/structure-verified? st-structure)
                        " structure-registered?=" (registry/structure-registered? st-structure)
                        " last-finding=" (:last-finding st-structure)
                        " actuate-equipment?=" (boolean (:actuate-equipment? value)))
                   (str structure-id " が見つかりません"))
     :cites      (if st-structure [structure-id] [])
     :effect     :reinspection/schedule
     :value      value
     :stake      nil
     :confidence (if (and ready? deficient? (not (:actuate-equipment? value))) 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :effect :propose :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :log-inspection                 (log-inspection db request)
    :structural-scan                (structural-scan db request)
    :escalate-structural-concern    (escalate-structural-concern db request)
    :schedule-reinspection          (schedule-reinspection db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは道路・橋梁インフラ現地調査コーディネーターの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。"
       "説明や前置きは一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:inspection-record/upsert|:condition-finding/decide|"
       ":concern/escalate|:reinspection/schedule) "
       ":stake(:coordination/structural-concern か nil) :confidence(0..1)。\n"
       "重要: 未検証または未登録の構造物に対する作業を提案してはいけません。"
       "検査ロボットの受動的センシングを超える直接操作(actuate)を絶対に提案してはいけません"
       "(この actor は提案のみを行い、実行は一切行いません)。"
       "condition-ratingセンサー読取値と矛盾する判定(:finding)を報告してはいけません。"
       "構造物の閉鎖(closure)を自己申告する提案をしてはいけません。"))

(defn- facts-for [st {:keys [op value]}]
  (case op
    :log-inspection                {}
    :structural-scan                {:structure (store/structure st (:structure-id value))}
    :escalate-structural-concern    {:structure (and (:structure-id value)
                                                      (store/structure st (:structure-id value)))}
    :schedule-reinspection          {:structure (store/structure st (:structure-id value))}
    {}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so `infrainsp.governor`
  escalates/holds -- an LLM hiccup can never auto-decide a condition
  finding, auto-escalate a structural concern, or auto-schedule a
  reinspection."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :infrainsp-advisor-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
