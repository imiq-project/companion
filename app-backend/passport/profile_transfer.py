"""Validate the agreed block-03 projection against current onboarding functions.

Synthetic development scope only. Does not enroll participants or grant consent.
"""
from datetime import datetime, date
from hashlib import sha256
import json

from jsonschema import Draft202012Validator
from resources import CONTRACTS_DIR

SCHEMA = json.loads((CONTRACTS_DIR / 'questionnaire-profile-v1.schema.json').read_text(encoding='utf-8'))
VALIDATOR = Draft202012Validator(SCHEMA)
MODEL_HASH = sha256((CONTRACTS_DIR / 'hotco-signed-model-v1.json').read_bytes()).hexdigest()
GOALS = {'env': 'pro_env', 'health_activity': 'physical', 'cost': 'cost', 'time': 'speed', 'comfort_physical': 'comfort'}


class InvalidProfileTransfer(ValueError):
    pass


def require(condition):
    if not condition:
        raise InvalidProfileTransfer('invalid_profile_evidence')


def instant(value):
    parsed = datetime.fromisoformat(value.replace('Z', '+00:00'))
    require(parsed.tzinfo is not None)
    return parsed


def exact_json(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False, allow_nan=False)


def validate_profile_transfer(payload, *, allow_participant=False):
    try:
        require(next(VALIDATOR.iter_errors(payload), None) is None)
        require(payload['data_class'] == ('participant' if allow_participant else 'synthetic'))
        exact_json(payload)
        require(payload['model_contract_sha256'] == MODEL_HASH)
        require(payload['previous_revision_id'] != payload['profile_revision_id'])
        source = payload['source']
        onboarding = source['onboarding_request']
        require(isinstance(onboarding['agent_id'], str) and 0 < len(onboarding['agent_id']) <= 160)
        if not allow_participant:
            require(onboarding['agent_id'].startswith('synthetic-'))
        else:
            require(payload['timing']['questionnaire_timing_status'] != 'illustrative')
        from passport.adaptive_passport_onboarding import start_adaptive_passport, complete_adaptive_passport
        expected_start = start_adaptive_passport(onboarding)
        require(source['calibration_start_response'] == expected_start)
        submission = source['calibration_completion_request']
        require(submission['start'] == expected_start)
        completion = complete_adaptive_passport(submission)
        require(completion == source['calibration_completion_response'])
        bootstrap = source['bootstrap_request']
        require(bootstrap['completed_passport'] == completion)
        snapshot = payload['android_profile_snapshot']
        require(snapshot['availability'] == bootstrap['availability'])
        fingerprint = json.loads(snapshot['fingerprint'])
        require(set(fingerprint) == {'localDate', 'payload'} and fingerprint['payload'] == onboarding)
        date.fromisoformat(fingerprint['localDate'])
        date.fromisoformat(snapshot['weekStart'])
        needs = onboarding['responses']['needs']
        require(snapshot['goalRatings'] == {g: needs[n] for g, n in GOALS.items()})
        original = snapshot['sourceSnapshot']
        require(set(original) == {'plan', 'valences', 'availability', 'habitual_mobility', 'toleranceState', 'inputs'})
        require(original['plan']['points'] == snapshot['goalRatings'])
        require(original['valences']['answers'] == onboarding['responses']['valences'])
        require(original['availability'] == snapshot['availability'])
        from services.passport_service import run_adaptive_hotco_bootstrap
        from passport.adaptive_profile_update import reconstruct_adaptive_participant
        actual = snapshot['response']['cognitive_passport']
        reconstruct_adaptive_participant(actual)
        expected = run_adaptive_hotco_bootstrap(bootstrap)['cognitive_passport']
        require(set(actual) == set(expected))
        # Generated time and opaque generated passport ID vary across runs.
        require(all(actual[k] == expected[k] for k in expected if k not in ('timestamp', 'lineage')))
        require(set(actual['lineage']) == set(expected['lineage']))
        require(all(actual['lineage'][k] == expected['lineage'][k]
                    for k in expected['lineage'] if k != 'passport_id'))
        require(isinstance(actual['lineage']['passport_id'], str) and bool(actual['lineage']['passport_id']))
        instant(actual['timestamp'])
        timing = payload['timing']
        times = {k: instant(timing[k]) if timing[k] is not None else None for k in (
            'questionnaire_started_at', 'questionnaire_completed_at',
            'profile_response_received_at', 'profile_confirmed_at')}
        started, ended = times['questionnaire_started_at'], times['questionnaire_completed_at']
        elapsed = timing['questionnaire_elapsed_seconds']
        if timing['questionnaire_timing_status'] == 'unavailable':
            require(elapsed is None)
        else:
            require(started is not None and ended is not None and elapsed is not None)
            require(type(elapsed) in (float, int) and elapsed >= 0)
            require(abs((ended - started).total_seconds() - elapsed) <= 5)
        require(times['profile_response_received_at'] <= times['profile_confirmed_at'])
        if ended is not None:
            require(ended <= times['profile_response_received_at'])
        require(instant(snapshot['confirmedAt']) == times['profile_confirmed_at'])
        instant(snapshot['createdAt'])
        if 'questionnaire_attempt_id' in snapshot:
            require(snapshot['questionnaire_attempt_id'] == payload['questionnaire_attempt_id'])
        if 'timing' in snapshot:
            require(snapshot['timing'] == timing)
        if 'questionnaire_source' in snapshot:
            require(snapshot['questionnaire_source'] == source)
        return payload
    except InvalidProfileTransfer:
        raise
    except (ValueError, TypeError, KeyError, AttributeError, OverflowError):
        raise InvalidProfileTransfer('invalid_profile_evidence') from None


def decode_profile_transfer(raw, *, allow_participant=False):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise InvalidProfileTransfer('duplicate_json_key')
            result[key] = value
        return result
    try:
        payload = json.loads(raw.decode('utf-8'), object_pairs_hook=unique,
                             parse_constant=lambda _: (_ for _ in ()).throw(InvalidProfileTransfer('nonfinite_json')))
    except (ValueError, UnicodeError):
        raise InvalidProfileTransfer('invalid_json') from None
    return validate_profile_transfer(payload, allow_participant=allow_participant)
