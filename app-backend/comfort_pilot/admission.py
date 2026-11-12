"""Candidate query-level admission, recorded in shadow; no operational activation.

Numerical precision, individual evidence, joint context support, specification
robustness and method qualification are distinct gates. Counts never substitute
for precision. This version cannot qualify a method from a request or simulation.
"""
from infrastructure.longitudinal_names import contract_view as _namespace_view
from copy import deepcopy
from dataclasses import asdict, dataclass
import re
from zoneinfo import ZoneInfo
import numpy as np
from scipy.optimize import linprog

from journeys.contract import timestamp
from .learned_passport import identity
from .model import MODES, _ess, diagnostics, signed_expectation
from .weather_contract import INTENSITY, RAW_V2, design_row_v2

VERSION = 'longitudinal-admission-shadow-v1'


@dataclass(frozen=True)
class Policy:
    probability: float = .95
    practical_margin: float = 1 / 6
    max_interval_width: float = 1 / 3
    rhat_limit: float = 1.01
    min_ess: float = 400
    max_mcse_mean: float = 1 / 60
    joint_radius: float = .75

    def __post_init__(self):
        values = asdict(self)
        if any(type(v) not in (int, float) or not np.isfinite(v) for v in values.values()):
            raise ValueError('Invalid admission thresholds')
        if (not .5 < self.probability < 1 or not 0 < self.practical_margin < 1
                or not 0 < self.max_interval_width <= 2 or not 1 < self.rhat_limit <= 1.01
                or self.min_ess < 400 or not 0 < self.max_mcse_mean <= self.practical_margin / 10
                or self.joint_radius <= 0):
            raise ValueError('Invalid candidate policy')

    def snapshot(self):
        return {'schema_version': VERSION, **asdict(self), 'status': 'candidate_not_empirically_validated',
            'interval': 'central_95_percent_for_coded_expectation_not_single_response',
            'support': 'same_missing_pattern_joint_convex_hull_and_local_transformed_distance',
            'probability_guard': 'two_mcse_or_three_over_effective_draws_candidate',
            'time_model': 'cumulative_full_history_no_forgetting',
            'method_qualified': False, 'production_activation_allowed': False}


def summarize_draws(posterior, context, initial, policy=Policy(), *, parameter_diagnostics=None):
    """Recompute numerical checks from archived draws, never trust a passed flag."""
    row = design_row_v2(context) if posterior.contextual else np.ones(1)
    mu = signed_expectation(posterior.chains @ row, posterior.cutpoint_scale)
    parameter = parameter_diagnostics if parameter_diagnostics is not None else diagnostics(posterior.chains)
    quantity = diagnostics(mu[..., None])
    raw_ess = _ess(mu)
    # Tail ESS for the actual 95% interval, rather than reusing 5/95% tails.
    low, high = np.quantile(mu, [.025, .975])
    tail95 = min(_ess((mu <= low).astype(float)), _ess((mu >= high).astype(float)))
    effective = min(parameter['bulk_ess_min'], parameter['tail_ess_min'],
                    quantity['bulk_ess_min'], quantity['tail_ess_min'], tail95, raw_ess)
    mcse = float(np.std(mu, ddof=1) / np.sqrt(effective)) if effective > 0 else None
    probabilities = {}; guards = {}; indicator_ess = {}
    for name, event in (
        ('increase', mu - initial > policy.practical_margin),
        ('decrease', mu - initial < -policy.practical_margin),
        ('equivalent', abs(mu - initial) <= policy.practical_margin)):
        p = float(event.mean())
        # A constant indicator has no estimable autocorrelation. Record that fact;
        # use the smallest parameter/expectation ESS with a finite-resolution guard.
        ess = min(effective, _ess(event.astype(float))) if 0 < p < 1 else effective
        indicator_ess[name] = float(ess)
        error = max(2 * np.sqrt(p * (1-p) / ess), 3 / ess) if ess > 0 else 1.
        probabilities[name] = p
        guards[name] = float(max(0., p - error))
    rhats = [parameter['rank_split_rhat_max'], quantity['rank_split_rhat_max']]
    numerical = bool(all(r is not None and r < policy.rhat_limit for r in rhats)
        and effective >= policy.min_ess and mcse is not None and mcse <= policy.max_mcse_mean)
    precise = bool(high - low <= policy.max_interval_width)
    direction = next((name for name in ('increase', 'decrease', 'equivalent')
                      if guards[name] >= policy.probability and indicator_ess[name] >= policy.min_ess), 'indeterminate')
    return {'mean': float(mu.mean()), 'median': float(np.median(mu)),
        'expectation_interval_95': [float(low), float(high)], 'interval_width': float(high-low),
        'interval_kind': 'epistemic_coded_expectation_not_individual_response',
        'initial_reference': initial, 'probabilities': probabilities,
        'numerical_lower_guards': guards, 'indicator_effective_draws': indicator_ess,
        'mcse_mean': mcse, 'effective_draws_min': float(effective),
        'parameter_diagnostics': parameter, 'expectation_diagnostics': quantity,
        'tail_ess_95': float(tail95), 'numerical_passed': numerical,
        'precision_passed': precise, 'evidence_classification': direction,
        'individual_quantitative_passed': bool(numerical and precise and direction != 'indeterminate')}


