"""Explicit immutable Passport revisions from validated prior measurements."""
from __future__ import annotations
from passport.need_allocation import validate_need_allocation
from datetime import datetime, timezone
import math
from typing import Any, Dict, Mapping, Tuple
from uuid import uuid4
from passport.adaptive_hotco_adapter import ADAPTIVE_HOTCO_ADAPTER_VERSION, AdaptiveHOTCOInput
from passport.adaptive_passport_v1 import AdaptivePassportError, AdaptivePassportV1
from model.availability_resolver import resolve_user_declared_availability
from model.input_mapping_v4_3 import tolerance_dimensions, MODES, NEEDS, ParticipantInput

from .adaptive_reconstruction import (
    ADAPTIVE_PROFILE_UPDATE_SCHEMA_VERSION, _RUNTIME, AdaptiveProfileUpdateError, _fail, _mapping, _integer, _unwrap_passport, _same_target, _active_candidate, _allocation_from_passport, _normalized_need_measurements, _raw_measurements, _availability_from_passport, _tolerances_from_passport, reconstruct_adaptive_participant,
)

def prepare_adaptive_profile_update(
    payload: Any,
) -> Tuple[
    AdaptiveHOTCOInput,
    Dict[str, Any],
]:
    """Prepare one explicit Adaptive Passport revision."""

    root = _mapping(
        payload,
        "$",
    )

    allowed = {
        "schema_version",
        "previous_passport",
        "update",
    }

    unknown = sorted(
        str(
            key
        )
        for key in root
        if key not in allowed
    )

    if unknown:
        _fail(
            "$ contains unknown fields: "
            + ", ".join(
                unknown
            )
        )

    if (
        root.get(
            "schema_version"
        )
        != ADAPTIVE_PROFILE_UPDATE_SCHEMA_VERSION
    ):
        _fail(
            "schema_version must be "
            f"'{ADAPTIVE_PROFILE_UPDATE_SCHEMA_VERSION}'"
        )

    previous = _unwrap_passport(
        root.get(
            "previous_passport"
        )
    )

    adaptive_extension = _mapping(
        previous.get(
            "adaptive_passport"
        ),
        (
            "previous_passport."
            "adaptive_passport"
        ),
    )

    if (
        adaptive_extension.get(
            "artifact_sha256"
        )
        != _RUNTIME.artifact_sha256
    ):
        _fail(
            "previous Passport uses a different "
            "Adaptive Passport artifact"
        )

    lineage = _mapping(
        previous.get(
            "lineage"
        ),
        "previous_passport.lineage",
    )

    previous_id = str(
        lineage.get(
            "passport_id",
            "",
        )
    ).strip()

    if not previous_id:
        _fail(
            "previous_passport.lineage."
            "passport_id is required"
        )

    previous_revision = _integer(
        lineage.get(
            "revision"
        ),
        minimum=1,
        maximum=1_000_000,
        path=(
            "previous_passport."
            "lineage.revision"
        ),
    )

    expected_candidate = (
        _active_candidate(
            previous
        )
    )

    expected_question_id = str(
        expected_candidate.get(
            "question_id",
            "",
        )
    ).strip()

    if not expected_question_id:
        _fail(
            "active adaptive question "
            "has no question_id"
        )

    expected_target = _mapping(
        expected_candidate.get(
            "target"
        ),
        (
            "previous_passport."
            "adaptive_questioning."
            "candidate.target"
        ),
    )

    update = _mapping(
        root.get(
            "update"
        ),
        "update",
    )

    if (
        update.get(
            "explicit_user_confirmation"
        )
        is not True
    ):
        _fail(
            "update.explicit_user_confirmation "
            "must be true"
        )

    if (
        str(
            update.get(
                "question_id",
                "",
            )
        )
        != expected_question_id
    ):
        _fail(
            "update.question_id does not match "
            "the active adaptive question"
        )

    submitted_target = _mapping(
        update.get(
            "target"
        ),
        "update.target",
    )

    if not _same_target(
        submitted_target,
        expected_target,
    ):
        _fail(
            "update.target does not match "
            "the active adaptive question"
        )

    new_user_rating = _integer(
        update.get(
            "raw_rating"
        ),
        minimum=1,
        maximum=7,
        path="update.raw_rating",
    )

    (
        raw_needs,
        raw_valences,
        raw_beliefs,
    ) = _raw_measurements(
        previous
    )

    kind = str(
        expected_target.get(
            "kind",
            "",
        )
    )

    previous_raw_rating = None
    previous_measurement_status = str(
        expected_target.get(
            "measurement_status",
            "observed",
        )
    )

    previous_profile = _mapping(
        previous.get(
            "profile"
        ),
        "previous_passport.profile",
    )

    previous_normalized_value = None

    # Apply exactly one explicit measurement.

    if kind == "need":
        if _allocation_from_passport(previous) is not None:
            _fail("Weekly need weights must be changed by reallocating the complete 10-point budget")

        need = str(
            expected_target.get(
                "need",
                "",
            )
        )

        if need not in NEEDS:
            _fail(
                "candidate need is not recognized"
            )

        previous_raw_rating = (
            raw_needs[
                need
            ]
        )

        raw_needs[
            need
        ] = new_user_rating

    elif kind == "valence":

        mode = str(
            expected_target.get(
                "mode",
                "",
            )
        )

        if mode not in MODES:
            _fail(
                "candidate valence mode "
                "is not recognized"
            )

        # User-facing response is 1..7.
        previous_raw_rating = (
            raw_valences[
                mode
            ]
            + 4
        )

        raw_valences[
            mode
        ] = (
            new_user_rating
            - 4
        )

    elif kind == "belief":

        mode = str(
            expected_target.get(
                "mode",
                "",
            )
        )

        need = str(
            expected_target.get(
                "need",
                "",
            )
        )

        if (
            mode not in MODES
            or need not in NEEDS
        ):
            _fail(
                "candidate belief target "
                "is not recognized"
            )

        cell_id = (
            f"{mode}__{need}"
        )

        if cell_id in raw_beliefs:

            previous_raw_rating = (
                raw_beliefs[
                    cell_id
                ]
            )

            if (
                previous_measurement_status
                != "observed"
            ):
                _fail(
                    "candidate provenance conflicts "
                    "with existing raw belief"
                )

        else:

            if (
                previous_measurement_status
                != "estimated"
            ):
                _fail(
                    "candidate says belief is observed "
                    "but no raw measurement exists"
                )

            beliefs_profile = _mapping(
                previous_profile.get(
                    "beliefs"
                ),
                (
                    "previous_passport."
                    "profile.beliefs"
                ),
            )

            mode_profile = _mapping(
                beliefs_profile.get(
                    mode
                ),
                (
                    "previous_passport."
                    f"profile.beliefs.{mode}"
                ),
            )

            try:
                previous_normalized_value = float(
                    mode_profile[
                        need
                    ]
                )
            except (
                KeyError,
                TypeError,
                ValueError,
            ) as exc:
                raise AdaptiveProfileUpdateError(
                    "previous estimated belief "
                    "cannot be read"
                ) from exc

        raw_beliefs[
            cell_id
        ] = new_user_rating

    else:
        _fail(
            "candidate target kind "
            "is not supported"
        )

    # Rebuild the dense profile from raw observations.

    normalized_needs = _normalized_need_measurements(previous, raw_needs)

    normalized_valences = {
        mode:
            raw_valences[
                mode
            ]
            / 3.0
        for mode in MODES
    }

    try:
        personalized = (
            _RUNTIME.personalize(
                needs=
                    normalized_needs,

                valences=
                    normalized_valences,

                observed_answers=
                    raw_beliefs,

                answers_are_raw_1_to_7=
                    True,

                # Longitudinal mode may contain
                # >4 observed belief measurements.
                require_complete_k=
                    False,
            )
        )

    except AdaptivePassportError as exc:
        raise AdaptiveProfileUpdateError(
            str(exc)
        ) from exc

    resolved_availability = (
        _availability_from_passport(
            previous
        )
    )

    tolerances = (
        _tolerances_from_passport(
            previous
        )
    )

    sparse_raw_beliefs: Dict[
        str,
        Dict[str, int],
    ] = {
        mode: {}
        for mode in MODES
    }

    for cell_id, raw_value in (
        raw_beliefs.items()
    ):

        mode, need = (
            cell_id.split(
                "__",
                1,
            )
        )

        sparse_raw_beliefs[
            mode
        ][
            need
        ] = int(
            raw_value
        )

    participant = ParticipantInput(
        needs_allocation=_allocation_from_passport(previous),
        agent_id=
            str(
                previous.get(
                    "agent_id"
                )
            ),

        raw_needs=
            dict(
                raw_needs
            ),

        raw_beliefs=
            sparse_raw_beliefs,

        raw_valences=
            dict(
                raw_valences
            ),

        raw_availability=
            resolved_availability.by_mode,

        availability_provenance=
            resolved_availability.provenance,

        raw_ranking=
            None,

        needs=[
            normalized_needs[
                need
            ]
            for need in NEEDS
        ],

        beliefs=[
            [
                float(
                    personalized[
                        "beliefs"
                    ][
                        mode
                    ][
                        need
                    ]
                )
                for need in NEEDS
            ]
            for mode in MODES
        ],

        valences=[
            normalized_valences[
                mode
            ]
            for mode in MODES
        ],

        availability=
            resolved_availability.vector,

        ranking=
            None,

        environmental_tolerances=
            tolerances,
    )

    observed_belief_count = len(
        raw_beliefs
    )

    estimated_belief_count = (
        20
        - observed_belief_count
    )

    adaptive_input = AdaptiveHOTCOInput(
        participant=
            participant,

        measurement_provenance={
            "needs": {
                "source":
                    "observed",

                "count":
                    len(
                        NEEDS
                    ),
            },

            "valences": {
                "source":
                    "observed",

                "count":
                    len(
                        MODES
                    ),
            },

            "beliefs":
                personalized[
                    "belief_provenance"
                ],

            "belief_counts": {
                "observed":
                    observed_belief_count,

                "estimated":
                    estimated_belief_count,
            },
        },

        adaptive_metadata={
            "schema_version":
                ADAPTIVE_HOTCO_ADAPTER_VERSION,

            "estimator":
                "adaptive_cognitive_passport_v1",

            "artifact_sha256":
                _RUNTIME.artifact_sha256,

            "normalized_beliefs_used_by_hotco":
                20,

            "raw_belief_measurements":
                observed_belief_count,

            "estimated_beliefs":
                estimated_belief_count,

            "fabricated_raw_beliefs":
                0,

            "availability_policy":
                resolved_availability
                .provenance[
                    "policy"
                ],

            "longitudinal_update":
                True,
        },
    )

    # Append-only lineage.

    new_revision = (
        previous_revision
        + 1
    )

    timestamp = (
        datetime.now(
            timezone.utc
        )
        .isoformat()
        .replace(
            "+00:00",
            "Z",
        )
    )

    if kind == "belief":

        transition = (
            "estimated_to_observed"
            if previous_measurement_status
            == "estimated"
            else
            "observed_to_observed_remeasurement"
        )

    else:
        transition = (
            "observed_to_observed_remeasurement"
        )

    event: Dict[
        str,
        Any,
    ] = {
        "revision":
            new_revision,

        "timestamp":
            timestamp,

        "source":
            "explicit_user_response",

        "scope":
            "profile",

        "question_id":
            expected_question_id,

        "trigger":
            expected_candidate.get(
                "trigger"
            ),

        "target": {
            key:
                value
            for key, value
            in expected_target.items()
            if key in {
                "kind",
                "mode",
                "need",
            }
        },

        "measurement_transition":
            transition,

        "previous_measurement_status":
            previous_measurement_status,

        "previous_raw_rating":
            previous_raw_rating,

        "previous_normalized_value":
            previous_normalized_value,

        "new_raw_rating":
            new_user_rating,

        "new_normalized_value":
            (
                (
                    new_user_rating
                    - 4.0
                )
                / 3.0
                if kind == "belief"
                else (
                    (
                        new_user_rating
                        - 1.0
                    )
                    / 6.0
                    if kind == "need"
                    else (
                        new_user_rating
                        - 4.0
                    )
                    / 3.0
                )
            ),

        "explicit_user_confirmation":
            True,

        "automatic_update":
            False,

        "observed_belief_count_after_update":
            observed_belief_count,

        "estimated_belief_count_after_update":
            estimated_belief_count,
    }

    previous_history = lineage.get(
        "measurement_history",
        [],
    )

    if not isinstance(
        previous_history,
        list,
    ):
        _fail(
            "previous_passport.lineage."
            "measurement_history must be an array"
        )

    history = [
        dict(
            item
        )
        for item in previous_history
        if isinstance(
            item,
            Mapping,
        )
    ]

    history.append(
        event
    )

    new_lineage: Dict[
        str,
        Any,
    ] = {
        "passport_id":
            str(
                uuid4()
            ),

        "revision":
            new_revision,

        "parent_passport_id":
            previous_id,

        "origin":
            "explicit_adaptive_micro_question_update",

        "measurement_history":
            history,

        "last_update_event":
            event,
    }

    return (
        adaptive_input,
        new_lineage,
    )
