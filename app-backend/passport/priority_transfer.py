"""Block 04: immutable weekly confirmations and predecision EMA snapshots."""
from datetime import datetime
from hashlib import sha256
import json

from jsonschema import Draft202012Validator
from resources import CONTRACTS_DIR
from model.action_readout import READOUT_VERSION
from model.compat import MODEL_VERSION
from passport.goal_priorities import prepare_priorities
from .priority_rules import normalized_from_passport

VERSION='companion-priorities-observation-v1'
GOALS=('pro_env','physical','cost','speed','comfort')
MODES=('walk','bike','pt','car')
MODEL_HASH=sha256((CONTRACTS_DIR/'hotco-signed-model-v1.json').read_bytes()).hexdigest()
VALIDATOR=Draft202012Validator(json.loads((CONTRACTS_DIR/'priorities-observation-v1.schema.json').read_text(encoding='utf-8')))


class InvalidPriorityTransfer(ValueError):
    pass


def require(condition):
    if not condition:
        raise InvalidPriorityTransfer('invalid_priority_evidence')


def instant(value):
    parsed=datetime.fromisoformat(value.replace('Z','+00:00'))
    require(parsed.tzinfo is not None)
    return parsed


def revision_id(kind, logical_id):
    return 'priority-'+sha256((kind+'|'+logical_id).encode()).hexdigest()


def same_json(value, expected):
    """Android may emit 1 instead of 1.0; booleans must never equal numbers."""
    if isinstance(expected,dict):
        return isinstance(value,dict) and set(value)==set(expected) and all(same_json(value[key],item) for key,item in expected.items())
    if isinstance(expected,list):
        return isinstance(value,list) and len(value)==len(expected) and all(same_json(a,b) for a,b in zip(value,expected))
    if isinstance(expected,(int,float)) and not isinstance(expected,bool):
        return type(value) in (int,float) and value==expected
    return type(value) is type(expected) and value==expected


def decode_priority_transfer(raw, *, data_class='participant'):
    def unique(pairs):
        value={}
        for key,item in pairs:
            require(key not in value)
            value[key]=item
        return value
    try:
        value=json.loads(raw.decode('utf-8'),object_pairs_hook=unique,
            parse_constant=lambda _:require(False))
        require(next(VALIDATOR.iter_errors(value),None) is None)
        require(value['data_class']==data_class and value['model_contract_sha256']==MODEL_HASH)
        require(value['model_version']==MODEL_VERSION and value['readout_version']==READOUT_VERSION)
        current=value['contextual_passport_snapshot']
        require(value['passport_reference']=={key:current['lineage'][key]
            for key in ('passport_id','revision','parent_passport_id','origin')})
        logical_id=value['search_id'] if value['kind']=='predecision_ema' else current['lineage']['passport_id']
        require(value['priority_revision_id']==revision_id(value['kind'],logical_id))
        instant(value['created_at'])
        return value
    except InvalidPriorityTransfer:
        raise
    except (ValueError,TypeError,KeyError,AttributeError,OverflowError,RecursionError):
        raise InvalidPriorityTransfer('invalid_priority_evidence') from None


def validate_priority_transfer(value, base):
    """Rebuild from the owner's stored parent; reject arbitrary snapshot additions."""
    try:
        require(value['base_passport_reference']=={key:base['lineage'][key] for key in ('passport_id','revision')})
        ref=value['goal_priorities_reference']
        if value['kind']=='weekly_reference_confirmation':
            require(ref['weekly_reference']['source']=='explicit_weekly_confirmation')
            require(value['situated_importance'] is None and value['search_id'] is None and value['journey_availability'] is None)
            require(ref['journey_overrides']=={} and ref['journey_confirmed_at'] is None)
            require(instant(value['created_at'])==instant(ref['weekly_reference']['confirmed_at']))
            lineage=value['contextual_passport_snapshot']['lineage']
            require(lineage['parent_passport_id']==base['lineage']['passport_id'] and lineage['revision']==base['lineage']['revision']+1)
            require(lineage['passport_id']!=base['lineage']['passport_id'])
        else:
            ema=value['situated_importance']
            require(isinstance(ema,dict) and ema['presentation_order']==list(GOALS))
            require(ref['journey_overrides']==ema['ratings']==ref['effective_ratings'])
            require(ref['journey_confirmed_at']==ema['confirmed_at'])
            require(instant(value['created_at'])>=instant(ema['confirmed_at'])>=instant(ema['opened_at']))
            require(any(value['journey_availability'].values()))
        expected=prepare_priorities({'schema_version':'goal-priorities-5-v1','previous_passport':base,
            'weekly_goals':ref['weekly_reference'],'journey_overrides':ref['journey_overrides'],
            'journey_confirmed_at':ref['journey_confirmed_at']})['cognitive_passport']
        require(same_json(value['contextual_passport_snapshot'],expected))
        require(same_json(ref,expected['goal_priorities']))
        normalized_from_passport(expected)
        return value
    except InvalidPriorityTransfer:
        raise
    except (ValueError,TypeError,KeyError,AttributeError,OverflowError,RecursionError):
        raise InvalidPriorityTransfer('invalid_priority_evidence') from None
