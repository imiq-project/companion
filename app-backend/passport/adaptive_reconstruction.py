"""Validate provenance and reconstruct frozen declared Passport measurements; never infer a revision."""

from __future__ import annotations
from passport.need_allocation import validate_need_allocation

from datetime import datetime, timezone
import math
from typing import Any, Dict, Mapping, Tuple
from uuid import uuid4

from passport.adaptive_hotco_adapter import (
    ADAPTIVE_HOTCO_ADAPTER_VERSION,
    AdaptiveHOTCOInput,
)
from passport.adaptive_passport_v1 import (
    AdaptivePassportError,
    AdaptivePassportV1,
)
from model.availability_resolver import (
    resolve_user_declared_availability,
)
from model.input_mapping_v4_3 import tolerance_dimensions, MODES, NEEDS, ParticipantInput


ADAPTIVE_PROFILE_UPDATE_SCHEMA_VERSION = (
    "adaptive_profile_update_1.0"
)

_RUNTIME = AdaptivePassportV1()


class AdaptiveProfileUpdateError(
    ValueError
):
    """Raised when a longitudinal adaptive update is invalid."""


def _fail(
    message: str,
) -> None:
    raise AdaptiveProfileUpdateError(
        message
    )


def _mapping(
    value: Any,
    path: str,
) -> Mapping[str, Any]:

    if not isinstance(
        value,
        Mapping,
    ):
        _fail(
            f"{path} must be a JSON object"
        )

    return value


def _integer(
    value: Any,
    *,
    minimum: int,
    maximum: int,
    path: str,
) -> int:

    if isinstance(
        value,
        bool,
    ):
        _fail(
            f"{path} must be an integer from "
            f"{minimum} to {maximum}"
        )

    try:
        numeric = float(
            value
        )
    except (
        TypeError,
        ValueError,
    ) as exc:
        raise AdaptiveProfileUpdateError(
            f"{path} must be an integer from "
            f"{minimum} to {maximum}"
        ) from exc

    if (
        not math.isfinite(
            numeric
        )
        or not numeric.is_integer()
        or numeric < minimum
        or numeric > maximum
    ):
        _fail(
            f"{path} must be an integer from "
            f"{minimum} to {maximum}"
        )

    return int(
        numeric
    )


def _unwrap_passport(
    value: Any,
) -> Mapping[str, Any]:

    root = _mapping(
        value,
        "previous_passport",
    )

    if (
        "cognitive_passport"
        in root
    ):
        return _mapping(
            root.get(
                "cognitive_passport"
            ),
            (
                "previous_passport."
                "cognitive_passport"
            ),
        )

    return root


def _same_target(
    submitted: Mapping[str, Any],
    expected: Mapping[str, Any],
) -> bool:

    kind = str(
        expected.get(
            "kind",
            "",
        )
    )

    if (
        submitted.get(
            "kind"
        )
        != kind
    ):
        return False

    if kind == "need":
        return (
            submitted.get(
                "need"
            )
            == expected.get(
                "need"
            )
        )

    if kind == "valence":
        return (
            submitted.get(
                "mode"
            )
            == expected.get(
                "mode"
            )
        )

    if kind == "belief":
        return (
            submitted.get(
                "mode"
            )
            == expected.get(
                "mode"
            )
            and submitted.get(
                "need"
            )
            == expected.get(
                "need"
            )
        )

    return False


def _active_candidate(
    passport: Mapping[str, Any],
) -> Mapping[str, Any]:

    adaptive = _mapping(
        passport.get(
            "adaptive_questioning"
        ),
        (
            "previous_passport."
            "adaptive_questioning"
        ),
    )

    if (
        adaptive.get(
            "question_needed"
        )
        is not True
    ):
        _fail(
            "previous_passport has no active "
            "adaptive micro-question"
        )

    candidate = _mapping(
        adaptive.get(
            "candidate"
        ),
        (
            "previous_passport."
            "adaptive_questioning."
            "candidate"
        ),
    )

    if (
        candidate.get(
            "scope"
        )
        != "profile"
    ):
        _fail(
            "only profile-scope adaptive "
            "questions may update the Passport"
        )

    return candidate


def _allocation_from_passport(passport):
    observed = passport.get("observed_measurements", {})
    if "needs_allocation" not in observed:
        return None
    if "needs_raw_1_to_7" in observed:
        _fail("Ambiguous observed needs: allocation and legacy ratings")
    try:
        allocation, _ = validate_need_allocation(observed["needs_allocation"])
    except ValueError as exc:
        raise AdaptiveProfileUpdateError(str(exc)) from exc
    return allocation


