"""Common service/provider probes. No participant history is read or written."""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import base64
import json
import time
from urllib.request import Request,urlopen
from urllib.error import HTTPError
from .runtime import settings


def probe(name,url,timeout,token='',body=None,expected=200):
    start=time.monotonic()
    headers={'Content-Type':'application/json'}
    if token:headers['Authorization']='Bearer '+token
    try:
        try:response=urlopen(Request(url,data=body,headers=headers),timeout=timeout)
        except HTTPError as exc:response=exc
        with response:
            status=response.code;raw=response.read(8_000_001)
            timing=response.headers.get('Server-Timing')
        if len(raw)>8_000_000:raise ValueError('Response bound exceeded')
        data=json.loads(raw) if name!='otp_http' else {}
        value={'http_status':status,'pass':status==expected}
        if timing:value['server_timing']=timing
        if name=='backend':
            value.update(model_version=data.get('model_version'),readout_version=data.get('action_readout_version'),
                         operational_learning_enabled=data.get('prospective_forecast',{}).get('operational_learning_enabled'))
            value['pass'] &= data.get('status')=='ok'
        elif name=='weather':
            count=sum(isinstance(o,dict) and o.get('status')=='OBSERVED' and o.get('numeric_value') is not None for o in data.get('raw_observations',[]))
            value.update(provider_status=data.get('status'),numeric_observations=count)
            value['pass'] &= data.get('status')=='AVAILABLE' and count>0
        elif name=='realtime':
            static,graph=data.get('static_dataset_sha256'),data.get('graph_dataset_sha256')
            same=isinstance(static,str) and isinstance(graph,str) and len(static)==64 and all(c in '0123456789abcdef' for c in static.lower()) and static.lower()==graph.lower()
            stamp=data.get('feed_timestamp');age=time.time()-stamp if type(stamp) in (int,float) else None
            value.update(ready=data.get('ready'),dataset_compatible=data.get('dataset_compatible'),dataset_ids_match=same,
                         feed_age_seconds=round(age,1) if age is not None else None,reason=data.get('refresh_error'))
            value['pass'] &= data.get('ready') is True and data.get('dataset_compatible') is True and same and age is not None and 0<=age<=180 and data.get('compatibility_version')=='mvb-gtfs-identity-v2'
        elif name in ('geocoding','stops'):
            key='features' if name=='geocoding' else 'stops'
            value[key]=len(data.get(key,[]));value['pass'] &= value[key]>0
        elif name=='map_tile':
            url_value=data.get('data_url','')
            png=base64.b64decode(url_value.split(',',1)[1],validate=True) if url_value.startswith('data:image/png;base64,') else b''
            value['bytes']=len(png);value['pass'] &= png.startswith(b'\x89PNG\r\n\x1a\n')
        if body is not None:value['error_code']=data.get('error')
        value['state']='available' if value['pass'] else 'degraded'
    except (OSError,ValueError,TypeError,AttributeError):
        value={'pass':False,'state':'unavailable','reason':'connection_timeout_or_invalid_response'}
    value['seconds']=round(time.monotonic()-start,3)
    return name,value