def context_support(history, context, policy=Policy()):
    """Joint support is stricter than independent min/max or a weather-present flag.

    Distances use the model's fixed transforms (not empirically certified units).
    A single matching situation can establish geometry, never sufficient precision.
    """
    query = {key: context.get(key) for key in RAW_V2}
    q = design_row_v2(query)[1:1+len(RAW_V2)]
    pattern = [key for key in RAW_V2 if query[key] is not None]
    eligible = [o for o in history if [key for key in RAW_V2 if o['context'].get(key) is not None] == pattern]
    common = {'observed_features': pattern, 'same_pattern_observations': len(eligible),
        'journeys': len({o['journey_id'] for o in history}),
        'events': len({o['event_id'] for o in history}),
        'days': len({timestamp(o['confirmed_at']).astimezone(ZoneInfo('Europe/Berlin')).date().isoformat() for o in history}),
        'day_timezone': 'Europe/Berlin',
        'independent_experience_count_established': False,
        'missing_is_zero': False, 'radius_candidate': policy.joint_radius}
    if not eligible:return {**common, 'passed': False, 'reason': 'missing_pattern_not_observed',
                            'joint_hull_contains_query': False, 'nearest_distance': None}
    indexes = [RAW_V2.index(k) for k in pattern]
    x = np.array([design_row_v2(o['context'])[1:1+len(RAW_V2)][indexes] for o in eligible])
    q = q[indexes]
    distances = np.linalg.norm(x-q, axis=1)
    nearest = float(distances.min())
    # Convex combination: no learned scale, univariate boxes or inversion of a
    # singular covariance. Duplicates don't manufacture additional dimensions.
    a = np.vstack([np.ones(len(x)), x.T]); b = np.r_[1., q]
    solution = linprog(np.zeros(len(x)), A_eq=a, b_eq=b, bounds=(0, None), method='highs')
    inside = bool(solution.success and np.max(abs(a @ solution.x-b)) <= 1e-8)
    passed = inside and nearest <= policy.joint_radius
    return {**common, 'passed': passed, 'joint_hull_contains_query': inside,
        'nearest_distance': nearest, 'local_observations': int((distances <= policy.joint_radius).sum()),
        'local_journeys': len({o['journey_id'] for o, d in zip(eligible, distances) if d <= policy.joint_radius}),
        'reason': None if passed else 'joint_extrapolation' if not inside else 'sparse_joint_neighborhood'}


def assess_model(posterior, history, context_audit, initial, policy=Policy(), *, parameter_diagnostics=None):
    if _namespace_view(posterior.weather_model_version) != _namespace_view(INTENSITY):raise ValueError('Admission requires V2 weather semantics')
    expected = [o['event_id'] for o in history]
    if list(posterior.event_ids) != expected or not expected:raise ValueError('Admission model/evidence mismatch')
    summary = summarize_draws(posterior, context_audit['values'], initial, policy, parameter_diagnostics=parameter_diagnostics)
    support = context_support(history, context_audit['values'], policy)
    reasons = []
    if not summary['numerical_passed']:reasons.append('numerical_precision_insufficient')
    if not summary['precision_passed']:reasons.append('expectation_interval_too_wide')
    if summary['evidence_classification'] == 'indeterminate':reasons.append('change_and_equivalence_indeterminate')
    if not support['passed']:reasons.append(support['reason'])
    if posterior.contextual:
        if not context_audit['contextual_prediction_allowed_for_research']:reasons.append('no_usable_weather')
        if not context_audit['semantic_metadata_complete']:reasons.append('weather_semantics_incomplete')
    return {'summary': summary, 'support': support,
            'evidence_first_confirmed_at': min(history, key=lambda o: timestamp(o['confirmed_at']))['confirmed_at'],
            'evidence_last_confirmed_at': max(history, key=lambda o: timestamp(o['confirmed_at']))['confirmed_at'],
            'temporal_status': 'cumulative_no_drift_or_expiry_validation',
            'individual_candidate_passed': not reasons,
            'individual_blockers': reasons}


