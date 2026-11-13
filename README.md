# Dynamic Immersive Vehicles

Independent experimental companion for Immersive Vehicles 24.0.0, Sable 2.0.5,
NeoForge 21.1.265, and Minecraft 1.21.1. No upstream source, assets, or dependency
jars are modified, copied, bundled, or published here.

> **Unverified prototype:** hosted compilation and focused tire-math/
> classification tests are separate from in-game acceptance. Do not deploy
> this build to an existing world until the runtime checklist in
> [VERIFICATION.md](VERIFICATION.md) passes.

## Scope

Companion physics now applies to every eligible IV vehicle **by default**,
across content packs, with no per-car registration or opt-in. It classifies
each vehicle from IV's own definition at the moment it is needed:

- **Ground vehicles (wheeled or tracked), any wheel/track count.** Not just
  four-wheel cars: one wheel, three wheels, six wheels, and tracked vehicles
  all map their installed ground-contact devices, vehicle mass, and chassis
  bounds to one server-owned Sable rigid body with raycast spring/damper
  suspension and combined tire/track grip. A track-steered vehicle
  (`hasSkidSteer`) turns by differential track force instead of a steerable
  wheel. IV remains responsible for models, seats, controls, persistence, and
  movement packets.
- **Fixed-wing aircraft and blimps** (`motorized.isAircraft` / `isBlimp`,
  with no installed rotor propeller): a server-owned Sable rigid body using a
  separate flight force model instead of the ground solver - wing lift
  (thin-airfoil lift slope with a hard stall cutoff), parasitic+induced drag,
  aileron/elevator/rudder control torques, and throttle-linear thrust from
  any running engine. A blimp with a configured, nonzero `ballastVolume`
  additionally gets mass/gravity-compatible neutral-buoyancy lift plus
  ballast trim - gated and scaled the way IV's own ballast system is, not a
  literal displaced-air calculation on that field's raw number (which is
  normally `emptyMass / 1000`, a small control-authority number, not an SI
  volume sized to displace the vehicle's mass) and not tied to
  `gravityFactor`, whose own IV runtime default actually resolves to full
  gravity when a pack leaves it unset. A blimp with no configured
  `ballastVolume` gets ordinary gravity, matching IV. See
  [`FlightForces`](src/main/java/io/github/brooswitminecraft/div/FlightForces.java)
  for the exact model and its stated simplifications (no post-stall/spin
  behavior, no advance-ratio propeller falloff, no altitude/weather air
  density, no natural weathercock stability beyond rudder input). No wheel or
  track device is required for this category to be eligible.
