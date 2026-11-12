"""Passport execution and explicit revision orchestration without HTTP handlers."""
from __future__ import annotations
from typing import Any, Dict, Mapping
from infrastructure.service_config import positive_integer
from model.input_mapping_v4_3 import InputValidationError, parse_participant_input
from passport.passport_xai import build_cognitive_passport
from passport.profile_update import prepare_profile_update
from passport.adaptive_profile_update import prepare_adaptive_profile_update
from passport.adaptive_passport_xai import build_adaptive_cognitive_passport
from passport.adaptive_hotco_bootstrap import prepare_adaptive_hotco_bootstrap
from .model_runtime import engine, model_lock


def _run_one(payload: Any) -> Dict[str, Any]:
    participant = parse_participant_input(payload)
    with model_lock:
        result = engine.simulate(participant)
    return build_cognitive_passport(participant, result, engine)


def run_companion(payload: Any) -> Dict[str, Any]:
    """Run one request, a raw list, or a versioned {requests:[...]} batch."""

    if isinstance(payload, list):
        if not payload:
            raise InputValidationError(["batch list must contain at least one request"])
        if len(payload)>positive_integer('COMPANION_MAX_BATCH_REQUESTS',16):
            raise InputValidationError(["batch exceeds the configured request limit"])
        return {"passports": [_run_one(item)["cognitive_passport"] for item in payload]}

    if isinstance(payload, Mapping) and "requests" in payload:
        requests_value = payload.get("requests")
        if not isinstance(requests_value, list) or not requests_value:
            raise InputValidationError(["requests must be a non-empty array"])
        if len(requests_value)>positive_integer('COMPANION_MAX_BATCH_REQUESTS',16):
            raise InputValidationError(["batch exceeds the configured request limit"])
        return {
            "passports": [
                _run_one(item)["cognitive_passport"] for item in requests_value
            ]
        }

    return _run_one(payload)


def run_profile_update(payload: Any) -> Dict[str, Any]:
    """Create a fresh Passport revision from one explicit re-measurement."""

    participant, lineage = prepare_profile_update(payload)
    with model_lock:
        result = engine.simulate(participant)
    return build_cognitive_passport(
        participant,
        result,
        engine,
        lineage=lineage,
    )


def run_adaptive_hotco_bootstrap(
    payload: Any,
) -> Dict[str, Any]:
    """Run initial HOTCO-CT from a completed Adaptive Passport."""

    payload = dict(payload)
    daily_goals = payload.pop("daily_goals", None)
    weekly_goals = payload.pop("weekly_goals", None)
    adaptive_input = (
        prepare_adaptive_hotco_bootstrap(
            payload
        )
    )

    with model_lock:
        result = engine.simulate(
            adaptive_input.participant
        )

    output = build_adaptive_cognitive_passport(
        adaptive_input,
        result,
        engine,
    )


    if daily_goals is not None:
        from passport.daily_passport import attach_initial_daily
        output = attach_initial_daily(output, daily_goals)
    if weekly_goals is not None:
        from passport.goal_priorities import attach_initial_weekly
        output = attach_initial_weekly(output, weekly_goals)
    return output


def run_adaptive_profile_update(
    payload: Any,
) -> Dict[str, Any]:
    """Create one explicit longitudinal Adaptive Passport revision."""

    adaptive_input, lineage = (
        prepare_adaptive_profile_update(
            payload
        )
    )

    with model_lock:
        result = engine.simulate(
            adaptive_input.participant
        )

    return build_adaptive_cognitive_passport(
        adaptive_input,
        result,
        engine,
        lineage=lineage,
    )
