# cloud-itonami-cofog-04.5

Open COFOG Blueprint (implemented actor) for **COFOG 04.5**: Transport.

This repository publishes a forkable OSS business for an independent road and
bridge inspection contractor: a road/bridge inspection robot performs
pavement-defect and structural scans under a governor-gated actor, so a
municipal public-works department (or its contracted inspector) keeps
auditable infrastructure-condition records instead of renting a closed
asset-management SaaS.

**Maturity: `:implemented`** — InspectionAdvisor ⊣ Infrastructure
Inspection Governor as a langgraph-clj StateGraph (`intake → advise →
govern → decide → commit/hold`, human-approval interrupt), modeled on
`cloud-itonami-isic-3091`'s motorcycle-plant-operations actor (the
closest robotics-gated, propose-only-coordination sibling shape). All
source `.cljc` (portable to JVM / ClojureScript / GraalVM), no JVM-only
interop. 93 tests / 243 assertions green, `clj-kondo` 0 errors / 0
warnings.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a road/bridge inspection robot
(crack/pothole imaging, bridge deck and joint scanning) performs the
on-site survey under an actor that proposes a defect assessment and an
independent **Infrastructure Inspection Governor** that gates repair
recommendations. The governor never dispatches hardware itself;
`:safety-critical` findings (e.g. a structural defect requiring
immediate closure) always require human sign-off, regardless of the
advisor's own confidence. Condition ratings are reported on two real,
independently verified published scales: the FHWA National Bridge
Inspection Standards (23 CFR 650 Subpart C) / AASHTO Manual for Bridge
Evaluation 0-9 component-rating scale for bridge deck/joint structures,
and the ASTM D6433 Pavement Condition Index (PCI) 0-100 scale for
pavement segments.

## What this actor does

Proposes **road/bridge-infrastructure-inspection back-office
coordination**, not equipment operation or closure orders:
- `:log-inspection` — completed on-site survey data logging
  (survey-type/notes against a registered structure; administrative,
  not an operational decision)
- `:structural-scan` — an acceptable/deficient finding drafted from a
  robot's raw condition-rating sensor reading against the structure's
  own registered maintenance-trigger threshold, plus an INDEPENDENT
  `:critical?` triage flag grounded in the real external scale's own
  fixed boundary (NBI rating <= 1 'Imminent Failure'; PCI <= 10
  'Failed') that a local threshold cannot loosen
- `:escalate-structural-concern` — surface a structural defect
  requiring immediate closure (always escalates)
- `:schedule-reinspection` — propose a follow-up survey window against
  a structure with an on-file DEFICIENT finding

## What this actor does NOT do

- Does NOT actuate the inspection robot beyond passive sensing — the
  robot senses, this actor only logs/finds/schedules
- Does NOT order a real structure closure or self-report that a
  closure happened (every escalation this actor produces is an
  unsigned DRAFT; a human public-works authority's own act is what
  actually closes a structure)
- Does NOT let a proposal's own self-reported finding stand
  uncontested — the governor independently recomputes it from the raw
  condition-rating reading every time
- ONLY proposes/coordinates back-office records; all actuation and
  closure authority requires explicit human authority

## Core Contract

```text
segment/structure survey request + prior condition history
        |
        v
Inspection Advisor -> Infrastructure Inspection Governor -> report, or human sign-off
        |
        v
robot survey actions (gated) + condition record + audit ledger
```

Implemented faithfully: no automated finding can dispatch a robot
action the governor refuses, suppress a condition record, or recommend
deferring a safety-critical repair without governor approval and audit
evidence.

## Implementation

Portable `.cljc` namespaces under `src/infrainsp/`:

- `registry` — pure domain logic: structure verified/registered ground
  truth, the independent acceptable/deficient finding verdict
  (condition-rating vs a structure's own registered threshold), the
  independent `:critical?` recompute grounded in the real NBI/PCI
  external scale boundaries (never a fabricated maintenance-trigger
  number), reading-plausibility and survey-type validation, draft
  finding/reinspection-schedule record construction.
- `store` — SSoT behind a `Store` protocol (`MemStore`); structures,
  inspection records, condition-finding history, reinspection
  schedules, structural concerns and the audit ledger all live here.
- `advisor` — the contained intelligence node (`mock-advisor` default,
  `llm-advisor` swap-in); returns proposals only, grounded only in
  store facts.
- `governor` — the independent Infrastructure Inspection Governor (13
  HARD + 1 SOFT check).
- `phase` — 0→3 staged rollout; only `:log-inspection` is ever
  auto-eligible, and only at phase 3.
- `operation` — the langgraph-clj StateGraph (1 run = 1 coordination
  request); `sim` drives the offline demo.

`clojure -M:dev:test` (93 tests, 243 assertions) and `clojure -M:lint`
(clj-kondo, 0 errors). `clojure -M:dev:run` drives the demo end to end,
including every HARD-hold scenario and a real Imminent-Failure/Failed
critical-reading scenario.

## Capability layer

Resolves via [`kotoba-lang/cofog`](https://github.com/kotoba-lang/cofog)
(COFOG `04.5`). Required capabilities:

- :robotics
- :telemetry
- :forms
- :dmn
- :bpmn
- :audit-ledger

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