- **Rotorcraft** (any vehicle with an installed `isRotor` propeller, e.g. a
  helicopter - checked directly against the installed part, independent of
  the `motorized.isAircraft`/`isBlimp` flag, so a rotor-carrying vehicle with
  neither flag set is still eligible as a rotorcraft rather than being
  deactivated or stuck on the ground solver): a server-owned Sable rigid body
  using a separate rotor force model instead of the fixed-wing model above,
  which has no wing for a rotorcraft to generate lift from - collective-pitch
  vertical thrust from throttle, cyclic tilt from elevator/aileron input that
  steers that thrust vector forward/sideways for the hover-to-forward-flight
  transition and separately drives attitude (roll/pitch) torque, and
  rudder-driven yaw/anti-torque authority. Unlike a fixed-wing control
  surface, this attitude and yaw authority scales with available thrust, not
  airspeed, so a helicopter keeps full control hovering at zero airspeed.
  See
  [`RotorForces`](src/main/java/io/github/brooswitminecraft/div/RotorForces.java)
  for the exact model and its stated simplifications (no translational-lift
  efficiency gain in forward flight, no retreating-blade stall, no
  autorotation/engine-out descent, no ground effect, and every installed
  rotor propeller treated as one lumped main lift rotor rather than modeling
  an individual tail rotor's own thrust/mount orientation). No wheel or track
  device is required for this category to be eligible, and an installed
  rotor always takes priority over wheels/tracks for dispatch (a wheeled
  rotor-equipped vehicle still gets rotor physics, not the ground solver).
- **Boats and other float-only hulls** (at least one installed ground device
  with `canFloat`, and no wheel or track, and no installed rotor propeller).
  Each installed float device gets its own Archimedes buoyant force and
  quadratic water drag, sampled at that device's own position against the
  local Minecraft water surface; a hull resting unevenly in the water is
  righted by that per-device force distribution rather than a separate
  stability calculation. An optional, configurable rotational water-drag
  coefficient (`angularWaterDrag`, MINECRAFT-518) additionally damps the
  hull's own roll/pitch/yaw directly - a dedicated stability knob on top
  of (never a replacement for) that per-float righting torque, applied as
  a direct, implicit (unconditionally stable - never overshoots or
  reverses the hull's spin, regardless of the configured value) velocity
  retention rather than a force, defaulting to 0 (no change from prior
  behavior) unless configured. Installed non-rotor `PartPropeller`s add forward/
  reverse thrust, steered by the rudder input, allocated per actual
  engine-to-propeller linkage rather than one vehicle-wide signal applied to
  every installed propeller. IV exposes neither that linkage nor a
  propeller's live RPM through its public API, so this bridge reads them the
  same way it already reads `EntityVehicleF_Physics`'s own protected
  `getRecursiveTowingThrust()` for towing (see
  [`PartEngineAccessor`](src/main/java/io/github/brooswitminecraft/div/mixin/PartEngineAccessor.java)/
  [`PartPropellerAccessor`](src/main/java/io/github/brooswitminecraft/div/mixin/PartPropellerAccessor.java),
  version-pinned `@Accessor` Mixins, not an edit to IV's own source). Thrust
  direction comes from the propeller's own public `desiredLinearVelocity`
  (driven by `currentPitch` - a fixed sign for a static-pitch propeller, or
  one swung between forward and reverse by `vehicle.reverseThrustVar` for a
  dynamic-pitch one), never from engine transmission gear state: a propeller
  has no gearbox of its own, so an engine sitting in neutral still drives a
  turning, correctly-pitched propeller, and a dynamic-pitch propeller
  reversed via `reverseThrustVar` thrusts backward even while the
  transmission itself stays in a forward gear (see
  [`Buoyancy.signedPropellerDrive`](src/main/java/io/github/brooswitminecraft/div/Buoyancy.java)).
  A propeller thrusts only while it is underwater, not destroyed/removed
  (`isActiveVar`), not a spare, actually turning (`currentRPM` away from
  zero), and linked to a currently-running engine - an unpowered,
  disconnected, or spare propeller produces no thrust regardless of
  throttle, submersion, or any other engine's signal. The shared per-car
  `propellerForce` budget is split only across the non-rotor propellers
  actually operational this tick (linked, active, turning) - a vehicle with
  one powered propeller and one unpowered one gives the powered propeller
  the full configured thrust, not half of it just because a second,
  unpowered propeller also happens to be installed.
- **Amphibious and float-equipped flight/rotor vehicles** (a drivable wheel/
  track device, a fixed-wing/blimp flag, or a rotor propeller, AND at least
  one `canFloat` device on the same chassis - an amphibious car, a seaplane,
  or a floatplane helicopter). These get *both* domains' forces in the same
  tick, not one at the expense of the other: the ground/flight/rotor forces
  above, plus the same per-float buoyancy/water-drag/underwater-propeller-
  thrust forces applied to their float devices (a rotor propeller's own
  thrust is never double-counted here, since it is excluded from this
  underwater-propeller-thrust pass - see
  [`BuoyantForces`](src/main/java/io/github/brooswitminecraft/div/BuoyantForces.java)).
  There is no separate "amphibious" classification - this is the same ground/
  flight/rotor domain from the bullets above, with buoyancy layered on top
  whenever a float device is present on the chassis.

Known, deliberate exclusions - not silently skipped, each tracked as a
follow-up task rather than faked with an unrelated force model:

- **A towed boat or other float-only hull** (an actively-hitched trailer
  whose own category is the float-only one, not ground/flight/rotorcraft):
  it keeps following IV's own native hitch code instead of getting a hitch
  joint, because `BuoyantBody` (see `VehicleBody`, deliberately minimal)
  exposes no pose/anchor API a `HitchJoint` could connect to - a concrete,
  citable API gap, not an invented restriction. A towed ground, flight, or
  rotorcraft vehicle has no such gap and is jointed normally; see "Trailer/
  hitch coupling" below.
- A vehicle whose JSON definition is missing or has a chassis with no finite
  collision volume is left on IV's native physics rather than crashing its
  tick; this is logged once per definition, not per vehicle instance.

## Trailer/hitch coupling

A vehicle actively hitched behind another (a trailer) is eligible for its
own Sable body on the same terms as any other vehicle **in its own
category** - ground, flight, or rotorcraft, all three sharing one
`PhysicsBridge.Body` implementation - jointed to its towing vehicle's body by
a Sable `GenericConstraintConfiguration` hitch (see `HitchJoint` for the
anchor-position/locked-axis math, `HitchRegistry` for the testable joint-
lifecycle bookkeeping - create/replace/release, by key - and
`PhysicsBridge.createHitch`/`reconcileHitch` for how those two are wired to
a live entity/Sable body), rather than IV's native hitch position-snap. The
joint math itself is domain-agnostic - it only connects two already-
classified bodies by their own `JSONConnection.pos` anchors - so a towed
aircraft/blimp or rotorcraft is jointed exactly the same way a towed ground
vehicle is; a towed boat is excluded instead because its `BuoyantBody` has
no pose/anchor API for the joint to connect to (see the exclusions list
above), not because of anything specific to towing. The joint anchors each
body at its own
`JSONConnection.pos` (the hitch/hookup points IV's own connection group
already defines), always locks the three linear axes (the two anchor points
stay coincident - a hitch is not a sliding coupling), and locks rotation
according to the connection's own flags: a `mounted` connection (e.g. a
flatbed) is fully rigid, a `restricted` one allows only pitch (its own
documented contract), and an ordinary connection leaves all rotation free -
a real ball/pintle hitch, with the towed vehicle's own suspension/
aerodynamics (the same ground/flight solver every other vehicle in that
category uses) keeping it stable rather than the joint.

A towing vehicle's own replicated towing-thrust compensation impulse
(`TowingForces`, its own math unchanged) is still applied on top of this,
so towing is still not lighter than it should be on that axis - but what
gets summed into it changed: `PhysicsBridge.unjointedTowingThrust`
replicates IV's own `getRecursiveTowingThrust` recursion (and its
`mounted`-connection skip) only for a towed vehicle that does **not**
already have its own active hitch joint. A jointed trailer's own engine
thrust is applied directly to its own Sable body by its own step and
already reaches the towing vehicle mechanically through the joint Sable
itself solves; compensating for it again at the towing vehicle would propel
a powered trailer twice (`PhysicsBridge.compensableConnection` is the exact,
unit-tested per-connection decision).

**Ordering dependency, not a classification exclusion:** a towed vehicle
only gets its own body once its towing vehicle already has one active - the
joint has to connect to a live body, not to IV's raw position. This is
normally a same-tick (at most one-tick) wait, not a permanent exclusion;
until it resolves, the towed vehicle stays on IV's native hitch code exactly
as before, so there is never a window with both a snap and a Sable body
moving it (`VehicleForcesMixin` only cancels IV's native forces once
`PhysicsBridge.active()` actually returns true for that entity). Detaching a
towed vehicle (or its towing vehicle losing its own body - opt-out, despawn,
chunk unload) releases the joint without leaking it or touching a body Sable
has already destroyed; the towed vehicle itself keeps its own body and
simply becomes an unjointed, free vehicle in its own category, the same as
any other.

## Wheel/joystick input (G29 and similar)

Steering-wheel/pedal/shifter support (ticket MINECRAFT-504) is **entirely
native to Immersive Vehicles** - this project adds no input-handling code,
keybind GUI, or parallel binding system. Verified by decompiling the exact
pinned installed artifact (`curse.maven:minecraft-transport-simulator-286703:7926606`,
IV 24.0.0), not just upstream source (which can be ahead of what is actually
pinned/installed):

> **Current installed state (ticket comment 53549): capability confirmed,
> binding/calibration still pending.** The running profile's
> `controlsettings.json` has no joystick assignments and `useShifter=false` -
> native support existing in the mod is a different fact from a G29 actually
> being bound on this install. Treat the steps below as what to configure,
> not as something already done.

- **Bind it in IV's own controls GUI/`controlsettings.json`**, the same way
  as any other joystick: per-axis min/max travel, inversion, and a global
  deadzone for `CAR_TURN` (steering), `CAR_GAS` and `CAR_BRAKE` (separate
  analog pedals - a combined pedal works too, just bind both to the same
  axis), and digital shift buttons `CAR_SHIFT_1`-`CAR_SHIFT_R` (H-pattern,
  gated by IV's own `useShifter` setting) or `CAR_SHIFT_U`/`CAR_SHIFT_D`
  (sequential up/down - this is what wheel **paddles** should be bound to:
  each is independently joystick-button-bindable and edge-triggered, exactly
  the "paddle shifts one gear per press" behavior, not the old
  `dynamic-vehicles` prototype's different test-time paddle actions, which
  this project does not reuse).
- **Keyboard/gamepad coexistence and reconnect are both automatic**: every
  bound control falls back to keyboard when no matching joystick is present
  and re-engages the joystick the instant `isJoystickPresent` sees it again
  (polled fresh every tick - there is no persisted "was connected" state that
  can go stale across a disconnect).
- **The one real gap** (not an IV limitation - a loss internal to this
  project): `PhysicsBridge.stepGround` used to collapse the brake pedal to a
  boolean (`v.brakeVar.isActive`) before applying `brakeForce`, discarding
  the real 0-1 analog pedal strength IV's own `brakeVar.currentValue` already
  carries from the wheel's brake axis. Fixed: the full analog magnitude now
  scales the brake force cap proportionally (full press and no press are
  byte-identical to the old boolean behavior; partial presses now stop
  proportionally less than a full press instead of clamping to on/off).
  Parking brake has no pedal-strength axis of its own and forces full (1.0)
  magnitude, same as before.

**Device-tested vs. automated verification**: the native IV binding/
deadzone/inversion/reconnect/sequential-shift behavior above is standard,
widely-used IV functionality and is not re-tested by this project (nothing
in this repository's code could regress it); the analog brake-magnitude fix
has automated unit coverage (`MultiContactFrictionSolverBrakingTest`) proving
proportional behavior and exact parity with the old boolean at both
boundaries. Actually driving with a physical G29 - confirming the paddle
bindings feel right and the wheel reconnects cleanly in practice - is
John's own device-level verification; this project has no CI hardware to
exercise a real wheel.

### Comprehensive G29 + shifter control map (all-native, no DIV code)

Requested scope (ticket comment 53557): map as many of IV's existing car
controls to the wheel's inputs as practical, with a separate H-pattern
shifter and (eventually) a handbrake. Everything below is IV's own control,
bound through its controls GUI/`controlsettings.json` exactly like the pedals
above - there is nothing here for this project to build or ship. Zippy's own
installed profile currently shows **no G29/shifter/handbrake device present**
(ticket comment 53557's device inventory: historical G29/PS4/PS3 compat IDs
all `Present=false`, 83 joystick control entries all unassigned,
`buttonIndex=0` placeholders are not real bindings) - the table below is what
to bind once the wheel is actually connected and its real axis/button indices
are read from IV's controls GUI; **do not guess indices from the historical
IDs**, and do not treat this table as "already configured."

| Car control | IV control(s) | Suggested G29 input |
|---|---|---|
| Steering | `CAR_TURN` | Wheel rotation axis |
| Throttle | `CAR_GAS` | Gas pedal axis |
| Brake (analog, this ticket's fix) | `CAR_BRAKE` | Brake pedal axis |
| Clutch | *(no native IV control)* | not mapped - IV has no clutch input to bind it to |
| Gear up/down (**paddles - required**) | `CAR_SHIFT_U` / `CAR_SHIFT_D` (native, `useShifter=false`) | Left/right paddle buttons |
| Gear select (H-pattern, works alongside paddles) | `CAR_SHIFT_1`-`CAR_SHIFT_9`/`CAR_SHIFT_R` (read directly by this project's `WheelAuxInput` - see below) | Shifter's own per-gear switches |
| Parking brake (digital toggle) | `CAR_PARK` | A spare wheel/shifter button |
| Horn | `CAR_HORN` | A spare wheel button |
| Headlights | `CAR_LIGHTS` | A spare wheel button |
| Turn signals | `CAR_TURNSIGNAL_L` / `CAR_TURNSIGNAL_R` | Wheel's turn-signal stalk/buttons, if present |
| Dashboard/instrument panel | `CAR_PANEL` | A spare wheel button |
| Radio | `CAR_RADIO` | A spare wheel button |
| Camera look / change view / zoom | `CAR_LOOK_*`, `CAR_CHANGEVIEW`, `CAR_ZOOM_I`/`CAR_ZOOM_O` | Wheel's view hat/buttons, if present |
| Joystick input inhibit (disable joystick control temporarily) | `CAR_JS_INHIBIT` | A spare button, optional |

Deliberately **not** mapped: `CAR_GUN_FIRE`/`CAR_GUN_SWITCH` (weapon controls -
"do not map weapons/custom actions blindly" per the ticket; a driving wheel
has no sensible weapon-trigger affordance and nothing in this ticket's scope
calls for one).

### Hybrid H-pattern + paddle shifting, and an analog handbrake (DIV additions)

Two things native IV genuinely cannot do on its own (ticket comment 53577,
John explicit: "they should work together", not merely pick one mode).
Both are small, targeted server+client additions - not a parallel full
control stack - configured via a new `wheel` section in this mod's own
CLIENT config.

- **H-pattern direct-select and paddle sequential-shift simultaneously.**
  IV's own `useShifter` setting is a single exclusive toggle (reading
  `ControlSystem.controlGroundVehicle` directly: `useShifter=true` runs only
  the H-pattern `CAR_SHIFT_1`-`R` branch, `useShifter=false` runs only the
  paddle/sequential `CAR_SHIFT_U`/`CAR_SHIFT_D` branch - never both). Fixed
  with `useShifter=false` (so native's own paddle handling keeps working,
  completely unmodified) plus `HPatternShifter`/`WheelAuxInput`/
  `PhysicsBridge.advanceWheelAuxInputs`: this project reads the H-pattern's
  own `CAR_SHIFT_1`-`R` bindings *directly* (client-side), independent of
  `useShifter`, and drives `PartEngine`'s public `shiftUp()`/`shiftDown()`/
  `shiftNeutral()` itself, server-side, one step per server tick for as
  long as a climb toward the received target is outstanding - not only
  inside the packet handler, since a client only sends a NEW packet when
  the target actually changes, and a multi-gear climb needs several ticks
  against that same stored target (an earlier version of this stalled after
  one gear for exactly that reason). Native's own `shiftSelectionVar`
  (which only `useShifter=true` touches) is never written to, so there is
  nothing left to fight over.
  - Climbs in **both directions** (up and down) toward a forward gear, and
    toward the single reverse slot via neutral, matching `PartEngine.
    shiftUp()`/`shiftDown()`'s own signed-integer gear stepping exactly - no
    separate direction logic.
  - A held H-pattern slot never re-asserts itself once reached, so an
    independent paddle shift that moves `currentGear` away from it is never
    silently reverted back (`HPatternShifter.Tracker` latches "already
    reached" per target value, only resuming a climb when the shifter moves
    to a genuinely different slot) - this is also what keeps native's own
    `simpleThrottle` auto-reverse from fighting a held slot. See
    `HPatternShifterTest` for both scenarios directly.
  - A paddle (or any other external cause) acting **mid-climb**, before the
    H-pattern target is reached, cancels the outstanding climb outright
    instead of being fought (ticket comment 53617): `Tracker.nextAction`
    tracks the gear it expects after its own last issued action
    (`expectedGear`); if the observed gear differs from that expectation and
    the target has not been reached yet, the climb is treated as superseded
    and goes silent on that target until the shifter moves to a genuinely
    new slot. A safety-refused shift (`PartEngine.shiftUp()`/`shiftDown()`
    declining without changing the gear) is distinguished from this via
    `Tracker.recordOutcome(false)` - a refusal retries next tick, it is
    never mistaken for an external intervention.
  - `currentHPatternTarget` distinguishes "no H-pattern device configured
    or present at all" (→ disabled, no action) from "configured, present,
    and genuinely reading released" (→ neutral) - the first case matters
    because `hybridShiftEnabled` defaults on, and a keyboard-only player
    (the common case) must never have their vehicle forced into neutral on
    mount just because no shift button happens to be pressed. The device
    sample is validated for consistency across every CONFIGURED H-pattern
    channel specifically, not merely "any channel active" - if even one
    configured button's device is absent, the whole sample is treated as
    disabled rather than partially trusted. Each configured channel's bound
    index is also checked against `getJoystickComponentCount` AND
    `isJoystickComponentAxis` before ever reading it (the same discipline
    the handbrake axis guard already applied - an out-of-range index, or
    one that is actually an axis rather than a button on the device, makes
    the sample untrustworthy exactly like an absent device does). A sample
    where more than one configured slot reads pressed simultaneously is
    genuinely ambiguous (a device glitch, or a shifter resting between two
    detents) and is rejected outright (disabled) rather than silently
    resolved by picking the lowest index.
  - Server-side authorization/validation (ticket comment 53603): only the
    vehicle's own controlling (driver) seat occupant's packets are accepted
    (`PhysicsBridge.isControllingRider`) - a passenger is rejected outright.
    An out-of-range/invalid target, or a non-finite handbrake magnitude, is
    rejected rather than stored.
  - **Real ownership tracking, not a best-effort dismount packet** (ticket
    comment 53617: a "clear on dismount" packet cannot work, because the
    controller-seat check already fails by the time such a packet could be
    sent). `PhysicsBridge.recordWheelAuxOwner` records the authorized
    player per vehicle; every server tick,
    `advanceWheelAuxInputs` first sweeps every tracked vehicle for
    ownership loss (the recorded owner no longer the controlling rider -
    dismount, seat change, disconnect) and clears its H-pattern/handbrake
    state and tracker outright, BEFORE applying any climb step - so a
    revoked owner can never sneak in one more tick of input. A fresh
    owner's request for the same slot the previous owner held genuinely
    re-climbs to it (never silently treated as "already there" from stale
    tracker state) - including an IMMEDIATE handoff with no intervening
    sweep tick at all: `recordWheelAuxOwner` itself clears the vehicle's
    complete wheel-aux state whenever the recorded owner actually CHANGES,
    before recording the new one, so a new controller's packet arriving
    before the next ownership-loss sweep ever runs cannot silently inherit
    the previous owner's still-latched tracker (a repeat packet from the
    SAME still-current owner is a no-op here, so an in-progress climb is
    never reset merely by that owner sending another packet).
  - **A real per-tick client heartbeat, not only changed-value sends**
    (ticket comment 53617: the server-side staleness timeout was
    independently discovered releasing a continuously-held handbrake after
    5s, because the client only ever sent on change). `WheelAuxInput` now
    resends on every change AND unconditionally every `HEARTBEAT_TICKS`
    (20) ticks even with nothing changed, so a held handbrake or H-pattern
    slot never goes stale merely from being held steady.
  - Any engine/vehicle whose last update is older than the bounded timeout
    (100 ticks / 5s) is still released automatically
    (`PhysicsBridge.advanceWheelAuxInputs`'s own staleness sweep) as a
    backstop - disconnect and genuine client stop are still covered by it -
    but with the heartbeat above, an actively-held control no longer trips
    it by itself.
- **An analog handbrake.** IV has no analog handbrake control at all -
  `CAR_PARK` is the only parking/handbrake-adjacent control, and it is a
  plain digital toggle. This project adds one, as its own SEPARATELY
  selectable device: `wheel.handbrakeJoystickName` (empty = reuse whatever
  device is already configured for `CAR_TURN`; set it to bind a future
  dedicated standalone handbrake device instead) and `wheel.handbrakeAxis`,
  with its own `handbrakeAxisMin`/`Max`/`Invert`/`Deadzone` - genuinely
  independent calibration from the service brake pedal and from `CAR_PARK`.
  The configured axis index is validated against the resolved device's own
  actual axis count before every read, not just checked for `>=0` - an
  out-of-range index can otherwise return 0 from the underlying API, which
  this project's own min/max mapping would silently normalize to a HALF
  pull rather than "no reading."
  - **Rear/handbrake-capable-wheel semantics, not just another service
    brake** (`MultiContactFrictionSolver.FrictionContact.brakeMagnitude` is
    now PER-WHEEL): a non-steering ("rear") wheel's own brake magnitude is
    `max(serviceBrake, handbrake)`; a steering ("front") wheel's is the
    service brake alone, same as before this addition. Parking brake still
    forces every wheel's own magnitude to 1.0, unchanged. Missing/disabled/
    disconnected always resolves to exactly 0.0, never a stale value.

`HPatternShifterTest`/`WheelAxisCalibrationTest`/`WheelAuxStalenessTest`
cover the pure logic above directly, including mid-climb external-change
cancellation and safety-refusal retry. `MultiContactFrictionSolverBrakingTest.
differentPerContactBrakeMagnitudesProduceDistinctBrakingForcesForFrontVsRearWheel`
is the regression for the per-wheel braking change itself: two contacts in the
SAME solve call with different `brakeMagnitude` values (mirroring a
non-steering/handbrake-composed wheel next to a steering/service-brake-only
wheel) must produce distinct braking forces - a regression back to a single
shared magnitude parameter would make them equal.

Everything above that genuinely requires a live IV `EntityVehicleF_Physics`/
`PartEngine`/`PartSeat` (not constructible from a plain unit test - the same
pre-existing boundary VERIFICATION.md documents elsewhere in this project) is
instead covered by `WheelAuxProbe` (ticket comment 53617's own ask: "prove
this against the real production entry points, not a reimplementation
loop") - an opt-in, bounded, CI/isolated-host-only runtime probe following
this project's established pattern (`AnchorFrameProbe`, `BrakeSweepProbe`,
`GroundSurfaceTagProbe`, etc.), gated by `-Ddiv.experimentalWheelAuxProbe=true`
(wired in `build.gradle` via `DIV_WHEEL_AUX_PROBE=true gradle runServer`) and
a strict no-op otherwise. It spawns a real chassis/engine/controller-seat,
then drives `PhysicsBridge.setHPatternTarget`/`setHandbrakeMagnitude`/
`recordWheelAuxOwner`/`advanceWheelAuxInputs` directly against them and
asserts against the real `PartEngine.currentGearVar` and (via
`PhysicsBridge.currentHandbrakeMagnitudeForTesting`, exposed purely for this
kind of direct observation) the real handbrake state:

1. a single recorded H-pattern target - set exactly ONCE, never re-sent -
   climbs multiple gears across multiple real server ticks (the exact
   stall this project's earlier, now-removed
   `HPatternShifterBidirectionalProductionGapTest` claimed to cover by
   looping `Tracker` directly instead - John's review correctly flagged
   that as not actually exercising production code, AND correctly flagged
   an earlier version of this probe's own step 1 for re-sending the target
   every tick, which cannot distinguish "climbs from one input" from
   "climbs because told to every tick"; this probe is the real
   replacement, recording once and relying purely on the actual per-tick
   hook for every subsequent tick)
2. dismounting the controlling seat revokes ownership before a newly
   recorded target can apply
3. a fresh owner remounting the same seat and requesting the same slot
   genuinely re-climbs to it
4. an IMMEDIATE A->B controller handoff, with NO intervening ownership-
   loss sweep tick at all, and B requesting the SAME slot A had already
   achieved, genuinely re-climbs to it rather than silently inheriting A's
   stale "already achieved" tracker latch (review: `recordWheelAuxOwner`'s
   own `put()` could otherwise let B's packet overwrite A's UUID before
   the next sweep ever ran, leaving A's tracker/target to apply to B)
5. a handbrake that is repeatedly RESENT (simulating `WheelAuxInput`'s own
   periodic heartbeat) stays fully applied well past the 100-tick
   staleness timeout, observed directly rather than inferred from an
   unrelated gear change
6. the SAME handbrake, once resends genuinely stop (true silence, not
   merely an unrelated change), actually expires back to 0.0 at that same
   timeout - the genuine staleness-release path, not the achieved-latch
   side effect an earlier version of this probe's step 4 actually measured

Verified locally via `gradle runServer` against a real headless dedicated
server: `[DIV wheelAuxProbe] COMPLETE pass=6 fail=0`. CI cannot currently
run this (the hosted runner's own established billing/spend-capacity
restriction - see ticket comment 53620 - blocks every job from starting,
not a code failure), so this local run plus `gradle clean build`/`gradle
test` passing is the evidence offered in place of it. No hardware G29 is
connected to Zippy right now (ticket comment 53557's device inventory), so
feel/calibration against a real wheel remains John's own verification once
hardware exists - but the correctness fixes above (authorization,
ownership revocation, heartbeat, staleness, bidirectional climb, mid-climb
cancellation, per-wheel braking) are software-correctness concerns this
project is responsible for testing as directly as this environment allows,
not something to defer to his playtesting.

## Configuration

JSON is now **optional per-car tuning**, never an activation prerequisite. A
car with no entry at all still gets physics if its category is eligible. On
first start the mod writes `config/dynamic-immersive-vehicles.json` with an
example entry; the key is the IV definition's `packID:systemName`. The one
activation-relevant field is `enabled`, and it is an opt-**out** only: omit it
(or the whole entry) to use the default behavior above, or set it to `false`
to exclude one specific car even if its category would otherwise qualify.
Setting it to `true` is accepted for backwards compatibility with earlier
opt-in configs but changes nothing beyond what omitting it already does.
Clients and the server must use identical configuration and must restart
after changes.

```json
{
  "vehicles": {
    "examplepack:ordinary_car": {
      "massKg": 1450.0,
      "suspensionTravel": 0.3,
      "friction": 1.0,
      "driveForce": 8000.0,
      "brakeForce": 12000.0,
      "maxSteerDegrees": 30.0
    },
    "examplepack:known_bad_car": {
      "enabled": false
    },
    "examplepack:ordinary_boat": {
      "massKg": 900.0,
      "hullFillFactor": 0.5,
      "waterDrag": 400.0,
      "propellerForce": 6000.0,
      "angularWaterDrag": 0.0,
      "maxSteerDegrees": 30.0
    }
  }
}
```

`massKg` is optional; omit it to use IV's calculated vehicle mass. The four
fields above a boat/pontoon hull specifically uses - `hullFillFactor` (the
fraction of the chassis bounding-box volume the hull actually displaces when
fully submerged, since a real hull is not a solid box), `waterDrag`,
`propellerForce` (split evenly across the non-rotor propellers actually
operational this tick - linked to a running engine, active, and turning -
not every installed propeller), and `angularWaterDrag` (an optional
roll/pitch/yaw damping torque coefficient, 0 by default - no change from
prior behavior unless configured) - are ignored by a ground/flight vehicle
with no float device, and `suspensionTravel`/`friction`/`driveForce`/
`brakeForce` are ignored by a float-only hull. A copyable example is also
available at
[`docs/dynamic-immersive-vehicles.example.json`](docs/dynamic-immersive-vehicles.example.json).

### Part overrides, templates, and `extends`

Alongside `vehicles`, the config accepts two more top-level maps:

- `parts`, keyed `partPackID:partSystemName` (the part definition's own
  identity, same shape as a vehicle's `packID:systemName`) - for tuning a
  part independently of any one vehicle it happens to be installed on.
  Only `driveForce` is currently supported: an installed, running engine
  part's own `driveForce`, if its part entry sets one, completely replaces
  (not multiplies) the vehicle's own resolved `driveForce` for that
  engine. Setting any other field on a `parts` entry - directly, or
  inherited through `extends` - fails config load/reload loudly (the same
  as an out-of-range value) rather than being silently accepted and
  ignored; every other field has no part-level consumer yet.
- `templates`, an arbitrary-named map of reusable tuning fragments. Any
  `vehicles` or `parts` entry (or another template) can set `"extends":
  "<template name>"` to inherit every field it leaves unset from that
  template (chained through multiple templates if needed); a field the
  entry itself sets always wins over its template. An unknown `extends`
  target or an extends cycle fails config load/reload loudly, the same as
  an out-of-range field.

```json
{
  "templates": {
    "sporty-base": { "friction": 1.2, "driveForce": 10000.0 }
  },
  "vehicles": {
    "examplepack:sporty_car": { "extends": "sporty-base", "massKg": 1250.0 }
  },
  "parts": {
    "examplepack:high_output_engine": { "driveForce": 9500.0 }
  }
}
```

### Hot reload

The server polls the config file's own last-modified time (every few
seconds, not every tick) and, on a real change, re-reads and fully
validates it. A clean reload reports how many vehicle/part overrides it
picked up and is logged; a malformed file (bad JSON, an out-of-range
field, an unknown `extends` target, a cycle) is also logged, as a failure,
and changes **nothing** - the previously-loaded config stays in effect
(last-known-good), so a bad edit while the server is live cannot itself
break anything already running.

A successful reload only live-updates fields every active vehicle already
re-reads every tick: `suspensionTravel`, `friction`, `driveForce`,
`brakeForce`, `maxSteerDegrees`, `lateralGripMultiplier`, `waterDrag`,
`propellerForce`, `angularWaterDrag`. Fields baked in at vehicle construction - `massKg`,
`hullFillFactor`, `upperMassFraction`, and every `selfRight*` field - are
**not** applied to an already-spawned vehicle; re-enter (or otherwise
respawn) the vehicle to pick up a changed value for one of those. The
reload log names exactly which changed fields, if any, fell into this
"needs respawn" set, rather than claiming everything applied.

### Ground-surface tire response (MINECRAFT-508)

Separate, optional config for the five baseline ground surfaces (pavement,
dirt, grass, gravel, ice) a wheel's actual terrain contact resolves to (see
`GroundSurfaces`/`GroundSurface` for how a block maps to one of these). On
first start the mod writes `config/dynamic-immersive-vehicles-surfaces.json`
with no entries (every surface uses its hardcoded default); add an entry
only for a surface you want to retune:

```json
{
  "surfaces": {
    "ice": {
      "frictionMultiplier": 0.05,
      "rollingResistanceMultiplier": 0.5
    },
    "gravel": {
      "frictionMultiplier": 0.75
    }
  }
}
```

Both fields are optional per entry (sparse, same convention as the vehicle
config above) - an omitted field keeps that surface's hardcoded default for
that field alone. `frictionMultiplier` must be in `[0, 3]`;
`rollingResistanceMultiplier` must be in `[0, 10]`; both are relative to dry
pavement's own `1.0`/`1.0`. A missing, malformed, or out-of-range file is
never fatal - startup logs a warning/error and continues with the hardcoded
defaults (see `GroundSurfaceOverrides`'s own class doc for the self-review
finding this guarantees).

**Load timing - read this before assuming a live edit takes effect.**
`GroundSurfaceOverrides.load()` runs once, at mod construction (same as
`Overrides.load()`). `GroundSurfaceOverrides.reload()` already exists and is
fully non-throwing/last-known-good, but **nothing currently calls it** - there
is no live-reload command or trigger wired up for this file yet. Editing
`dynamic-immersive-vehicles-surfaces.json` while the server is running has no
effect until the next full restart. This is now DIFFERENT from the per-vehicle
config above: MINECRAFT-505's periodic watcher (the "Hot reload" section just
above) already calls `Overrides.reload()` on this branch, but nothing
equivalent exists yet for this file. Wiring an actual trigger for THIS file is
expected to piggyback on that same `OverridesHotReload` mechanism rather than
add a second, separate one - that wiring is not part of this ticket's bounded
scope.

## Build

CI uses Java 21 and Gradle 8.8. It resolves the exact official dependency
artifacts at build time and uploads only this project's jar, checksums, and test
reports. A second, bounded `runtime-smoke` job boots a headless dedicated
server with those same pinned dependencies to check NeoForge/Mixin startup
only; see [VERIFICATION.md](VERIFICATION.md) for what it does and does not
prove. To run the same tasks locally with Gradle 8.8:

```text
gradle --no-daemon clean build
```

Never commit jars under `libs/`. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
and [PROVENANCE.md](PROVENANCE.md) before changing dependency handling or making
the repository public.
