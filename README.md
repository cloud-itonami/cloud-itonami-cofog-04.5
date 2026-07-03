# cloud-itonami-cofog-04.5

Open COFOG Blueprint for **COFOG 04.5**: Transport.

This repository designs a forkable OSS business for an independent road and
bridge inspection contractor: a road/bridge inspection robot performs
pavement-defect and structural scans under a governor-gated actor, so a
municipal public-works department (or its contracted inspector) keeps
auditable infrastructure-condition records instead of renting a closed
asset-management SaaS.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a road/bridge inspection robot
(crack/pothole imaging, bridge deck and joint scanning) performs the
on-site survey under an actor that proposes a defect assessment and an
independent **Infrastructure Inspection Governor** that gates repair
recommendations. The governor never dispatches hardware itself;
`:high`/`:safety-critical` findings (e.g. a structural defect requiring
immediate closure) require human sign-off.

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

No automated finding can dispatch a robot action the governor refuses,
suppress a condition record, or recommend deferring a safety-critical
repair without governor approval and audit evidence.

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
