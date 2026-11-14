"""Lossless HOTCO archival packets. No changes to simulation or sampling."""
import base64
from copy import deepcopy
from dataclasses import asdict
from datetime import datetime,timezone
from decimal import Decimal
import gzip
import hashlib
import hmac
import io
import json
import math
import os
import platform
from pathlib import Path
import secrets
import zlib
from jsonschema import Draft202012Validator
from resources import BACKEND_DIR,CONTRACTS_DIR
from passport.results_transfer import revision_id as results_revision
from passport.priority_transfer import instant
from model.hotco_signed_v1 import MODEL_VERSION,SignedHOTCOParameters
from model.input_mapping_v4_3 import NEEDS,MODES

VERSION='companion-hotco-transfer-v1'
MAX_BYTES=32*1024*1024
MAX_RAW=128*1024*1024
SUMMARY_KEYS=set('final_action_activations terminal_leaders readout_version winner context_metadata process_diagnostics node_activations ambiguity_state simulation_evidence'.split())
SHARED_KEYS=set('model_version parameters topology availability state_columns time_reference dt time_grid'.split())
EVIDENCE_KEYS=SHARED_KEYS|set('schema_version state_kind state_bounds cognitive_scaling availability_policy numerical evaluation_status readout_version terminal_leaders action_tie_tolerance trajectory_id sample_count projection_corrections effective_input_digest reuse_policy goal_priorities effective_initial_needs'.split())
SIGNED_KEYS=set('schema_version data_class search_id hotco_revision_id results_reference backend_generated_at passport_input_sha256 packet'.split())
VALIDATOR=Draft202012Validator(json.loads((CONTRACTS_DIR/'hotco-transfer-v1.schema.json').read_text(encoding='utf-8')))

class InvalidHotcoTransfer(ValueError):pass
def require(condition):
    if not condition:raise InvalidHotcoTransfer('invalid_hotco_evidence')
def digest(raw):return hashlib.sha256(raw).hexdigest()
def canonical(value):return json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=False,allow_nan=False).encode()
def revision_id(search):return 'hotco-'+digest(('hotco-v1|'+search).encode())
def input_digest(value):
    def tagged(v):
        if isinstance(v,dict):return ['object',[[k,tagged(v[k])] for k in sorted(v)]]
        if isinstance(v,list):return ['array',[tagged(x) for x in v]]
        if type(v) in (int,float):
            require(math.isfinite(v));n=Decimal(str(v));return ['number',str(n.normalize()) if n else '0']
        return [type(v).__name__,v]
    return digest(canonical(tagged(value)))

class PacketSigner:
    def __init__(self,key):
        require(isinstance(key,bytes) and len(key)>=32);self.key=key;self.key_id=digest(key)[:16]
    def sign(self,wire):
        return hmac.new(self.key,canonical({k:wire[k] for k in sorted(SIGNED_KEYS)}),hashlib.sha256).hexdigest()
    def verify(self,wire):require(wire['key_id']==self.key_id and hmac.compare_digest(wire['signature'],self.sign(wire)))

def local_signer():
    from infrastructure.service_config import settings
    path=settings().state/'private/hotco-signing.key'
    path.parent.mkdir(parents=True,exist_ok=True)
    try:
        descriptor=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    except FileExistsError:pass
    else:
        with os.fdopen(descriptor,'wb') as stream:stream.write(secrets.token_bytes(32))
    return PacketSigner(path.read_bytes())

def pack(value):
    """Stream JSON into gzip; do not allocate another full uncompressed JSON string."""
    output=io.BytesIO();hasher=hashlib.sha256();count=0
    with gzip.GzipFile(fileobj=output,mode='wb',mtime=0) as target:
        for text in json.JSONEncoder(sort_keys=True,separators=(',',':'),ensure_ascii=False,allow_nan=False).iterencode(value):
            raw=text.encode();count+=len(raw);require(count<=MAX_RAW);hasher.update(raw);target.write(raw)
    data=output.getvalue();require(len(data)<=MAX_BYTES)
    return {'codec':'gzip-json-utf8-v1','raw_sha256':hasher.hexdigest(),'compressed_sha256':digest(data),'raw_bytes':count,
        'compressed_bytes':len(data),'data_base64':base64.b64encode(data).decode('ascii')}

