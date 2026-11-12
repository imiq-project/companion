"""Fast shadow forecasts from a client-frozen archive; no fitting or activation."""
from infrastructure.longitudinal_names import contract_view as _namespace_view
from copy import deepcopy
from datetime import datetime, timezone

from journeys.contract import timestamp
from .evidence import _route_context, validate_request
from .eligibility import validate_exclusions
from .learned_passport import validate_version, unpack, identity
from .model import MODES, probabilities, initial_location
from .weather_contract import INTENSITY, specification
from .admission import assess_model, decision
from .model import diagnostics

REQUEST = 'longitudinal-prospective-request-v2'
RESULT = 'longitudinal-prospective-forecast-v2'
SOURCE = 'longitudinal-prospective-source-v2'


def compact_route_context(context):
    """Retain readings used by the model; reference repeated source arrays by digest.

    The full raw arrays remain in the original routed response on the device.
    Counts, exclusion reasons, coverage and exact-source digests stay explicit.
    """
    result = deepcopy(context)
    for entry in result.get('route_sensor_audit', {}).get('variables', {}).values():
        raw = entry.pop('raw_readings', [])
        entry['raw_readings_count'] = len(raw)
        entry['raw_readings_digest'] = identity(raw)
    result['projection_version'] = 'longitudinal-route-context-projection-v2'
    result['full_context_digest'] = identity(context)
    return result


def routed_prediction_contexts(result, queried_at):
    """Project every available route, without inventing missing route observations."""
    candidates = {c['route_id']: c for c in (result.get('contextual_deliberation') or {}).get('candidate_results', [])}
    audit = {(a.get('routing_rank'), a.get('mode_key')): a for a in result.get('route_audit', [])}
    projected = []
    for route in result['routing']['routes']:
        if route.get('available') is not True:
            continue
        raw_mode = route['mode_key']
        mode = {'foot': 'walk', 'pt_bus_tram': 'pt', 'car_driver': 'car'}.get(raw_mode, raw_mode)
        if mode not in MODES:
            continue
        candidate = candidates.get(audit.get((route['rank'], raw_mode), {}).get('route_id'))
        journey = {'selection': {'mode_key': raw_mode, 'chosen_at': queried_at},
                   'experience': {'completion': 'as_planned'},
                   'plan': {'route': route, 'context_at_selection': {'snapshot': candidate}}}
        context = _route_context({'completed_at': queried_at}, journey, mode=mode, weather_model_version=INTENSITY)
        projected.append({'prediction_id': f"{route['rank']}-{raw_mode}", 'mode': mode,
                          'context': compact_route_context(context)})
    return projected


def _excluded_model(version, target, mode, registry):
    rows = validate_exclusions(registry)
    for observation in version['evidence_ledger']:
        if observation['target'] != target or observation['mode'] != mode:
            continue
        for row in rows:
            if target in row['targets'] and all(row[k] == value for k, value in (
                    ('journey_id', observation['journey_id']), ('experience_event_id', observation['event_id'])) if k in row):
                return 'archived_evidence_now_excluded_reconstruction_required'
    return None


def validate_source(source, as_of, *, evidence_scope='observed'):
    timestamp(as_of)
    if (not isinstance(source, dict) or _namespace_view(source.get('schema_version')) != _namespace_view(SOURCE)
            or set(source) - {'schema_version', 'selected_at', 'initial_passport', 'learned_version', 'evidence_exclusions', 'selection_rule'}):
        raise ValueError('Invalid frozen source')
    if timestamp(source['selected_at']) > timestamp(as_of):
        raise ValueError('Source selected after forecast cutoff')
    original = source['initial_passport']; registry = source.get('evidence_exclusions')
    validate_exclusions(registry)
    version = source.get('learned_version')
    if version is not None:
        validate_version(version)
        if version.get('evidence_scope', 'observed') != evidence_scope:raise ValueError('Forecast evidence scope mismatch')
        if (version['original_passport_digest'] != identity(original)
                or version['original_passport_id'] != original['lineage']['passport_id']
                or version['subject_id'] != original['agent_id']
                or timestamp(version['evidence_cutoff']) >= timestamp(source['selected_at'])):
            raise ValueError('Archive identity or availability does not match frozen source')
        if any(timestamp(o['confirmed_at']) > timestamp(version['evidence_cutoff']) for o in version['evidence_ledger']):
            raise ValueError('Archive contains evidence after its declared cutoff')
    return original, version, registry


