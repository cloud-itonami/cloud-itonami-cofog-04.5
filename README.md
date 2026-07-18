# cloud-itonami-cofog-04.5

Open COFOG Blueprint for **COFOG 04.5**: Transport.

This repository designs a forkable OSS business for an independent road and
bridge inspection contractor: a road/bridge inspection robot performs
pavement-defect and structural scans under a governor-gated actor, so a
municipal public-works department (or its contracted inspector) keeps
auditable infrastructure-condition records instead of renting a closed
asset-management SaaS.

**Status: design blueprint, no code implemented yet.** This repository
has zero files under `src/` and no `test/` directory — the Inspection
Advisor and Infrastructure Inspection Governor described below do not
exist in code. It is not (yet) a governed Advisor⊣Governor actuation
actor; the Core Contract section specifies what that pipeline is
intended to enforce once built, not current behavior. See
[`cloud-itonami-isco-1324`](https://github.com/cloud-itonami/cloud-itonami-isco-1324)
for this fleet's minimal implemented reference (`actor`/`advisor`/
`governor`/`store`), and the `cloud-itonami-assoc-*` /
`cloud-itonami-municipality-*` / `cloud-itonami-lei-*` repos for this
fleet's honest not-an-actuation-actor disclaimer pattern.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a road/bridge inspection robot
(crack/pothole imaging, bridge deck and joint scanning) performs the
on-site survey under an actor that proposes a defect assessment and an
independent **Infrastructure Inspection Governor** that gates repair
recommendations. The governor never dispatches hardware itself;
`:high`/`:safety-critical` findings (e.g. a structural defect requiring
immediate closure) require human sign-off.

## Core Contract (design intent — not yet implemented)

```text
segment/structure survey request + prior condition history
        |
        v
Inspection Advisor -> Infrastructure Inspection Governor -> report, or human sign-off
        |
        v
robot survey actions (gated) + condition record + audit ledger
```

**No code exists yet in this repo** — no `src/`, no `test/`, only this
design document plus `blueprint.edn` and `docs/`. Once built, no
automated finding will be able to dispatch a robot action the governor
refuses, suppress a condition record, or recommend deferring a
safety-critical repair without governor approval and audit evidence —
but none of that is enforced today.

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
