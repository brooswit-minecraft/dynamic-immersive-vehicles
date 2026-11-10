import re, numpy as np
from collections import defaultdict

RAW = open('raw_capture_excerpt.txt').read()

def vec(s):
    return np.array([float(x) for x in s.strip('()').split(',')])

def quat_to_matrix(x,y,z,w):
    return np.array([
     [1-2*(y*y+z*z), 2*(x*y-z*w), 2*(x*z+y*w)],
     [2*(x*y+z*w), 1-2*(x*x+z*z), 2*(y*z-x*w)],
     [2*(x*z-y*w), 2*(y*z+x*w), 1-2*(x*x+y*y)]
    ])

header_re = re.compile(
 r"entity=(?P<entity>[0-9a-f-]+) side=server tick=(?P<tick>\d+) mass=(?P<mass>[\d.]+) gravity=[\d.]+ wheels=\d+ "
 r"spring=[\d.]+ damper=[\d.]+ pose\.position=\([^)]+\) orientation=\((?P<q>[^)]+)\) up=\([^)]+\) "
 r"linearVelocity=\([^)]+\) angularVelocity=\((?P<omega>[^)]+)\)")

wheel_re = re.compile(
 r"wheel localOffset=\[[^\]]+\] radius=[\d.]+ arm=\([^)]+\) rayStart=\([^)]+\) rayEnd=\([^)]+\) rayLength=[\d.]+ "
 r"hit=\([^)]+\) distance=[-\d.]+ compression=[-\d.]+ anchorVerticalVelocity=[-\d.]+ point=\((?P<point>[^)]+)\) "
 r"localVelocity\(anchor-sampled\)=\([^)]+\) contactVelocity\(contact-sampled\)=\([^)]+\) speed=[-\d.]+ sideSpeed=[-\d.]+ "
 r"lateralSlipBudgetTarget=[-\d.]+ normal=(?P<normal>[-\d.]+) tire=Force\[longitudinal=(?P<long>[-\d.]+), lateral=(?P<lat>[-\d.]+)\] "
 r"requestedImpulseWorld=\((?P<J>[^)]+)\)")

DT = 0.05
ticks = defaultdict(dict)  # (entity,tick) -> {omega, q, wheels:[...]}
cur_key = None
for line in RAW.splitlines():
    hm = header_re.search(line)
    if hm:
        ent, tick = hm['entity'], int(hm['tick'])
        cur_key = (ent, tick)
        ticks[cur_key]['omega'] = vec(hm['omega'])
        ticks[cur_key]['q'] = vec(hm['q'])  # x,y,z,w
        ticks[cur_key]['wheels'] = []
        continue
    wm = wheel_re.search(line)
    if wm and cur_key:
        ticks[cur_key]['wheels'].append(dict(
            point=vec(wm['point']),
            normal=float(wm['normal']),
            long=float(wm['long']),
            lat=float(wm['lat']),
            J=vec(wm['J']),
        ))

print(f"parsed {len(ticks)} tick records")

mass = 350.0
a,b,c = 0.42500001192092896, 0.4410937510430813, 1.050000011920929
Ibody = np.diag([mass/3*(b**2+c**2), mass/3*(a**2+c**2), mass/3*(a**2+b**2)])

entities = sorted(set(e for e,t in ticks))
for ent in entities:
    print(f"\n=== entity {ent} ===")
    max_tick = max(t for e,t in ticks if e==ent)
    for t in range(0, max_tick):
        k0, k1 = (ent,t), (ent,t+1)
        if k0 not in ticks or k1 not in ticks: continue
        d0 = ticks[k0]
        q = d0['q']; R = quat_to_matrix(*q)
        up = R @ np.array([0,1,0])
        forward = R @ np.array([0,0,1])
        right = np.cross(up, forward)
        Iworld_inv = np.linalg.inv(R @ Ibody @ R.T)
        tau_vert = np.zeros(3); tau_tire = np.zeros(3); tau_total_logged = np.zeros(3)
        for w in d0['wheels']:
            p = w['point']
            J_vert = up*w['normal']*DT
            J_tire = (forward*w['long'] + right*w['lat'])*DT
            tau_vert += np.cross(p, J_vert)
            tau_tire += np.cross(p, J_tire)
            tau_total_logged += np.cross(p, w['J'])
        domega_vert = Iworld_inv @ tau_vert
        domega_tire = Iworld_inv @ tau_tire
        domega_total = Iworld_inv @ tau_total_logged
        predicted = d0['omega'] + domega_total
        actual = ticks[k1]['omega']
        err = np.linalg.norm(predicted-actual)
        relerr = err/ (np.linalg.norm(actual)+1e-9)
        print(f"t{t}->{t+1}: |domega_vert|={np.linalg.norm(domega_vert):.4f} |domega_tire|={np.linalg.norm(domega_tire):.4f} "
              f"predicted={np.round(predicted,4)} actual={np.round(actual,4)} abs_err={err:.5f} rel_err={relerr:.4f}")
