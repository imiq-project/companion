"""Versioned weekly declarations and journey overrides, with no selection mask."""
from copy import deepcopy
from datetime import date, datetime, timedelta
from hashlib import sha256
from zoneinfo import ZoneInfo
import json
from model.input_mapping_v4_3 import NEEDS

VERSION = 'goal-priorities-5-v1'

def digest(value):
    return sha256(json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()).hexdigest()

def ratings(value, complete=True):
    if not isinstance(value, dict) or (set(value) != set(NEEDS) if complete else not set(value) <= set(NEEDS)):
        raise ValueError('Known goal ratings are required; weekly references require all five')
    if any(type(r) is not int or not 1 <= r <= 7 for r in value.values()):
        raise ValueError('Importance ratings must be integers from 1 to 7')
    return deepcopy(value)

def validate_weekly(value):
    if not isinstance(value, dict) or set(value) != {'week_start', 'timezone', 'confirmed_at', 'source', 'inherited_from', 'ratings'}:
        raise ValueError('Invalid weekly goal reference')
    start = date.fromisoformat(value['week_start'])
    if start.weekday() != 0: raise ValueError('Week must start on Monday')
    zone = ZoneInfo(value['timezone'])
    if value['source'] == 'explicit_weekly_confirmation':
        instant = datetime.fromisoformat(value['confirmed_at'].replace('Z', '+00:00'))
        if instant.tzinfo is None or not start <= instant.astimezone(zone).date() < start+timedelta(days=7):
            raise ValueError('Confirmation must belong to the declared local week')
        if value['inherited_from'] is not None: raise ValueError('Explicit confirmation is not inheritance')
    elif value['source'] == 'inherited_explicit_ratings':
        if value['confirmed_at'] is not None or not isinstance(value['inherited_from'], str) or not value['inherited_from']:
            raise ValueError('Inherited values require their source and no new confirmation')
    else: raise ValueError('Unknown weekly source')
    ratings(value['ratings'])
    return deepcopy(value)

def effective_reference(weekly, overrides, confirmed_at=None):
    weekly=validate_weekly(weekly); overrides=ratings(overrides, False)
    if overrides:
        if not isinstance(confirmed_at, str): raise ValueError('Journey adjustments require confirmation')
        instant=datetime.fromisoformat(confirmed_at.replace('Z', '+00:00'))
        start=date.fromisoformat(weekly['week_start']); local=instant.astimezone(ZoneInfo(weekly['timezone'])).date()
        if instant.tzinfo is None or not start <= local < start+timedelta(days=7):
            raise ValueError('Journey adjustments must belong to the reference week')
    elif confirmed_at is not None: raise ValueError('No journey confirmation without adjustments')
    effective={**weekly['ratings'], **overrides}
    return {'schema_version':VERSION, 'weekly_reference_id':'weekly-'+digest(weekly),
            'weekly_reference':weekly, 'journey_overrides':overrides, 'journey_confirmed_at':confirmed_at,
            'effective_ratings':effective, 'normalized':{n:(r-1)/6 for n,r in effective.items()}}

def normalized_from_passport(passport):
    ref=passport['goal_priorities']
    expected=effective_reference(ref['weekly_reference'],ref['journey_overrides'],ref['journey_confirmed_at'])
    if ref != expected or passport.get('weekly_goals') != expected['weekly_reference']:
        raise ValueError('Goal reference does not match its declared inputs')
    if passport['profile']['needs'] != expected['normalized']:
        raise ValueError('Effective needs do not match the goal ratings')
    return deepcopy(expected['normalized'])

