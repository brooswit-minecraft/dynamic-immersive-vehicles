# Regression test fixture (ticket MINECRAFT-503, comment 53639)

`dynamic-immersive-vehicles.json` here is a tracked snapshot of the gitignored
`run/config/dynamic-immersive-vehicles.json` operator-override file that every
real DIV_TURN_COMPARISON_PROBE/DIV_WHEEL_AUX_PROBE run in this ticket's history
has actually used (and that the real Zippy deployment also carries).

Why this exists: a fresh `git worktree add` does not bring gitignored files, so
a disposable comparison worktree with no `run/config/` silently falls back to
`Overrides.FALLBACK` - `lateralGripMultiplier` defaults to 1.0 instead of the
tuned 0.7, and `selfRightStrength` has no FALLBACK entry at all (self-righting
assist completely off). This previously caused `quadRegression-turnbrake` to
roll over in a clean checkout while the properly-configured fixture does not -
not a code regression, just a missing file (see ticket comments 53606/53632/
53638 for the full incident).

**Before running `DIV_TURN_COMPARISON_PROBE`/`DIV_WHEEL_AUX_PROBE` in any
worktree that doesn't already have `run/config/`, copy this file there
first:**

```
mkdir -p run/config
cp src/test/resources/regression-fixture/dynamic-immersive-vehicles.json run/config/
```

SHA-256 of this file: `eb472cda39f30d847b738a9363243a1181e7a758b0fed22c1dc141bd1a35a571`

Reference resolved values for `divtest:turncmp-grip7` (the quad regression
scenario's vehicle) when this fixture is in place: `massKg=350.0`,
`lateralGripMultiplier=0.7`, `selfRightStrength=400.0`,
`selfRightDamping=160.0`, `selfRightMaxTorque=1000.0`. A probe run that logs
different values for this vehicle is not using this fixture.

This is a test/regression-harness fixture only - it is not a production config
change, and the real deployed `run/config/dynamic-immersive-vehicles.json` is
untouched by this file.
