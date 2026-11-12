"""Versioned weekly declarations and journey overrides, with no selection mask."""
from copy import deepcopy
from datetime import date, datetime, timedelta
from hashlib import sha256
from zoneinfo import ZoneInfo
import json
from model.input_mapping_v4_3 import NEEDS

from .priority_rules import VERSION, digest, ratings, validate_weekly, effective_reference, normalized_from_passport


def prepare_priorities(payload):
    from passport.adaptive_reconstruction import reconstruct_adaptive_participant
    from passport.measurement_basis import estimation_basis
    if not isinstance(payload, dict) or set(payload) != {'schema_version','previous_passport','weekly_goals','journey_overrides','journey_confirmed_at'} or payload['schema_version'] != VERSION:
        raise ValueError('Invalid goal personalization request')
    previous=payload['previous_passport']
    reconstruct_adaptive_participant(previous)  # validate the old immutable measurements first
    ref=effective_reference(payload['weekly_goals'],payload['journey_overrides'],payload['journey_confirmed_at'])
    weekly=ref['weekly_reference']
    if weekly['source']=='inherited_explicit_ratings' and weekly != previous.get('weekly_goals'):
        old_week=previous.get('weekly_goals')
        declared=deepcopy(old_week['ratings'] if old_week else previous['observed_measurements'].get('needs_raw_1_to_7',{}))
        if not old_week:
            legacy=previous.get('focal_goals',{})
            declared.update(legacy.get('ratings',{}));declared.update(legacy.get('importance_overrides',{}))
        origin=previous.get('goal_priorities',{}).get('weekly_reference_id',previous['lineage']['passport_id'])
        if weekly['ratings']!=declared or weekly['inherited_from']!=origin:
            raise ValueError('Inherited ratings must match their declared source; explicitly confirm any changes')
    if previous.get('goal_priorities') == ref and 'focal_goals' not in previous:
        return {'cognitive_passport':deepcopy(previous)}
    current=deepcopy(previous); parent=previous['lineage']; event_id='priorities-'+digest(ref)
    current['belief_estimation_inputs']=estimation_basis(previous)
    current['profile']['needs']=deepcopy(ref['normalized'])
    # Original raw measurements, all beliefs and mode valences stay immutable.
    for key in ('focal_goals','focal_deliberation','daily_goals','deliberation','process_diagnostics','xai_diagnostics','adaptive_questioning'):
        current.pop(key,None)
    current['measurement_provenance'].pop('focal_goals',None)
    current['measurement_provenance']['needs']={'source':'explicit_reference_and_journey_ratings', 'scale':'ordinal_importance_1_7', 'reference_id':ref['weekly_reference_id']}
    current['weekly_goals']=deepcopy(ref['weekly_reference']);current['goal_priorities']=ref
    current['lineage']={**deepcopy(parent),'passport_id':event_id+'-'+digest(parent['passport_id'])[:16],
                        'parent_passport_id':parent['passport_id'],'revision':parent['revision']+1,
                        'origin':'goal_personalization','last_update_event':{'event_id':event_id,'kind':'goal_priorities_prepared','learning_applied':False}}
    current['longitudinal_policy']='explicit_goal_ratings_no_terminal_state_carryover'
    current['adaptive_questioning']={'question_needed':False,'reason':'goal_reference_revision'}
    reconstruct_adaptive_participant(current)  # round-trip validates frozen belief reconstruction
    return {'cognitive_passport':current}



def attach_initial_weekly(output, weekly):
    """The first externally visible Passport is a root, not a child of a discarded bootstrap."""
    old=output['cognitive_passport']
    if validate_weekly(weekly)['ratings'] != old['observed_measurements'].get('needs_raw_1_to_7'):
        raise ValueError('Initial weekly ratings must match the questionnaire')
    updated=prepare_priorities({'schema_version':VERSION,'previous_passport':old,'weekly_goals':weekly,
                                'journey_overrides':{},'journey_confirmed_at':None})
    current=updated['cognitive_passport']
    current['lineage']={**deepcopy(old['lineage']), 'passport_id':current['lineage']['passport_id'],
                        'origin':'initial_weekly_confirmation'}
    return updated
