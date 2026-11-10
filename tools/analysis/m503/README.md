# MINECRAFT-503 forensic analysis scripts

One-off Python analysis over real captured `-Ddiv.debugGroundContact=true` log text (not part of
the Gradle build, not a dependency of any production code - standalone scripts, run with a plain
`python3` + `numpy`). Published per ticket review's explicit request (comment 53019) so the
results below are independently reproducible, not just prose claims.

## Files

- `raw_capture_excerpt.txt` - the real captured diagnostic log text, pasted into Jira as plain
  text across comments 52984/52985/52988-53007 (ticket's attachment-download tooling gap), for
  two real placements of `mtsofficialpack:quad` (entities `4b381e31-...` and `b4ec442c-...`),
  ticks 0-30, server side, fully undriven (drive=0, braking=false throughout this range).
- `torque_closure_check.py` - parses the log and, for every real tick-to-tick transition on both
  entities, predicts the next tick's `angularVelocity` from the real per-wheel impulses (summed
  `point x impulse`) and a solid-box inertia tensor derived from the chassisReceipt's own
  half-extents/mass, then compares to the actually logged value.
  **Result: matches to ~1e-5 rad/s absolute error on every single transition (30 ticks, both
  entities)** - the rigid-body impulse/inertia integration itself is exact; it is not the bug.
- `energy_accounting.py` - the same idea extended to kinetic energy, decomposing each tick's
  energy change into gravity/vertical/tire contributions plus the cross-coupling term between
  them (closes to ~1e-3 J against the real logged values). Shows net scalar KE is not
  monotonically increasing (dominated early by the vehicle still falling, and large cross terms
  that swing both ways) - **slip velocity (sideSpeed), not scalar KE, is the unambiguous
  monotonic signal**: it grows every tick from spawn with zero decay, and by tick 5 the tire
  lateral force is pinned exactly at `friction*normal` (measured ratio 1.000), alternating sign.

## Running

```
cd tools/analysis/m503
python3 torque_closure_check.py
python3 energy_accounting.py
```

## Relationship to the Java experiment

`src/main/java/io/github/brooswitminecraft/div/experimental/MultiContactFrictionSolver.java` and
its paired test implement and replay one attempted fix (a bounded multi-contact friction solve)
informed by this analysis. See that class's own doc for the three variants tried and why two were
reverted; see the test's doc for what its own replay currently shows (a measurable but incomplete
improvement) and the known simplification (fixed, non-raycast normal forces) that leaves the
question of whether the real game's suspension/load-transfer coupling changes that picture still
open as of ticket comment 53019.