def _normalized_need_measurements(passport, raw_needs):
    if "goal_priorities" in passport:
        from passport.priority_rules import normalized_from_passport
        return normalized_from_passport(passport)
    if "focal_goals" in passport:
        from passport.focal_passport import reference_needs
        return reference_needs(passport)
    allocation = _allocation_from_passport(passport)
    if allocation is not None:
        return {need: raw_needs[need] / 10.0 for need in NEEDS}
    return {need: (raw_needs[need] - 1.0) / 6.0 for need in NEEDS}


def _raw_measurements(
    passport: Mapping[str, Any],
) -> tuple[
    Dict[str, int],
    Dict[str, int],
    Dict[str, int],
]:

    observed = _mapping(
        passport.get(
            "observed_measurements"
        ),
        (
            "previous_passport."
            "observed_measurements"
        ),
    )

    allocation = _allocation_from_passport(passport)
    raw_needs_source = _mapping(
        allocation["points"] if allocation is not None else observed.get("needs_raw_1_to_7"),
        (
            "previous_passport."
            "observed_measurements."
            "needs_raw_1_to_7"
        ),
    )

    raw_valences_source = _mapping(
        observed.get(
            "valences_raw_minus3_to_3"
        ),
        (
            "previous_passport."
            "observed_measurements."
            "valences_raw_minus3_to_3"
        ),
    )

    raw_beliefs_source = _mapping(
        observed.get(
            "beliefs_raw_1_to_7"
        ),
        (
            "previous_passport."
            "observed_measurements."
            "beliefs_raw_1_to_7"
        ),
    )

    raw_needs: Dict[
        str,
        int,
    ] = {}

    for need in NEEDS:

        if need not in raw_needs_source:
            _fail(
                "previous Passport is missing "
                f"observed need {need}"
            )

        raw_needs[
            need
        ] = _integer(
            raw_needs_source[
                need
            ],
            minimum=0 if allocation is not None else 1,
            maximum=10 if allocation is not None else 7,
            path=(
                "previous_passport."
                "observed_measurements."
                "needs_raw_1_to_7."
                + need
            ),
        )

    raw_valences: Dict[
        str,
        int,
    ] = {}

    for mode in MODES:

        if mode not in raw_valences_source:
            _fail(
                "previous Passport is missing "
                f"observed valence {mode}"
            )

        raw_valences[
            mode
        ] = _integer(
            raw_valences_source[
                mode
            ],
            minimum=-3,
            maximum=3,
            path=(
                "previous_passport."
                "observed_measurements."
                "valences_raw_minus3_to_3."
                + mode
            ),
        )

    raw_beliefs: Dict[
        str,
        int,
    ] = {}

    for cell_id, value in (
        raw_beliefs_source.items()
    ):

        cell_id = str(
            cell_id
        )

        if cell_id not in (
            _RUNTIME.all_cells
        ):
            _fail(
                "previous Passport contains "
                "unknown observed belief cell "
                + cell_id
            )

        raw_beliefs[
            cell_id
        ] = _integer(
            value,
            minimum=1,
            maximum=7,
            path=(
                "previous_passport."
                "observed_measurements."
                "beliefs_raw_1_to_7."
                + cell_id
            ),
        )

    if len(
        raw_beliefs
    ) < 20:
        _fail(
            "Adaptive Passport must retain at least "
            "the twenty initial observed beliefs"
        )

    return (
        raw_needs,
        raw_valences,
        raw_beliefs,
    )


def _availability_from_passport(
    passport: Mapping[str, Any],
):
    profile = _mapping(
        passport.get(
            "profile"
        ),
        "previous_passport.profile",
    )

    availability_source = _mapping(
        profile.get(
            "availability"
        ),
        (
            "previous_passport."
            "profile.availability"
        ),
    )

    raw: Dict[
        str,
        bool,
    ] = {}

    for mode in MODES:

        value = availability_source.get(
            mode
        )

        if not isinstance(
            value,
            bool,
        ):
            _fail(
                "previous_passport.profile."
                f"availability.{mode} "
                "must be boolean"
            )

        raw[
            mode
        ] = value

    try:
        return (
            resolve_user_declared_availability(
                raw,
                MODES,
            )
        )

    except ValueError as exc:
        raise AdaptiveProfileUpdateError(
            str(exc)
        ) from exc