def decision(models, initial, *, target, mode, as_of, source_digest, policy=Policy()):
    """Pure receipt constructor. Method promotion is unavailable in this release."""
    if target not in ('comfort', 'affect') or mode not in MODES:raise ValueError('Invalid admission target/mode')
    if (type(initial) not in (int, float) or not np.isfinite(initial) or abs(initial) > 1
            or not isinstance(source_digest, str) or not re.fullmatch('[0-9a-f]{64}', source_digest)):
        raise ValueError('Invalid admission reference/source')
    timestamp(as_of)
    candidates = [name for name in ('contextual', 'basic') if models.get(name, {}).get('individual_candidate_passed')]
    candidate = candidates[0] if candidates else None
    classification = models[candidate]['summary']['evidence_classification'] if candidate else 'indeterminate'
    state = ('original_practical_equivalence' if classification == 'equivalent'
             else 'learned_'+candidate if candidate else 'original_insufficient_evidence')
    snapshot = policy.snapshot()
    receipt = {'schema_version': VERSION, 'policy': snapshot, 'policy_digest': identity(snapshot),
        'target': target, 'mode': mode, 'as_of': as_of, 'source_digest': source_digest,
        'shadow_state_before_method_gates': state,
        'shadow_candidate_model': candidate, 'shadow_candidate_value': models[candidate]['summary']['mean'] if candidate else None,
        'model_assessments': deepcopy(models),
        'method_blockers': ['prospective_domain_qualification_not_available',
                            'prior_cutpoint_and_influence_robustness_not_available'],
        'operational_source': 'original', 'operational_value': initial,
        'operational_reason': 'method_not_qualified_shadow_only',
        'original_empirically_confirmed': False,
        'active_passport_modified': False, 'hotco_modified': False, 'activation_applied': False}
    return {**receipt, 'receipt_digest': identity(receipt)}


def validate_receipt(receipt):
    if _namespace_view(receipt.get('schema_version')) != _namespace_view(VERSION):raise ValueError('Unknown admission receipt')
    if identity({k: v for k, v in receipt.items() if k != 'receipt_digest'}) != receipt['receipt_digest']:
        raise ValueError('Admission receipt was modified')
    if identity(receipt['policy']) != receipt['policy_digest']:raise ValueError('Policy digest mismatch')
    if (receipt['operational_source'] != 'original' or receipt['operational_reason'] != 'method_not_qualified_shadow_only'
            or any(receipt[k] is not False for k in ('active_passport_modified', 'hotco_modified', 'activation_applied'))):
        raise ValueError('Shadow policy cannot activate learned beliefs')
    if _namespace_view(receipt['policy']) != _namespace_view(Policy(**{k: receipt['policy'][k] for k in asdict(Policy())}).snapshot()):
        raise ValueError('Unknown admission policy semantics')
    timestamp(receipt['as_of'])
    if receipt['target'] not in ('comfort', 'affect') or receipt['mode'] not in MODES:raise ValueError('Invalid admission identity')
    expected = decision(receipt['model_assessments'], receipt['operational_value'], target=receipt['target'],
        mode=receipt['mode'], as_of=receipt['as_of'], source_digest=receipt['source_digest'],
        policy=Policy(**{k: receipt['policy'][k] for k in asdict(Policy())}))
    # Reconstruct in the stored spelling before comparing signed documents.
    expected['schema_version'] = receipt['schema_version']
    expected['policy']['schema_version'] = receipt['policy']['schema_version']
    expected['policy_digest'] = identity(expected['policy'])
    expected.pop('receipt_digest')
    expected['receipt_digest'] = identity(expected)
    if receipt != expected:raise ValueError('Admission receipt is inconsistent')
    return receipt
