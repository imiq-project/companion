"""Stateless public-coordinate smoke inputs. Never stores participant data."""
from datetime import datetime,timedelta
from zoneinfo import ZoneInfo
from model.input_mapping_v4_3 import NEEDS,MODES
from passport.adaptive_passport_onboarding import ONBOARDING_SCHEMA_VERSION
from passport.adaptive_hotco_bootstrap import ADAPTIVE_HOTCO_BOOTSTRAP_SCHEMA_VERSION
from passport.goal_priorities import VERSION

def scenario(client):
    def post(path, payload):
        response = client.post('/api/companion/' + path, json=payload)
        data = response.get_json()
        assert response.status_code == 200, (path, response.status_code, data)
        return data
    start = post('adaptive-passport/start', {'schema_version': ONBOARDING_SCHEMA_VERSION,
        'agent_id': 'synthetic-five-goals',
        'responses': {'needs': {n: 4 for n in NEEDS}, 'valences': {m: 0 for m in MODES}}})
    assert start['question_count'] == 20
    complete = post('adaptive-passport/complete', {'schema_version': ONBOARDING_SCHEMA_VERSION,
        'start': start, 'answers': {q['cell_id']: 4 for q in start['questions']}})
    now = datetime.now(ZoneInfo('Europe/Berlin'))
    weekly = {'week_start': (now.date() - timedelta(days=now.weekday())).isoformat(),
        'timezone': 'Europe/Berlin', 'confirmed_at': now.isoformat(),
        'source': 'explicit_weekly_confirmation', 'inherited_from': None,
        'ratings': {n: 4 for n in NEEDS}}
    output = post('adaptive-passport/bootstrap', {'schema_version': ADAPTIVE_HOTCO_BOOTSTRAP_SCHEMA_VERSION,
        'completed_passport': complete, 'availability': {m: True for m in MODES}, 'weekly_goals': weekly})
    passport = output['cognitive_passport']
    assert set(passport['profile']['needs']) == set(NEEDS)
    assert all(set(b) == set(NEEDS) for b in passport['profile']['beliefs'].values())
    assert passport['topology']['n_nodes'] == 13, passport['topology']
    prepared = post('goal-personalization/prepare', {'schema_version': VERSION,
        'previous_passport': passport, 'weekly_goals': weekly,
        'journey_overrides': {'comfort': 7}, 'journey_confirmed_at': now.isoformat()})
    assert prepared['cognitive_passport']['goal_priorities']['effective_ratings']['comfort'] == 7
    return {'start': start, 'complete': complete, 'output': output, 'prepared': prepared}

