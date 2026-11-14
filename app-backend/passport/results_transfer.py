"""Block 06A: closed backend projection, independent of HOTCO outputs and GPS."""
from copy import deepcopy
from datetime import datetime,timezone
from hashlib import sha256
import json
import math
from jsonschema import Draft202012Validator
from resources import CONTRACTS_DIR
from passport.priority_transfer import instant,same_json
from passport.search_transfer import revision_id as search_revision,climate_profile

SCHEMA=json.loads((CONTRACTS_DIR/'search-results-v1.schema.json').read_text(encoding='utf-8'))
VALIDATOR=Draft202012Validator(SCHEMA)
staged=deepcopy(SCHEMA);staged['required']=[k for k in staged['required'] if k not in ('app_version','response_received_at')]
STAGED_VALIDATOR=Draft202012Validator(staged)
MAX_BYTES=32*1024*1024
ROUTE_KEYS=set(SCHEMA['$defs']['route']['properties'])
CONTEXT_KEYS=set(SCHEMA['$defs']['context']['properties'])
SEGMENT_KEYS=set(SCHEMA['$defs']['context_segment']['properties'])
PROVIDER_KEYS=set(SCHEMA['$defs']['provider']['properties'])
RT_KEYS=set(SCHEMA['$defs']['rt']['properties'])

class InvalidResultsTransfer(ValueError):pass
def require(value):
    if not value:raise InvalidResultsTransfer('invalid_search_results')
def revision_id(search_id):return 'search-results-'+sha256(('search-results-v1|'+search_id).encode()).hexdigest()
def pick(value,keys):return {k:deepcopy(v) for k,v in value.items() if k in keys}
def known(value,allowed):require(isinstance(value,dict) and set(value)<=set(allowed))

def validate(payload,*,staged=False):
    try:
        error=next((STAGED_VALIDATOR if staged else VALIDATOR).iter_errors(payload),None)
        require(error is None)
        require(payload['results_revision_id']==revision_id(payload['search_id']))
        require(payload['search_metadata_reference']['search_revision_id']==search_revision(payload['search_id']))
        instant(payload['backend_generated_at'])
        if not staged:instant(payload['response_received_at'])
        routes=payload['routing']['routes'];audit=payload['route_audit'];candidates=payload['candidate_routes'];contexts=payload['route_context_observations']
        require(len(routes)==len(audit))
        keys=[(r['rank'],r['mode_key']) for r in routes]
        require(len(set(keys))==len(keys))
        require(keys==[(a['routing_rank'],a['mode_key']) for a in audit])
        ids=[c['route_id'] for c in candidates];context_ids=[c['route_id'] for c in contexts]
        require(len(set(ids))==len(ids) and context_ids==ids)
        require([a['route_id'] for a in audit if a['context_ready']]==ids)
        require(all((a['route_id'] is not None)==a['context_ready'] for a in audit))
        for item in contexts:require(item['route_id']==item['route_context']['route_id'])
        for route in routes:
            estimate=route.get('climate_estimate')
            if estimate:
                from climate.climate_impact import validate_snapshot
                validate_snapshot(estimate)
                require(estimate['search_id']==payload['search_id'])
                require(estimate['route_key']==f"{route['rank']}-{route['mode_key']}")
                climate_profile(estimate.get('profile_snapshot'))
            for transit in [route.get('transit'),*[leg.get('transit') for leg in route.get('legs',[])]]:
                if not transit:continue
                if transit.get('realtime') is False or transit.get('realtime_status')=='SCHEDULED':
                    require(transit.get('departure_delay_seconds') is None and transit.get('arrival_delay_seconds') is None)
        return payload
    except InvalidResultsTransfer:raise
    except (ValueError,TypeError,KeyError,AttributeError,OverflowError,RecursionError):
        raise InvalidResultsTransfer('invalid_search_results') from None

