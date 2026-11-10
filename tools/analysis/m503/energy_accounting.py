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
 r"linearVelocity=\((?P<v>[^)]+)\) angularVelocity=\((?P<omega>[^)]+)\)")

wheel_re = re.compile(
 r"wheel localOffset=\[[^\]]+\] radius=[\d.]+ arm=\([^)]+\) rayStart=\([^)]+\) rayEnd=\([^)]+\) rayLength=[\d.]+ "
 r"hit=\([^)]+\) distance=[-\d.]+ compression=[-\d.]+ anchorVerticalVelocity=[-\d.]+ point=\((?P<point>[^)]+)\) "
 r"localVelocity\(anchor-sampled\)=\([^)]+\) contactVelocity\(contact-sampled\)=\((?P<cv>[^)]+)\) speed=(?P<speed>[-\d.]+) sideSpeed=(?P<side>[-\d.]+) "
 r"lateralSlipBudgetTarget=[-\d.]+ normal=(?P<normal>[-\d.]+) tire=Force\[longitudinal=(?P<long>[-\d.]+), lateral=(?P<lat>[-\d.]+)\] "
 r"requestedImpulseWorld=\((?P<J>[^)]+)\)")

DT = 0.05
ticks = defaultdict(dict)
cur_key = None
for line in RAW.splitlines():
    hm = header_re.search(line)
    if hm:
        ent, tick = hm['entity'], int(hm['tick'])
        cur_key = (ent, tick)
        ticks[cur_key].update(v=vec(hm['v']), omega=vec(hm['omega']), q=vec(hm['q']), wheels=[])
        continue
    wm = wheel_re.search(line)
    if wm and cur_key:
        ticks[cur_key]['wheels'].append(dict(
            point=vec(wm['point']), cv=vec(wm['cv']),
            speed=float(wm['speed']), side=float(wm['side']),
            normal=float(wm['normal']), long=float(wm['long']), lat=float(wm['lat']),
            J=vec(wm['J']),
        ))

mass = 350.0
a,b,c = 0.42500001192092896, 0.4410937510430813, 1.050000011920929
Ibody = np.diag([mass/3*(b**2+c**2), mass/3*(a**2+c**2), mass/3*(a**2+b**2)])

def KE(v, omega, Iworld):
    return 0.5*mass*v.dot(v) + 0.5*omega.dot(Iworld @ omega)

def quad_energy(J_list, p_list, v_c_list, Iworld_inv):
    # linear term: sum_i J_i . v_c_i  (each wheel's own pre-impulse contact velocity - exact, additive)
    lin = sum(J.dot(vc) for J, vc in zip(J_list, v_c_list))
    Jsum = sum(J_list, np.zeros(3))
    tauSum = sum(np.cross(p, J) for p, J in zip(p_list, J_list))
    quad_trans = 0.5*Jsum.dot(Jsum)/mass
    domega = Iworld_inv @ tauSum
    quad_rot = 0.5*domega.dot(tauSum)
    return lin + quad_trans + quad_rot

entities = sorted(set(e for e,t in ticks))
for ent in entities:
    print(f"\n=== entity {ent} ===")
    max_tick = max(t for e,t in ticks if e==ent)
    for t in range(0, min(10, max_tick)):
        k0, k1 = (ent,t), (ent,t+1)
        if k0 not in ticks or k1 not in ticks: continue
        d0 = ticks[k0]
        R = quat_to_matrix(*d0['q'])
        up = R @ np.array([0,1,0]); forward = R @ np.array([0,0,1]); right = np.cross(up, forward)
        Iworld = R @ Ibody @ R.T
        Iworld_inv = np.linalg.inv(Iworld)

        p_list=[]; Jtire_list=[]; Jvert_list=[]; vc_list=[]
        for w in d0['wheels']:
            p_list.append(w['point']); vc_list.append(w['cv'])
            Jvert_list.append(up*w['normal']*DT)
            Jtire_list.append((forward*w['long']+right*w['lat'])*DT)

        dKE_tire = quad_energy(Jtire_list, p_list, vc_list, Iworld_inv)
        dKE_vert = quad_energy(Jvert_list, p_list, vc_list, Iworld_inv)
        Jcombined = [jt+jv for jt,jv in zip(Jtire_list, Jvert_list)]
        dKE_combined_model = quad_energy(Jcombined, p_list, vc_list, Iworld_inv)
        cross = dKE_combined_model - dKE_tire - dKE_vert

        KE0 = KE(d0['v'], d0['omega'], Iworld)
        d1 = ticks[k1]
        R1 = quat_to_matrix(*d1['q']); Iworld1 = R1 @ Ibody @ R1.T
        KE1 = KE(d1['v'], d1['omega'], Iworld1)
        dKE_actual = KE1-KE0

        sides0 = [w['side'] for w in d0['wheels']]
        sides1 = [w['side'] for w in ticks[k1]['wheels']] if ticks[k1]['wheels'] else []
        print(f"t{t}->{t+1}: KE0={KE0:8.3f} dKE_actual={dKE_actual:9.4f}  dKE_tireOnly={dKE_tire:9.4f}  dKE_vertOnly={dKE_vert:9.4f}  cross={cross:8.4f}  "
              f"sideSpeed[t]={[round(s,4) for s in sides0]}  sideSpeed[t+1]={[round(s,4) for s in sides1]}")