def run(*,providers=False,include_weather=False,timeout=15,base_url=None,otp_url=None,realtime_url=None,validation=False):
    config=settings();base=(base_url or f'http://127.0.0.1:{config.backend_port}').rstrip('/')
    jobs=[('backend',base+'/health',None,200),('otp_http',otp_url or f'http://127.0.0.1:{config.otp_port}/',None,200),
          ('realtime',realtime_url or f'http://127.0.0.1:{config.realtime_port}/status',None,200)]
    if providers or include_weather:jobs.append(('weather',base+'/api/companion/weather?lat=52.13&lon=11.62',None,200))
    if providers:
        jobs.extend([('geocoding',base+'/api/companion/geocoding?q=Universitat%20Magdeburg&lang=en',None,200),
                     ('stops',base+'/api/companion/transit/stops',None,200),('map_tile',base+'/api/companion/map-tiles/12/2180/1350',None,200)])
    if validation:
        jobs.extend((name,base+path,body,expected) for name,path,body,expected in (
            ('malformed_json_rejected','/api/companion',b'{invalid',400),
            ('missing_inputs_rejected','/api/companion',b'{}',422),
            ('incomplete_priorities_rejected','/api/companion/goal-personalization/prepare',b'{}',422),
            ('missing_route_inputs_rejected','/api/companion/routed-contextual-deliberation',b'{}',422)))
    with ThreadPoolExecutor(max_workers=6) as pool:
        values=dict(pool.map(lambda job:probe(job[0],job[1],timeout,config.api_token if job[1].startswith(base+'/') else '',job[2],job[3]),[job for job in jobs if job[2] is None]))
    # Validation probes are sequential so they do not contend with their own CPU admission limit.
    for job in jobs:
        if job[2] is not None:
            name,value=probe(job[0],job[1],timeout,config.api_token,job[2],job[3])
            values[name]=value
    code=3 if not values['backend']['pass'] else 4 if not values['otp_http']['pass'] else 2 if not all(v['pass'] for v in values.values()) else 0
    return {'checked_at':datetime.now(timezone.utc).isoformat(),'overall':'available' if code==0 else 'degraded' if code==2 else 'unavailable',
            'checks':values,'passed':sum(v['pass'] for v in values.values()),'total':len(values),'exit_code':code,
            'scope':'Public-coordinate probes; no participant or device storage. Availability is not physical weather accuracy.'}


def synthetic_route(base_url=None,timeout=180):
    from .smoke import scenario
    config=settings();base=(base_url or f'http://127.0.0.1:{config.backend_port}').rstrip('/')
    class Response:
        def __init__(self,status,data,timing=None):self.status_code=status;self.data=data;self.timing=timing
        def get_json(self):return self.data
    class Client:
        def post(self,path,json):
            headers={'Content-Type':'application/json'}
            if config.api_token:headers['Authorization']='Bearer '+config.api_token
            request=Request(base+path,data=__import__('json').dumps(json).encode(),headers=headers)
            try:response=urlopen(request,timeout=timeout)
            except HTTPError as exc:response=exc
            with response:return Response(response.code,__import__('json').load(response),response.headers.get('Server-Timing'))
    client=Client();result=scenario(client)
    import uuid
    start=time.monotonic()
    response=client.post('/api/companion/routed-contextual-deliberation',{
        'search_id':'synthetic-operations-'+uuid.uuid4().hex,'datetime':datetime.now(timezone.utc).isoformat(),
        'start':{'lat':52.13,'lon':11.62},'stop':{'lat':52.14,'lon':11.64},
        'cognitive_passport':result['prepared']['cognitive_passport'],'context_policy':'observations_only',
        'contextual_query':{'query_orion':False},'record_trajectories':True})
    data=response.get_json()
    if response.status_code!=200:raise ValueError('Synthetic route failed: HTTP '+str(response.status_code))
    simulation=data['contextual_deliberation']
    rows=simulation['candidate_results']
    if not rows:raise ValueError('Synthetic search returned no candidates')
    for summary in [simulation['baseline']]+[row['hotco'] for row in rows]:
        evidence=summary['simulation_evidence']
        if evidence['model_version']!='hotco_signed_grossberg_5goals_v1' or len(evidence['state_columns'])!=13:
            raise ValueError('Synthetic search returned an unexpected model contract')
    audit={name:{key:value[key] for key in ('status','configured','route_count','realtime_feed_fresh') if key in value}
           for name,value in data['routing'].get('provider_audit',{}).items()}
    return {'status':'pass','question_count':20,'goal_count':5,'states':13,'candidate_count':len(rows),
            'route_modes':[r['mode_key'] for r in data['routing']['routes']],
            'provider_audit':audit,'seconds':round(time.monotonic()-start,3),
            'server_timing':response.timing,'participant_storage_written':False}