def _tolerances_from_passport(
    passport: Mapping[str, Any],
) -> Dict[
    str,
    Any,
]:

    profile = _mapping(
        passport.get(
            "profile"
        ),
        "previous_passport.profile",
    )

    source = profile.get(
        "environmental_tolerances"
    )

    if source is None:
        return {
            name: None
            for name
            in tolerance_dimensions()
        }

    source = _mapping(
        source,
        (
            "previous_passport.profile."
            "environmental_tolerances"
        ),
    )

    result: Dict[
        str,
        Any,
    ] = {}

    for name in tolerance_dimensions(source):

        value = source.get(
            name
        )

        if value is None:
            result[
                name
            ] = None
            continue

        if isinstance(
            value,
            bool,
        ):
            _fail(
                "environmental tolerance "
                f"{name} must be numeric or null"
            )

        try:
            numeric = float(
                value
            )
        except (
            TypeError,
            ValueError,
        ) as exc:
            raise AdaptiveProfileUpdateError(
                "environmental tolerance "
                f"{name} must be numeric or null"
            ) from exc

        if (
            not math.isfinite(
                numeric
            )
            or numeric < 0.0
            or numeric > 1.0
        ):
            _fail(
                "environmental tolerance "
                f"{name} must lie within [0,1]"
            )

        result[
            name
        ] = numeric

    return result



def reconstruct_adaptive_participant(
    passport_value: Any,
) -> ParticipantInput:
    """Reconstruct canonical HOTCO input from an Adaptive Cognitive Passport.

    Only genuinely observed raw measurements are treated as raw questionnaire
    responses. The dense belief matrix is regenerated with the frozen Adaptive
    Cognitive Passport estimator, so estimated beliefs are never inverted into
    fabricated 1..7 responses.
    """

    previous = _unwrap_passport(
        passport_value
    )

    adaptive_extension = _mapping(
        previous.get(
            "adaptive_passport"
        ),
        "cognitive_passport.adaptive_passport",
    )

    if (
        adaptive_extension.get(
            "artifact_sha256"
        )
        != _RUNTIME.artifact_sha256
    ):
        _fail(
            "Adaptive Passport uses a different "
            "estimator artifact"
        )

    if (
        adaptive_extension.get(
            "model_version"
        )
        != "five-goals-explicit-v1"
    ):
        _fail(
            "Adaptive Passport model_version is not supported"
        )

    (
        raw_needs,
        raw_valences,
        raw_beliefs,
    ) = _raw_measurements(
        previous
    )

    required_initial_cells = {
        question["cell_id"]
        for question in _RUNTIME.select_questions()
    }

    if not required_initial_cells.issubset(
        raw_beliefs
    ):
        _fail(
            "Adaptive Passport no longer contains "
            "all twenty initial declared measurements"
        )

    provenance = _mapping(
        previous.get(
            "input_provenance"
        ),
        "cognitive_passport.input_provenance",
    )

    observed_counts = _mapping(
        provenance.get(
            "observed_counts"
        ),
        (
            "cognitive_passport.input_provenance."
            "observed_counts"
        ),
    )

    estimated_counts = _mapping(
        provenance.get(
            "estimated_counts"
        ),
        (
            "cognitive_passport.input_provenance."
            "estimated_counts"
        ),
    )

    observed_belief_count = _integer(
        observed_counts.get(
            "beliefs"
        ),
        minimum=20,
        maximum=20,
        path=(
            "cognitive_passport.input_provenance."
            "observed_counts.beliefs"
        ),
    )

    estimated_belief_count = _integer(
        estimated_counts.get(
            "beliefs"
        ),
        minimum=0,
        maximum=0,
        path=(
            "cognitive_passport.input_provenance."
            "estimated_counts.beliefs"
        ),
    )

    if (
        observed_belief_count
        != len(raw_beliefs)
    ):
        _fail(
            "Adaptive Passport observed belief count "
            "does not match its raw measurements"
        )

    if (
        observed_belief_count
        + estimated_belief_count
        != 20
    ):
        _fail(
            "Adaptive Passport belief provenance "
            "must account for all 20 cells"
        )

    normalized_needs = _normalized_need_measurements(previous, raw_needs)
    from passport.measurement_basis import estimation_basis
    frozen_inputs = estimation_basis(previous) if "belief_estimation_inputs" in previous else None

    normalized_valences = {
        mode:
            raw_valences[mode]
            / 3.0
        for mode in MODES
    }

    try:
        personalized = _RUNTIME.personalize(
            needs=
                frozen_inputs["needs"] if frozen_inputs is not None else normalized_needs,

            valences=
                frozen_inputs["valences"] if frozen_inputs is not None else normalized_valences,

            observed_answers=
                raw_beliefs,

            answers_are_raw_1_to_7=
                True,

            # Initial passports contain four observations;
            # longitudinal revisions may contain more.
            require_complete_k=
                False,
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
        mode, need = cell_id.split(
            "__",
            1,
        )

        sparse_raw_beliefs[
            mode
        ][
            need
        ] = int(
            raw_value
        )

    agent_id = str(
        previous.get(
            "agent_id",
            "",
        )
    ).strip()

    if not agent_id:
        _fail(
            "Adaptive Passport agent_id is required"
        )

    return ParticipantInput(
        needs_allocation=_allocation_from_passport(previous),
        agent_id=
            agent_id,

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

