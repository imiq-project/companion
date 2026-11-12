"""Explicit immutable Passport revisions from validated prior measurements."""
from __future__ import annotations
from datetime import datetime, timezone
from typing import Any, Dict, Mapping, Tuple
from uuid import uuid4
from model.input_mapping_v4_3 import INPUT_SCHEMA_VERSION, MODES, NEEDS, InputValidationError, ParticipantInput, parse_participant_input

from .profile_reconstruction import (
    PROFILE_UPDATE_SCHEMA_VERSION, _fail, _mapping, _exact_integer, _inverse_exact, _unwrap_passport, _reconstruct_questionnaire,
)

def _candidate(passport: Mapping[str, Any]) -> Mapping[str, Any]:
    adaptive = _mapping(passport.get("adaptive_questioning"), "previous_passport.adaptive_questioning")
    if adaptive.get("question_needed") is not True:
        _fail("previous_passport has no active micro-question candidate")
    candidate = _mapping(adaptive.get("candidate"), "previous_passport.adaptive_questioning.candidate")
    if candidate.get("scope") != "profile":
        _fail("only explicit profile-scope micro-questions can update the Passport")
    return candidate


def _same_target(submitted: Mapping[str, Any], expected: Mapping[str, Any]) -> bool:
    kind = expected.get("kind")
    if submitted.get("kind") != kind:
        return False
    if kind == "need":
        return submitted.get("need") == expected.get("need")
    if kind == "valence":
        return submitted.get("mode") == expected.get("mode")
    if kind == "belief":
        return (
            submitted.get("mode") == expected.get("mode")
            and submitted.get("need") == expected.get("need")
        )
    return False


def _previous_user_rating(
    responses: Mapping[str, Any],
    target: Mapping[str, Any],
) -> int:
    kind = str(target.get("kind"))
    if kind == "need":
        need = str(target.get("need"))
        if need not in NEEDS:
            _fail("candidate need is not recognized")
        return int(responses["needs"][need])
    if kind == "belief":
        mode = str(target.get("mode"))
        need = str(target.get("need"))
        if mode not in MODES or need not in NEEDS:
            _fail("candidate belief target is not recognized")
        return int(responses["beliefs"][mode][need])
    if kind == "valence":
        mode = str(target.get("mode"))
        if mode not in MODES:
            _fail("candidate valence mode is not recognized")
        return int(responses["valences"][mode]) + 4
    _fail("candidate target kind is not supported")


def prepare_profile_update(payload: Any) -> Tuple[ParticipantInput, Dict[str, Any]]:
    """Validate one explicit micro-answer and return new model input + lineage."""

    root = _mapping(payload, "$")
    allowed = {"schema_version", "previous_passport", "update"}
    unknown = sorted(str(key) for key in root if key not in allowed)
    if unknown:
        _fail("$ contains unknown fields: " + ", ".join(unknown))
    if root.get("schema_version") != PROFILE_UPDATE_SCHEMA_VERSION:
        _fail(f"schema_version must be '{PROFILE_UPDATE_SCHEMA_VERSION}'")

    previous = _unwrap_passport(root.get("previous_passport"))
    lineage = _mapping(previous.get("lineage"), "previous_passport.lineage")
    previous_id = str(lineage.get("passport_id", "")).strip()
    if not previous_id:
        _fail("previous_passport.lineage.passport_id is required")
    previous_revision = _exact_integer(
        lineage.get("revision"), 1, 1_000_000, "previous_passport.lineage.revision"
    )

    expected_candidate = _candidate(previous)
    expected_question_id = str(expected_candidate.get("question_id", ""))
    expected_target = _mapping(expected_candidate.get("target"), "candidate.target")

    update = _mapping(root.get("update"), "update")
    if update.get("explicit_user_confirmation") is not True:
        _fail("update.explicit_user_confirmation must be true")
    if str(update.get("question_id", "")) != expected_question_id:
        _fail("update.question_id does not match the active Passport micro-question")
    submitted_target = _mapping(update.get("target"), "update.target")
    if not _same_target(submitted_target, expected_target):
        _fail("update.target does not match the active Passport micro-question target")

    new_user_rating = _exact_integer(update.get("raw_rating"), 1, 7, "update.raw_rating")
    questionnaire = _reconstruct_questionnaire(previous)
    responses = questionnaire["responses"]

    previous_user_rating = _previous_user_rating(responses, expected_target)
    candidate_previous_rating = _exact_integer(
        expected_target.get("current_raw_rating"), 1, 7, "candidate.target.current_raw_rating"
    )
    if candidate_previous_rating != previous_user_rating:
        _fail(
            "candidate.target.current_raw_rating does not match the previous Passport measurement"
        )

    kind = str(expected_target.get("kind"))
    if kind == "need":
        responses["needs"][str(expected_target.get("need"))] = new_user_rating
    elif kind == "belief":
        responses["beliefs"][str(expected_target.get("mode"))][str(expected_target.get("need"))] = new_user_rating
    elif kind == "valence":
        responses["valences"][str(expected_target.get("mode"))] = new_user_rating - 4
    else:  # already guarded by _previous_user_rating, kept explicit for readability
        _fail("candidate target kind is not supported")

    new_participant = parse_participant_input(questionnaire)
    new_revision = previous_revision + 1
    timestamp = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    event: Dict[str, Any] = {
        "revision": new_revision,
        "timestamp": timestamp,
        "source": "explicit_user_response",
        "scope": "profile",
        "question_id": expected_question_id,
        "trigger": expected_candidate.get("trigger"),
        "target": {
            key: value
            for key, value in expected_target.items()
            if key in {"kind", "mode", "need"}
        },
        "previous_raw_rating": previous_user_rating,
        "new_raw_rating": new_user_rating,
        "delta_raw_rating": new_user_rating - previous_user_rating,
        "value_changed": new_user_rating != previous_user_rating,
        "explicit_user_confirmation": True,
    }

    previous_history = lineage.get("measurement_history", [])
    if not isinstance(previous_history, list):
        _fail("previous_passport.lineage.measurement_history must be an array")
    history = [dict(item) for item in previous_history if isinstance(item, Mapping)]
    history.append(event)

    new_lineage: Dict[str, Any] = {
        "passport_id": str(uuid4()),
        "revision": new_revision,
        "parent_passport_id": previous_id,
        "origin": "explicit_micro_question_update",
        "measurement_history": history,
        "last_update_event": event,
    }
    return new_participant, new_lineage
