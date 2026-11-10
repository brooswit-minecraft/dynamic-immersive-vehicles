"""
Regression for review comment 53054: the production wiring's new per-tick header inserts
`solver=... iterations=...` between `gravity=` and `wheels=`, which the original header regex
(gravity immediately followed by wheels) does not match. Tests BOTH the OLD header format (pre-
c521a69 captures) and the NEW one (55a46fd onward) against both scripts' header_re, plus a full
header+wheel-line record for each, not just the header in isolation.

Run with: python3 test_header_format_compat.py
"""
import re
import torque_closure_check as tcc
import energy_accounting as ea

OLD_HEADER = ("[DIV debugGroundContact] entity=4b381e31-597a-471a-bc26-f25217bfd0e5 side=server tick=0 "
  "mass=350.0 gravity=11.0 wheels=4 spring=6416.666666666667 damper=1049.0273272576521 "
  "pose.position=(0,0,0) orientation=(0,0,0,1) up=(0,1,0) linearVelocity=(0,0,0) angularVelocity=(0,0,0)")
NEW_HEADER = ("[DIV debugGroundContact] entity=4b381e31-597a-471a-bc26-f25217bfd0e5 side=server tick=0 "
  "mass=350.0 gravity=11.0 solver=MultiContactFrictionSolver iterations=4 wheels=4 spring=6416.666666666667 "
  "damper=1049.0273272576521 pose.position=(0,0,0) orientation=(0,0,0,1) up=(0,1,0) linearVelocity=(0,0,0) "
  "angularVelocity=(0,0,0)")
WHEEL_LINE = ("[DIV debugGroundContact]  wheel localOffset=[X:0.35, Y:0.125, Z:-0.0625] radius=0.375 "
  "arm=(0,0,0) rayStart=(0,0,0) rayEnd=(0,0,0) rayLength=0.675 hit=(0,0,0) distance=0.1 compression=0.05 "
  "anchorVerticalVelocity=0.0 point=(0,0,0) localVelocity(anchor-sampled)=(0,0,0) "
  "contactVelocity(contact-sampled)=(0,0,0) speed=0.0 sideSpeed=0.0 lateralSlipBudgetTarget=1.23 "
  "normal=320.8 tire=Force[longitudinal=0.0, lateral=-0.0] requestedImpulseWorld=(0,0,0)")

def check(name, header_re, wheel_re=None):
    for label, header in [("OLD", OLD_HEADER), ("NEW", NEW_HEADER)]:
        assert header_re.search(header), f"{name}: {label} header did not match"
        full_record = header + "\n" + WHEEL_LINE
        assert header_re.search(full_record), f"{name}: {label} full header+wheel record - header didn't match"
        if wheel_re is not None:
            assert wheel_re.search(full_record), f"{name}: {label} full header+wheel record - wheel line didn't match"
    print(f"{name}: OLD and NEW header formats (header alone and full header+wheel record) both parse correctly.")

check("torque_closure_check.header_re", tcc.header_re, tcc.wheel_re)
check("energy_accounting.header_re", ea.header_re)
print("All header-format compatibility checks passed.")