def project(response,*,generated_at=None):
    """Fail closed for this archive only; never alter the original routing response."""
    known(response,('schema_version','search_id','journey_availability','availability_source','status','routing','route_audit','candidate_routes','contextual_deliberation','warnings','climate_method','d_day_prediction_contexts','d_day_prediction_context_failure'))
    routing=response['routing']
    known(routing,('routes','provider_audit','ordering_policy','agent','best_route','metadata','request','status','straight_line_distance_m'))
    providers={}
    for name,source in routing['provider_audit'].items():
        known(source,PROVIDER_KEYS|{'query','raw_response','routing_errors','rejected_itineraries','selected_boarding_stop'})
        value=pick(source,PROVIDER_KEYS)
        for key in ('realtime_status_before','realtime_status_after'):
            if key in source:
                snapshot=source[key]
                value[key]=None if snapshot is None else pick(snapshot,RT_KEYS)
                # RT status has private operating paths/diagnostics; only this reviewed public identity/freshness projection is retained.
        for key in ('routing_errors','rejected_itineraries'):
            if key in source:value[key+'_count']=len(source[key])
        if 'later_departures' in source:
            known(source['later_departures'],('offset_minutes','status','reason','query','raw_response'))
            value['later_departures']=pick(source['later_departures'],{'offset_minutes','status','reason'})
        providers[name]=value
    for route in routing['routes']:known(route,ROUTE_KEYS)
    contexts=[]
    contextual=response.get('contextual_deliberation')
    if contextual:
        known(contextual,('schema_version','search_id','timestamp','baseline','candidate_results','model_version','normalization_version','warnings','goal_priorities','baseline_role','trajectory_archive','trajectory_archive_version'))
    for item in (contextual or {}).get('candidate_results',[]):
        known(item,('route_id','route_context','hotco','difference_from_baseline','warnings','source_status','belief_update'))
        source=item['route_context'];known(source,CONTEXT_KEYS|{'normalized_stressors','mode_specific_stressors','contextual_data_completeness'})
        context=pick(source,CONTEXT_KEYS)
        context['segments']=[]
        for segment in source['segments']:
            known(segment,SEGMENT_KEYS|{'normalized_stressors','contextual_data_completeness'})
            context['segments'].append(pick(segment,SEGMENT_KEYS))
        contexts.append({'route_id':item['route_id'],'route_context':context,'source_status':deepcopy(item['source_status']),'warnings':deepcopy(item['warnings'])})
    payload={'schema_version':'companion-search-results-v1','data_class':'participant','results_revision_id':revision_id(response['search_id']),
        'search_id':response['search_id'],'search_metadata_reference':{'search_revision_id':search_revision(response['search_id'])},
        'backend_generated_at':generated_at or datetime.now(timezone.utc).isoformat(),
        'source_response_schema_version':response['schema_version'],'source_response_status':response['status'],
        'journey_availability':deepcopy(response['journey_availability']),'availability_source':response['availability_source'],
        'routing':{'routes':deepcopy(routing['routes']),'provider_audit':providers},
        'route_audit':deepcopy(response['route_audit']),'candidate_routes':deepcopy(response['candidate_routes']),
        'route_context_observations':contexts,'warnings':deepcopy(response['warnings'])}
    if 'ordering_policy' in routing:payload['routing']['ordering_policy']=routing['ordering_policy']
    if 'climate_method' in response:payload['climate_method']=deepcopy(response['climate_method'])
    validate(payload,staged=True)
    require(len(json.dumps(payload,ensure_ascii=False,allow_nan=False).encode())<=MAX_BYTES)
    return payload

def decode_results_transfer(raw,*,data_class='participant'):
    def pairs(items):
        value={}
        for k,v in items:require(k not in value);value[k]=v
        return value
    def finite(text):
        value=float(text);require(math.isfinite(value));return value
    try:
        require(len(raw)<=MAX_BYTES)
        value=json.loads(raw.decode('utf-8'),object_pairs_hook=pairs,parse_float=finite,parse_constant=lambda _:require(False))
        require(value['data_class']==data_class)
        return validate(value)
    except InvalidResultsTransfer:raise
    except (ValueError,TypeError,KeyError,AttributeError,RecursionError,OverflowError):raise InvalidResultsTransfer('invalid_search_results') from None

def validate_parent(payload,parent):
    require(parent['status']=='complete')
    require(payload['search_id']==parent['search_id'])
    require(payload['search_metadata_reference']['search_revision_id']==parent['search_revision_id'])
    require(payload['app_version']==parent['app_version'])
    require(same_json(payload['journey_availability'],parent['request']['journey_availability']))
    for candidate in payload['candidate_routes']:
        for role,field in (('origin','start'),('destination','stop')):
            require(same_json(candidate[role],{'latitude':parent['request'][field]['lat'],'longitude':parent['request'][field]['lon']}))
    for route in payload['routing']['routes']:
        if route.get('climate_estimate'):require(same_json(route['climate_estimate'].get('profile_snapshot'),parent['request']['climate_profile']))
