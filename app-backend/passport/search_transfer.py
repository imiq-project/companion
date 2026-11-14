"""Closed block-05 projection validation; results, choices and GPS are excluded."""
from hashlib import sha256
import json
import math
from urllib.parse import urlsplit
from jsonschema import Draft202012Validator
from resources import CONTRACTS_DIR
from climate.climate_impact import normalize_profile
from passport.priority_transfer import instant,same_json,revision_id as priority_revision_id

VERSION='companion-search-metadata-v1'
VALIDATOR=Draft202012Validator(json.loads((CONTRACTS_DIR/'search-metadata-v1.schema.json').read_text(encoding='utf-8')))
CATALOGUE_KEYS=set(('schema_version entry_id catalogue_version source source_url license source_updated_at source_record_sha256 source_file_sha256 make model variant fuel fuel_label engine_cc power_kw transmission profile_values source_ranges reference reference_policy market model_year').split())
REFERENCE_KEYS={'g_co2e_per_vehicle_km','basis','factor_rows','assumptions','factor_version','factor_sha256'}


class InvalidSearchTransfer(ValueError):
    pass


def require(condition):
    if not condition:
        raise InvalidSearchTransfer('invalid_search_evidence')


def revision_id(search_id):
    return 'search-metadata-'+sha256(('search-metadata-v1|'+search_id).encode()).hexdigest()


def climate_profile(value):
    normalized=normalize_profile(value)
    if normalized is None:
        return
    for key in ('make','model','variant'):
        require(key not in value or isinstance(value[key],str) and len(value[key])<=160)
    if value.get('year') is not None:
        require(type(value['year']) is int and 1900<=value['year']<=2100)
    catalogue=value.get('catalogue')
    if catalogue is None:
        return
    require(isinstance(catalogue,dict) and set(catalogue)<=CATALOGUE_KEYS)
    for key,item in catalogue.items():
        if key in ('profile_values','source_ranges','reference'):
            continue
        if item is None:
            continue
        require(type(item) in (str,int,float) and (len(item)<=2000 if isinstance(item,str) else math.isfinite(item)))
    url=urlsplit(catalogue['source_url'])
    require(url.scheme=='https' and url.hostname and not url.username and not url.password)
    ranges=catalogue.get('source_ranges',{})
    require(isinstance(ranges,dict) and set(ranges)<={'litres_per_100km','wh_per_km','tailpipe_co2_g_per_km'})
    for item in ranges.values():
        require(item is None or isinstance(item,list) and len(item)==2 and all(type(n) in (int,float) and math.isfinite(n) and n>=0 for n in item))
    reference=catalogue.get('reference',{})
    require(isinstance(reference,dict) and set(reference)<=REFERENCE_KEYS)
    for key,item in reference.items():
        if key in ('factor_rows','assumptions'):
            require(isinstance(item,list) and len(item)<=64 and all(isinstance(text,str) and len(text)<=500 for text in item))
        elif key=='g_co2e_per_vehicle_km':
            require(type(item) in (int,float) and math.isfinite(item) and item>=0)
        else:
            require(isinstance(item,str) and len(item)<=500)


def decode_search_transfer(raw,*,data_class='participant'):
    def finite_number(text):
        value=float(text);require(math.isfinite(value));return value
    def unique(pairs):
        value={}
        for key,item in pairs:
            require(key not in value);value[key]=item
        return value
    try:
        payload=json.loads(raw.decode('utf-8'),object_pairs_hook=unique,parse_float=finite_number,parse_constant=lambda _:require(False))
        require(next(VALIDATOR.iter_errors(payload),None) is None)
        require(payload['data_class']==data_class)
        require(payload['search_revision_id']==revision_id(payload['search_id']))
        require(payload['priority_reference']['priority_revision_id']==priority_revision_id('predecision_ema',payload['search_id']))
        require(payload['request']['search_id']==payload['search_id'])
        require(instant(payload['completed_at'])>=instant(payload['created_at'])>=instant(payload['request']['datetime']))
        request=payload['request']
        for field,place in (('start','origin'),('stop','destination')):
            require(same_json(request[field],{key:payload['places'][place][key] for key in ('lat','lon')}))
        require(any(request['journey_availability'].values()))
        climate_profile(request['climate_profile'])
        if 'car_parking' in request:
            require(request['journey_availability']['car'])
        if request.get('boarding_plan_datetime') is not None:
            instant(request['boarding_plan_datetime'])
        if request.get('boarding_departure_ms') is not None:
            require(float(request['boarding_departure_ms']).is_integer())
        if any(key.startswith('boarding_') for key in request):
            require(request.get('boarding_stop_id') is not None and request['journey_availability']['pt'])
        if payload['status']=='complete':
            require(payload['source_status']=='complete' and payload['failure'] is None)
        else:
            require(payload['source_status']=='failed' and isinstance(payload['failure'],dict))
            require((payload['failure']['code']=='cancelled')==(payload['status']=='cancelled'))
            if payload['status']=='cancelled':
                require(payload['failure']['http_status'] is None)
        return payload
    except InvalidSearchTransfer:
        raise
    except (ValueError,TypeError,KeyError,AttributeError,OverflowError,RecursionError):
        raise InvalidSearchTransfer('invalid_search_evidence') from None


def validate_search_parent(payload,priority):
    try:
        require(priority['kind']=='predecision_ema' and priority['search_id']==payload['search_id'])
        require(priority['priority_revision_id']==payload['priority_reference']['priority_revision_id'])
        require(same_json(payload['passport_reference'],{key:priority['passport_reference'][key] for key in ('passport_id','revision')}))
        require(same_json(payload['request']['journey_availability'],priority['journey_availability']))
        require(payload['request']['required_model_version']==priority['model_version'] and payload['request']['required_readout_version']==priority['readout_version'])
        require(payload['created_at']==priority['created_at'])
        require(instant(payload['request']['datetime'])>=instant(priority['situated_importance']['confirmed_at']))
        return payload
    except InvalidSearchTransfer:
        raise
    except (ValueError,TypeError,KeyError,AttributeError,OverflowError):
        raise InvalidSearchTransfer('invalid_search_parent') from None