def forecast_archived(data, *, evidence_scope='observed'):
    allowed = {'schema_version', 'search_id', 'as_of', 'source', 'candidates'}
    if not isinstance(data, dict) or _namespace_view(data.get('schema_version')) != _namespace_view(REQUEST) or set(data) - allowed:
        raise ValueError('Invalid archived forecast request')
    if not isinstance(data['search_id'], str) or not data['search_id']:
        raise ValueError('Missing search identity')
    as_of = data['as_of'];source = data['source']
    original, version, registry = validate_source(source, as_of, evidence_scope=evidence_scope)
    candidates = data['candidates']
    if not isinstance(candidates, list) or not 1 <= len(candidates) <= 32:
        raise ValueError('Invalid candidate count')
    prediction_rows = [dict(c, as_of=as_of) for c in candidates]
    _, _, checked = validate_request({'schema_version': 'longitudinal-comfort-pilot-request-v1',
        'subject_id': original['agent_id'], 'initial_passport': original,
        'observations': [], 'prediction_contexts': prediction_rows,
        'weather_model_version': INTENSITY})
    unpacked = {}; results = {}; numerical = {}; assessments = {}
    for target in ('comfort', 'affect'):
        predictions = []
        for candidate in checked:
            mode = candidate['mode']; audit = candidate['context_audit']
            initial = original['profile']['valences'][mode] if target == 'affect' else original['profile']['beliefs'][mode]['comfort']
            models = {'static': {'category_probabilities': probabilities(initial_location(initial)).tolist(),
                      'prediction_usable_for_research': True, 'observation_count': 0,
                      'reference': 'initial_declared_or_ridge_point_expectation', 'initial_normalized_value': initial}}
            archived = version['targets'][target].get(mode) if version else None
            blocked = _excluded_model(version, target, mode, registry) if archived else None
            admission_models = {}
            for name in ('basic', 'contextual'):
                if not archived or blocked:
                    models[name] = {'prediction_usable_for_research': False, 'observation_count': 0,
                                    'reason': blocked or 'archived_posterior_not_available'}
                    admission_models[name] = {'individual_candidate_passed': False,
                        'individual_blockers': [blocked or 'archived_posterior_not_available']}
                    continue
                key = (target, mode, name)
                if key not in unpacked:
                    unpacked[key] = unpack(archived[name])
                    numerical[key] = diagnostics(unpacked[key].chains)
                posterior = unpacked[key]
                assessment_key = (*key, identity(audit))
                if assessment_key not in assessments:
                    history = [o for o in version['evidence_ledger'] if o['target'] == target and o['mode'] == mode]
                    assessments[assessment_key] = assess_model(posterior, history, audit, initial,
                        parameter_diagnostics=numerical[key])
                admission_models[name] = assessments[assessment_key]
                prediction = posterior.predict(audit['values'])
                if target == 'affect':
                    for old, new in (('signed_belief_mean', 'normalized_valence_mean'),
                                     ('signed_belief_median', 'normalized_valence_median'),
                                     ('signed_belief_interval_90', 'normalized_valence_interval_90')):
                        prediction[new] = prediction.pop(old)
                    prediction['expected_raw_valence'] = 3 * prediction['normalized_valence_mean']
                    prediction.pop('expected_ordinal_response')
                    prediction['category_labels_raw_minus3_to_3'] = list(range(-3, 4))
                    prediction['interval_kind'] = 'epistemic_interval_for_expected_normalized_affect_not_single_emotion'
                models[name] = {**prediction, 'diagnostics': posterior.diagnostics,
                    'event_ids': list(posterior.event_ids), 'observation_count': len(posterior.event_ids),
                    'prediction_usable_for_research': bool(posterior.diagnostics['passed'] and
                        (name == 'basic' or audit['contextual_prediction_allowed_for_research'])),
                    'source_passport_id': version['passport_id'], 'weather_model_version': INTENSITY,
                    'activation': {'applied': False, 'reason': 'shadow_only_no_operational_admission'}}
            predictions.append({'prediction_id': candidate['prediction_id'], 'mode': mode,
                'as_of': as_of, 'context_audit': audit, 'models': models,
                'admission_shadow': decision(admission_models, initial, target=target, mode=mode,
                    as_of=as_of, source_digest=identity(source))})
        results[target] = {'predictions': predictions, 'weather_contract': specification()}
    result = {'schema_version': RESULT, 'search_id': data['search_id'], 'as_of': as_of,
        'request_digest': identity(data), 'source_digest': identity(source),
        'source_passport_id': version['passport_id'] if version else None,
        'source_selected_at': source['selected_at'],
        'evidence_cutoff': version['evidence_cutoff'] if version else None,
        'computed_at': datetime.now(timezone.utc).isoformat(), 'targets': results,
        'forecast_method': 'archived_posterior_no_refit_v2',
        'learning_applied_to_active_passport': False, 'hotco_modified': False, 'stored_on_server': False}
    if evidence_scope != 'observed':result['evidence_scope'] = evidence_scope
    return result
