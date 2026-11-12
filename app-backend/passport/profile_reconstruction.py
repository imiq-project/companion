"""Reconstruct strict questionnaire inputs from immutable Passport measurements using their original scales."""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any, Dict, Mapping, Tuple
from uuid import uuid4

from model.input_mapping_v4_3 import (
    INPUT_SCHEMA_VERSION,
    MODES,
    NEEDS,
    InputValidationError,
    ParticipantInput,
    parse_participant_input,
)


PROFILE_UPDATE_SCHEMA_VERSION = "hotco_ct_profile_update_1.0"


def _fail(message: str) -> None:
    raise InputValidationError([message])


def _mapping(value: Any, path: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        _fail(f"{path} must be a JSON object")
    return value


def _exact_integer(value: Any, minimum: int, maximum: int, path: str) -> int:
    if isinstance(value, bool):
        _fail(f"{path} must be an integer from {minimum} to {maximum}")
    try:
        numeric = float(value)
    except (TypeError, ValueError):
        _fail(f"{path} must be an integer from {minimum} to {maximum}")
    if not numeric.is_integer() or numeric < minimum or numeric > maximum:
        _fail(f"{path} must be an integer from {minimum} to {maximum}")
    return int(numeric)


def _inverse_exact(value: Any, *, scale: float, offset: float, minimum: int, maximum: int, path: str) -> int:
    try:
        numeric = float(value)
    except (TypeError, ValueError):
        _fail(f"{path} is not a valid normalized Passport measurement")
    raw = numeric * scale + offset
    rounded = round(raw)
    if abs(raw - rounded) > 1e-6 or rounded < minimum or rounded > maximum:
        _fail(f"{path} cannot be exactly reconstructed on its original response scale")
    return int(rounded)


def _unwrap_passport(value: Any) -> Mapping[str, Any]:
    root = _mapping(value, "previous_passport")
    if "cognitive_passport" in root:
        return _mapping(root.get("cognitive_passport"), "previous_passport.cognitive_passport")
    return root


def _reconstruct_questionnaire(passport: Mapping[str, Any]) -> Dict[str, Any]:
    if passport.get("schema_version") != "2.0":
        _fail("previous_passport must use Cognitive Passport schema 2.0")
    agent_id = str(passport.get("agent_id", "")).strip()
    if not agent_id:
        _fail("previous_passport.agent_id is required")

    profile = _mapping(passport.get("profile"), "previous_passport.profile")
    needs_profile = _mapping(profile.get("needs"), "previous_passport.profile.needs")
    beliefs_profile = _mapping(profile.get("beliefs"), "previous_passport.profile.beliefs")
    valences_profile = _mapping(profile.get("valences"), "previous_passport.profile.valences")
    availability_profile = _mapping(profile.get("availability"), "previous_passport.profile.availability")

    needs = {
        need: _inverse_exact(
            needs_profile.get(need),
            scale=6.0,
            offset=1.0,
            minimum=1,
            maximum=7,
            path=f"previous_passport.profile.needs.{need}",
        )
        for need in NEEDS
    }

    beliefs: Dict[str, Dict[str, int]] = {}
    for mode in MODES:
        mode_profile = _mapping(
            beliefs_profile.get(mode),
            f"previous_passport.profile.beliefs.{mode}",
        )
        beliefs[mode] = {
            need: _inverse_exact(
                mode_profile.get(need),
                scale=3.0,
                offset=4.0,
                minimum=1,
                maximum=7,
                path=f"previous_passport.profile.beliefs.{mode}.{need}",
            )
            for need in NEEDS
        }

    valences = {
        mode: _inverse_exact(
            valences_profile.get(mode),
            scale=3.0,
            offset=0.0,
            minimum=-3,
            maximum=3,
            path=f"previous_passport.profile.valences.{mode}",
        )
        for mode in MODES
    }

    availability: Dict[str, bool] = {}
    for mode in MODES:
        value = availability_profile.get(mode)
        if not isinstance(value, bool):
            _fail(f"previous_passport.profile.availability.{mode} must be boolean")
        availability[mode] = value

    responses: Dict[str, Any] = {
        "needs": needs,
        "beliefs": beliefs,
        "valences": valences,
        "availability": availability,
    }
    if "environmental_tolerances" in profile:
        tolerance_profile = _mapping(
            profile.get("environmental_tolerances"),
            "previous_passport.profile.environmental_tolerances",
        )
        responses["environmental_tolerances"] = dict(tolerance_profile)
    ranking = passport.get("top_needs_ranking")
    if ranking is not None:
        responses["top_needs_ranking"] = ranking

    return {
        "schema_version": INPUT_SCHEMA_VERSION,
        "agent_id": agent_id,
        "responses": responses,
    }


