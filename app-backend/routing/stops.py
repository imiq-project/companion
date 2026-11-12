"""GTFS stop catalogue and explicit platform links, cached by static generation."""
import copy
import threading
import time
from routing.otp_transit import OtpTransitClient, OtpError
from routing.graphhopper_geometry import distance_m

_lock=threading.Lock()
_cache=None
_cache_time=0
_cache_generation=None

def associate_platforms(stops, data):
    """Only explicit stop/platform route pairs; proximity alone never picks a sidewalk."""
    points={p['id']:p for p in data['points']}
    waiting={k:p for k,p in points.items() if p['kind']=='stop' and p['tags'].get('public_transport')=='platform'}
    pairs={}
    for pair in data.get('stop_platform_pairs',[]):
        if pair['platform'] in waiting:pairs.setdefault(pair['stop'],set()).add(pair['platform'])
    vehicles=[points[k] for k in pairs if k in points and points[k]['tags'].get('public_transport')=='stop_position']
    result=copy.deepcopy(stops)
    for s in result:
        exact=[p for p in waiting.values() if s.get('code') and s['code']==p['tags'].get('ref:IFOPT')]
        direct=sorted((distance_m([s['lon'],s['lat']],[p['lon'],p['lat']]),p['id']) for p in waiting.values())
        candidates=exact
        method='IFOPT'
        if not candidates and direct and direct[0][0]<=3 and (len(direct)==1 or direct[1][0]-direct[0][0]>=5):
            candidates=[waiting[direct[0][1]]];method='coincident_platform'
        if not candidates:
            near=sorted((distance_m([s['lon'],s['lat']],[p['lon'],p['lat']]),p['id']) for p in vehicles)
            if near and near[0][0]<=15 and (len(near)==1 or near[1][0]-near[0][0]>=5):
                candidates=[waiting[k] for k in pairs[near[0][1]]];method='OSM_route_stop_platform_pair'
        if len(candidates)==1:
            s['waiting_platform']=copy.deepcopy(candidates[0]);s['platform_association']=method
    return result

def stop_catalog(client=None):
    global _cache,_cache_time,_cache_generation
    c=client or OtpTransitClient()
    generation=(c.status() or {}).get('static_dataset_sha256') if client is None else None
    with _lock:
        if client is None and _cache is not None and _cache_generation==generation and time.monotonic()-_cache_time<600:return copy.deepcopy(_cache)
        raw=c.request(c.base+'/otp/routers/default/index/graphql',{'query':'{ stops { gtfsId name lat lon code } }'})
        if raw.get('errors') or not isinstance(raw.get('data',{}).get('stops'),list):raise OtpError('Stop registry unavailable')
        stops=raw['data']['stops']
        if client is None:
            from routing.parking_planner import dataset
            stops=associate_platforms(stops,dataset())
        if client is None:_cache,_cache_time,_cache_generation=copy.deepcopy(stops),time.monotonic(),generation
        return stops