def strict_json(raw):
    def pairs(items):
        result={}
        for k,v in items:require(k not in result);result[k]=v
        return result
    def number(text):
        value=float(text);require(math.isfinite(value));return value
    return json.loads(raw,object_pairs_hook=pairs,parse_float=number,parse_constant=lambda _:require(False))

def unpack(blob,*,remaining=MAX_RAW):
    require(set(blob)=={'codec','raw_sha256','compressed_sha256','raw_bytes','compressed_bytes','data_base64'})
    require(blob['codec']=='gzip-json-utf8-v1' and type(blob['raw_bytes']) is int and 0<blob['raw_bytes']<=min(MAX_RAW,remaining))
    data=base64.b64decode(blob['data_base64'],validate=True)
    require(len(data)==blob['compressed_bytes'] and len(data)<=MAX_BYTES and digest(data)==blob['compressed_sha256'])
    decoder=zlib.decompressobj(31);raw=decoder.decompress(data,blob['raw_bytes']+1)
    require(decoder.eof and not decoder.unused_data and not decoder.unconsumed_tail)
    require(len(raw)==blob['raw_bytes'] and digest(raw)==blob['raw_sha256'])
    return strict_json(raw)

def project(response,passport,signer,*,generated_at=None):
    contextual=response.get('contextual_deliberation');require(isinstance(contextual,dict))
    require(contextual['model_version']==MODEL_VERSION)
    archive=contextual['trajectory_archive'];configs={};runs={};artifacts={}
    def artifact(kind,value):
        blob=pack(value);identifier=kind+'-'+blob['raw_sha256'];artifacts.setdefault(identifier,{'artifact_id':identifier,'kind':kind,'blob':blob});return identifier
    def run(summary):
        require(set(summary)==SUMMARY_KEYS)
        evidence=summary['simulation_evidence'];require(set(evidence)<=EVIDENCE_KEYS)
        require(evidence['schema_version']=='hotco-simulation-evidence-v2' and evidence['model_version']==MODEL_VERSION)
        require(evidence['parameters']==asdict(SignedHOTCOParameters()))
        shared={k:deepcopy(evidence[k]) for k in SHARED_KEYS};configuration_id='configuration-'+digest(canonical(shared))
        configs.setdefault(configuration_id,{'configuration_id':configuration_id,**shared})
        trajectory_id=evidence.get('trajectory_id');require(trajectory_id in archive)
        states=archive[trajectory_id];require(len(states)==len(shared['time_grid'])==evidence['sample_count'])
        require(len(shared['state_columns'])==13 and all(len(row)==13 and all(type(x) in (int,float) and math.isfinite(x) and abs(x)<=1+1e-10 for x in row) for row in states))
        expected='trajectory-'+digest(json.dumps({'model_version':MODEL_VERSION,'parameters':shared['parameters'],'time_grid':shared['time_grid'],'states':states},sort_keys=True,separators=(',',':')).encode())
        require(expected==trajectory_id)
        # Reuse the existing trajectory identity to avoid re-encoding shared arrays.
        cache_key=('source',trajectory_id)
        if cache_key not in trajectory_cache:trajectory_cache[cache_key]=artifact('trajectory',states)
        small=deepcopy({k:v for k,v in summary.items() if k!='simulation_evidence'})
        small['simulation_evidence']={k:deepcopy(v) for k,v in evidence.items() if k not in SHARED_KEYS}
        entry={'configuration_id':configuration_id,'trajectory_artifact_id':trajectory_cache[cache_key],'summary':small}
        identifier='run-'+digest(canonical(entry));runs.setdefault(identifier,{'run_id':identifier,**entry});return identifier
    trajectory_cache={};baseline=run(contextual['baseline']);routes=[]
    for candidate in contextual['candidate_results']:
        require('belief_update' not in candidate)
        context=candidate['route_context']
        normalized={k:deepcopy(context[k]) for k in ('normalized_stressors','mode_specific_stressors','contextual_data_completeness')}
        normalized['segments']=[{k:deepcopy(s[k]) for k in ('segment_id','normalized_stressors','contextual_data_completeness')} for s in context['segments']]
        routes.append({'route_id':candidate['route_id'],'run_id':run(candidate['hotco']),
            'normalization_artifact_id':artifact('normalization',normalized),'difference_from_baseline':deepcopy(candidate['difference_from_baseline'])})
    import numpy,scipy
    implementation={'python':platform.python_version(),'numpy':numpy.__version__,'scipy':scipy.__version__,
        'model_contract_sha256':digest((CONTRACTS_DIR/'hotco-signed-model-v1.json').read_bytes()),
        'source_sha256':{name:digest((Path(BACKEND_DIR)/name).read_bytes()) for name in ('model/hotco_signed_v1.py','model/input_mapping_v4_3.py','model/action_readout.py','model/xai_diagnostics.py','services/contextual_deliberation.py','context/route_context_adapter.py')}}
    inner={'schema_version':'companion-hotco-evidence-v1','search_id':response['search_id'],'implementation':implementation,
        'model_version':contextual['model_version'],'normalization_version':contextual['normalization_version'],
        'contextual_timestamp':contextual['timestamp'],'baseline_run_id':baseline,'runs':list(runs.values()),
        'configurations':list(configs.values()),'route_runs':routes,'artifacts':list(artifacts.values()),
        'warnings':deepcopy(contextual['warnings']),'goal_priorities':deepcopy(contextual.get('goal_priorities')),
        'baseline_role':contextual.get('baseline_role')}
    from passport.mongodb_results_archive import bson_bytes
    require(bson_bytes({k:v for k,v in inner.items() if k!='artifacts'})<8*1024*1024-65536)
    require(sum(a['blob']['raw_bytes'] for a in inner['artifacts'])<=MAX_RAW)
    wire={'schema_version':VERSION,'data_class':'participant','search_id':response['search_id'],'hotco_revision_id':revision_id(response['search_id']),
        'results_reference':{'results_revision_id':results_revision(response['search_id'])},
        'backend_generated_at':generated_at or datetime.now(timezone.utc).isoformat(),
        'passport_input_sha256':input_digest(passport),'packet':pack(inner)}
    wire.update(key_id=signer.key_id,signature=signer.sign(wire));require(len(canonical(wire))<=MAX_BYTES)
    return wire

