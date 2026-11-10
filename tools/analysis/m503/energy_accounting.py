import re, sys
import numpy as np
from collections import defaultdict

"""
Energy accounting against the real captured log (raw_capture_excerpt.txt). Decomposes each
tick's kinetic-energy change into THREE simultaneous impulse sources - gravity (zero torque,
acts at the COM), vertical/suspension, and tire - using the same quadratic-impulse/cross-
coupling formalism as the torque-only check in torque_closure_check.py, and asserts the combined
model closes against the actually logged pre/post kinetic energy. An earlier version of this
script (committed by mistake - ticket comment 53023) omitted the gravity term entirely, so its
"tireOnly"/"vertOnly" numbers never summed to the actual KE change; THIS version is the one that
was actually run to produce the closure numbers quoted in ticket comments 53014/53020.
"""

def vec(s): return np.array([float(x) for x in s.strip('()').split(',')])

def quat_to_matrix(x,y,z,w):
    return np.array([
     [1-2*(y*y+z*z), 2*(x*y-z*w), 2*(x*z+y*w)],
     [2*(x*y+z*w), 1-2*(x*x+z*z), 2*(y*z-x*w)],
     [2*(x*z-y*w), 2*(y*z+x*w), 1-2*(x*x+y*y)]])

header_re = re.compile(
 r"entity=(?P<entity>[0-9a-f-]+) side=server tick=(?P<tick>\d+) mass=(?P<mass>[\d.]+) gravity=(?P<g>[\d.]+) wheels=\d+ "
 r"spring=[\d.]+ damper=[\d.]+ pose\.position=\([^)]+\) orientation=\((?P<q>[^)]+)\) up=\([^)]+\) "
 r"linearVelocity=\((?P<v>[^)]+)\) angularVelocity=\((?P<omega>[^)]+)\)")
wheel_re = re.compile(
 r"wheel localOffset=\[[^\]]+\] radius=[\d.]+ arm=\([^)]+\) rayStart=\([^)]+\) rayEnd=\([^)]+\) rayLength=[\d.]+ "
 r"hit=\([^)]+\) distance=[-\d.]+ compression=[-\d.]+ anchorVerticalVelocity=[-\d.]+ point=\((?P<point>[^)]+)\) "
 r"localVelocity\(anchor-sampled\)=\([^)]+\) contactVelocity\(contact-sampled\)=\((?P<cv>[^)]+)\) speed=[-\d.]+ sideSpeed=(?P<side>[-\d.]+) "
 r"lateralSlipBudgetTarget=[-\d.]+ normal=(?P<normal>[-\d.]+) tire=Force\[longitudinal=(?P<long>[-\d.]+), lateral=(?P<lat>[-\d.]+)\] "
 r"requestedImpulseWorld=\((?P<J>[^)]+)\)")
DT=0.05

def parse(path):
    ticks=defaultdict(dict); cur_key=None
    for line in open(path):
        hm=header_re.search(line)
        if hm:
            cur_key=(hm['entity'],int(hm['tick']))
            ticks[cur_key].update(v=vec(hm['v']),omega=vec(hm['omega']),q=vec(hm['q']),g=float(hm['g']),wheels=[])
            continue
        wm=wheel_re.search(line)
        if wm and cur_key:
            ticks[cur_key]['wheels'].append(dict(point=vec(wm['point']),cv=vec(wm['cv']),side=float(wm['side']),
                normal=float(wm['normal']),long=float(wm['long']),lat=float(wm['lat'])))
    return ticks

mass=350.0
a,b,c=0.42500001192092896,0.4410937510430813,1.050000011920929
Ibody=np.diag([mass/3*(b*b+c*c), mass/3*(a*a+c*c), mass/3*(a*a+b*b)])

def KE(v,omega,Iw): return 0.5*mass*v.dot(v)+0.5*omega.dot(Iw@omega)

def energy_of_group(J_list,p_list,v_list,Iworld_inv):
    if not J_list: return 0.0
    lin=sum(J.dot(v) for J,v in zip(J_list,v_list))
    Jsum=sum(J_list,np.zeros(3)); tauSum=sum(np.cross(p,J) for p,J in zip(p_list,J_list))
    domega=Iworld_inv@tauSum
    return lin+0.5*Jsum.dot(Jsum)/mass+0.5*domega.dot(tauSum)

def run(path, max_tick_per_entity=9, abs_tol=0.05):
    ticks=parse(path)
    entities=sorted(set(e for e,t in ticks))
    assert entities, f"parsed zero entities from {path} - regex/log format mismatch"
    covered=0; worst_closure_err=0.0
    for ent in entities:
        max_tick=max(t for e,t in ticks if e==ent)
        for t in range(0, min(max_tick_per_entity, max_tick)):
            k0,k1=(ent,t),(ent,t+1)
            if k0 not in ticks or k1 not in ticks or not ticks[k0]['wheels']: continue
            d0=ticks[k0]
            R=quat_to_matrix(*d0['q']); up=R@np.array([0,1,0]); forward=R@np.array([0,0,1]); right=np.cross(up,forward)
            Iworld=R@Ibody@R.T; Iworld_inv=np.linalg.inv(Iworld)
            v0=d0['v']
            p_list=[w['point'] for w in d0['wheels']]
            vc_list=[w['cv'] for w in d0['wheels']]
            Jvert=[up*w['normal']*DT for w in d0['wheels']]
            Jtire=[(forward*w['long']+right*w['lat'])*DT for w in d0['wheels']]
            Jgrav=[mass*np.array([0,-d0['g'],0])*DT]; p_grav=[np.zeros(3)]; v_grav=[v0]

            dKE_vert=energy_of_group(Jvert,p_list,vc_list,Iworld_inv)
            dKE_tire=energy_of_group(Jtire,p_list,vc_list,Iworld_inv)
            dKE_grav=energy_of_group(Jgrav,p_grav,v_grav,Iworld_inv)
            Jall=Jvert+Jtire+Jgrav; pall=p_list+p_list+p_grav; vall=vc_list+vc_list+v_grav
            dKE_model_total=energy_of_group(Jall,pall,vall,Iworld_inv)
            cross=dKE_model_total-dKE_vert-dKE_tire-dKE_grav

            KE0=KE(v0,d0['omega'],Iworld)
            d1=ticks[k1]; R1=quat_to_matrix(*d1['q']); Iw1=R1@Ibody@R1.T
            KE1=KE(d1['v'],d1['omega'],Iw1)
            dKE_actual=KE1-KE0
            closure_err=abs(dKE_model_total-dKE_actual)
            worst_closure_err=max(worst_closure_err, closure_err)
            covered+=1
            print(f"{ent[:8]} t{t:2d}->{t+1:<2d} KE0={KE0:9.3f} dKE_actual={dKE_actual:10.4f}  "
                  f"grav={dKE_grav:9.4f} vert={dKE_vert:9.4f} tire={dKE_tire:9.4f} cross={cross:9.4f}  "
                  f"closure_err={closure_err:.6f}")
            assert closure_err < abs_tol, f"{ent} t{t}: closure residual {closure_err} exceeds tolerance {abs_tol}"
    print(f"\ncovered {covered} tick-transitions across {len(entities)} entities; worst closure residual = {worst_closure_err:.6f} J")
    assert covered > 0, "zero tick-transitions covered - parser matched headers but no wheel lines, or ticks dict empty"
    return covered, worst_closure_err

if __name__ == "__main__":
    path = sys.argv[1] if len(sys.argv) > 1 else "raw_capture_excerpt.txt"
    run(path)
