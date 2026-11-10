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
- `energy_accounting.py` - the same idea extended to kinetic energy: decomposes each tick's
  energy change into gravity/vertical/tire contributions plus the cross-coupling term between
  them, using the SAME quadratic-impulse/cross-coupling formalism as `torque_closure_check.py`
  (gravity included as a third simultaneous impulse source with zero torque - an earlier,
  mistakenly-committed version of this file omitted that term and its numbers never closed; see
  ticket comment 53023). The script asserts its own closure residual per tick-transition and
  prints a coverage/residual summary at the end rather than leaving that to be taken on faith.
  **Result: closes to within ~5e-3 J of the actually logged kinetic energy on every covered
  transition (18 transitions, both entities, asserted in-script).** Shows net scalar KE is not
  monotonically increasing (dominated early by the vehicle still falling, and large cross terms
  that swing both ways) - **slip velocity (sideSpeed), not scalar KE, is the unambiguous
  monotonic signal**: it grows every tick from spawn with zero decay, and by tick 5 the tire
  lateral force is pinned exactly at `friction*normal` (measured ratio 1.000), alternating sign.
  Coverage and tolerance are asserted in-script (comment 53025), not just claimed: it requires
  exactly the two expected entities and the full contiguous tick 0-30 range for both (60
  transitions total, not a truncated subset), explicitly reports the two isolated out-of-range
  ticks (61, 179 - later braking/parkingBrake snapshots with no tick+1 pair in this file) as
  excluded rather than silently skipping them, and fails loudly if any transition's closure
  residual exceeds 0.01 J. Measured worst residual over the full 60 transitions: 0.0053 J.

## Running

```
cd tools/analysis/m503
python3 torque_closure_check.py
python3 energy_accounting.py   # asserts its own closure residuals; exits nonzero if any fail
```

## Relationship to the Java experiment

`src/main/java/io/github/brooswitminecraft/div/experimental/MultiContactFrictionSolver.java` and
its paired tests implement and replay one attempted fix (a bounded multi-contact friction solve)
informed by this analysis. See that class's own doc for the three variants tried and why two were
reverted. Review (comment 53022) found and this fixed two further bugs: an accumulation bug in
the solver itself (the lateral target was replacing, not adding to, the running per-contact
total - `MultiContactFrictionSolverConvergenceTest` is the regression for that), and two
coordinate/basis bugs in the whole-vehicle replay test (world-oriented points reused as
body-local; fixed world-up instead of the body's own rotated up-axis). With both fixed, the
replay (`MultiContactFrictionSolverExperimentTest`) now shows the solver substantially resolving
the instability in that simplified (still fixed, non-raycast, normal-force) harness - not yet the
faithful production-path reproduction (real suspension/load-transfer coupling) review asked for,
which is the next piece.