def validate_evidence(inner):
    require(set(inner)==set('schema_version search_id implementation model_version normalization_version contextual_timestamp baseline_run_id runs configurations route_runs artifacts warnings goal_priorities baseline_role'.split()))
    configs={c['configuration_id']:c for c in inner['configurations']};runs={r['run_id']:r for r in inner['runs']};artifacts={a['artifact_id']:a for a in inner['artifacts']}
    require(len(configs)==len(inner['configurations']) and len(runs)==len(inner['runs']) and len(artifacts)==len(inner['artifacts']))
    require(inner['baseline_run_id'] in runs)
    for config_id,c in configs.items():
        require(set(c)==SHARED_KEYS|{'configuration_id'})
        require(config_id=='configuration-'+digest(canonical({k:v for k,v in c.items() if k!='configuration_id'})))
        require(c['model_version']==MODEL_VERSION and c['parameters']==asdict(SignedHOTCOParameters()))
        require(c['state_columns']==[*NEEDS,*('action:'+m for m in MODES),*('valence:'+m for m in MODES)])
        require(len(c['topology'])==13 and all(len(row)==13 for row in c['topology']))
        require(c['time_reference']=='model_units_not_elapsed_travel_time' and c['dt']==SignedHOTCOParameters().dt)
        require(len(c['time_grid'])>=2 and c['time_grid'][0]==0 and all(b>a for a,b in zip(c['time_grid'],c['time_grid'][1:])))
    total=0
    for identifier,a in artifacts.items():
        require(set(a)=={'artifact_id','kind','blob'} and a['kind'] in ('trajectory','normalization'))
        require(identifier==a['kind']+'-'+a['blob']['raw_sha256'])
        total+=a['blob']['raw_bytes'];require(total<=MAX_RAW)
        value=unpack(a['blob'])
        if a['kind']=='trajectory':
            require(isinstance(value,list) and all(isinstance(row,list) and len(row)==13 and all(type(x) in (int,float) and math.isfinite(x) and abs(x)<=1+1e-10 for x in row) for row in value))
            for run in runs.values():
                if run['trajectory_artifact_id']==identifier:require(len(value)==len(configs[run['configuration_id']]['time_grid'])==run['summary']['simulation_evidence']['sample_count'])
        else:
            require(set(value)=={'normalized_stressors','mode_specific_stressors','contextual_data_completeness','segments'})
    for identifier,run in runs.items():
        require(set(run)=={'run_id','configuration_id','trajectory_artifact_id','summary'})
        require(identifier=='run-'+digest(canonical({k:v for k,v in run.items() if k!='run_id'})))
        require(run['configuration_id'] in configs and run['trajectory_artifact_id'] in artifacts)
        require(artifacts[run['trajectory_artifact_id']]['kind']=='trajectory')
        require(set(run['summary'])==SUMMARY_KEYS)
        require(set(run['summary']['simulation_evidence'])<=EVIDENCE_KEYS-SHARED_KEYS)
        metadata=run['summary']['context_metadata']
        require(metadata['context_policy']=='observations_only' and metadata['learning_applied'] is False and metadata['state_carryover_applied'] is False)
        require(run['summary']['simulation_evidence']['numerical']['actual_horizon']==configs[run['configuration_id']]['time_grid'][-1])
    used={r['trajectory_artifact_id'] for r in runs.values()}
    for route in inner['route_runs']:
        require(set(route)=={'route_id','run_id','normalization_artifact_id','difference_from_baseline'})
        require(route['run_id'] in runs and route['normalization_artifact_id'] in artifacts)
        require(artifacts[route['normalization_artifact_id']]['kind']=='normalization');used.add(route['normalization_artifact_id'])
    require(used==set(artifacts))

def decode_hotco_transfer(raw,signer,*,data_class='participant'):
    try:
        require(len(raw)<=MAX_BYTES);wire=strict_json(raw)
        require(set(wire)==SIGNED_KEYS|{'app_version','response_received_at','key_id','signature'})
        require(next(VALIDATOR.iter_errors(wire),None) is None)
        require(wire['schema_version']==VERSION and wire['data_class']==data_class)
        require(wire['hotco_revision_id']==revision_id(wire['search_id']))
        require(wire['results_reference']=={'results_revision_id':results_revision(wire['search_id'])})
        require(isinstance(wire['app_version'],str) and 0<len(wire['app_version'])<=48)
        instant(wire['backend_generated_at']);instant(wire['response_received_at']);signer.verify(wire)
        inner=unpack(wire['packet']);require(inner['schema_version']=='companion-hotco-evidence-v1' and inner['search_id']==wire['search_id'])
        require(inner['model_version']==MODEL_VERSION)
        validate_evidence(inner)
        return wire,inner
    except InvalidHotcoTransfer:raise
    except (ValueError,TypeError,KeyError,AttributeError,OverflowError,RecursionError,zlib.error):raise InvalidHotcoTransfer('invalid_hotco_evidence') from None
