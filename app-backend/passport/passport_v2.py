"""Cognitive Passport v2 serialization and HOTCO-CT process diagnostics."""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any, Dict, Iterable, Optional

import numpy as np
from model.action_readout import signed_action_readout

from model.compat import (
    HOTCOCTv43,
    MODEL_NAME,
    MODEL_VERSION,
    SimulationResult,
)
from model.input_mapping_v4_3 import tolerance_dimensions, ENVIRONMENTAL_TOLERANCE_SCHEMA_VERSION, INPUT_SCHEMA_VERSION, MODES, NEEDS, ParticipantInput


PASSPORT_SCHEMA_VERSION = "2.0"


def _number(value: float | np.number, digits: int = 10) -> float:
    return round(float(value), digits)


def _optional_number(value: Optional[float], digits: int = 10) -> Optional[float]:
    return None if value is None else round(float(value), digits)


def _mode_map(values: Iterable[float | np.number]) -> Dict[str, float]:
    return {mode: _number(value) for mode, value in zip(MODES, values)}


def _need_map(values: Iterable[float | np.number]) -> Dict[str, float]:
    return {need: _number(value) for need, value in zip(NEEDS, values)}


def _terminal_margin(
    terminal_actions: np.ndarray, availability: np.ndarray
) -> tuple[float, float, int]:
    active = terminal_actions[availability == 1]
    if active.size == 1:
        return 1.0, 0.0, 1
    sorted_values = np.sort(active)[::-1]
    margin = float(sorted_values[0] - sorted_values[1])
    return margin, 1.0 - margin / 2.0, int(active.size)


def _settling_diagnostics(trajectory, engine, numerical=None):
    # Stationarity alone is separate from independently verified stability.
    audit = numerical or {}
    return {
        "achieved": audit.get("verified_stable_equilibrium", False),
        "stationary": audit.get("integrated_residual", float("inf")) <= engine.parameters.residual_tolerance*(1+1e-8),
        "time_model_units": audit.get("actual_horizon") if audit.get("verified_stable_equilibrium") else None,
        "time_semantics": "verified integrated endpoint horizon, not first settling time",
        "action_rate_threshold": engine.parameters.residual_tolerance,
        "full_state_rate_threshold": engine.parameters.residual_tolerance,
        "terminal_full_state_change_rate": audit.get("integrated_residual"),
        "interpretation": "numerical equilibrium verification; not human reaction time",
    }


def _winner_switches(
    trajectory: np.ndarray,
    availability: np.ndarray,
    engine: HOTCOCTv43,
) -> int:
    action_slice = slice(engine.n_needs, engine.n_needs + engine.n_modes)
    actions = trajectory[:, action_slice]
    active_indices = np.where(availability == 1)[0]
    last_identifiable: Optional[int] = None
    switches = 0
    for row in actions:
        active = row[active_indices]
        if active.size > 1:
            order = np.argsort(active)[::-1]
            margin = float(active[order[0]] - active[order[1]])
            if margin <= engine.parameters.practical_margin_threshold:
                continue
            winner = int(active_indices[order[0]])
        else:
            winner = int(active_indices[0])
        if last_identifiable is not None and winner != last_identifiable:
            switches += 1
        last_identifiable = winner
    return switches


def _input_organization(
    participant: ParticipantInput,
    engine: HOTCOCTv43,
) -> Dict[str, Any]:
    needs = np.asarray(participant.needs, dtype=np.float64)
    beliefs = np.asarray(participant.beliefs, dtype=np.float64)
    valences = np.asarray(participant.valences, dtype=np.float64)
    availability = np.asarray(participant.availability, dtype=np.float64)
    p = engine.parameters

    cognitive = (
        p.cognitive_gain
        * p.need_to_action_gain
        * (beliefs * needs[np.newaxis, :]).sum(axis=1)
    )
    affective = p.valence_to_action_gain * valences
    net = cognitive + affective
    active = availability == 1

    c_centered = cognitive[active] - cognitive[active].mean()
    g_centered = affective[active] - affective[active].mean()
    denominator = np.linalg.norm(c_centered) * np.linalg.norm(g_centered)
    if denominator > 0.0:
        alignment: Optional[float] = float(
            np.dot(c_centered, g_centered) / denominator
        )
        incongruence: Optional[float] = (1.0 - alignment) / 2.0
    else:
        alignment = None
        incongruence = None

    cancellation_denominator = np.sum(
        np.abs(cognitive[active]) + np.abs(affective[active])
    )
    cancellation = (
        1.0 - float(np.sum(np.abs(net[active])) / cancellation_denominator)
        if cancellation_denominator > 0.0
        else None
    )

    contributions = beliefs * needs[np.newaxis, :]
    positive = np.maximum(contributions, 0.0).sum(axis=1)
    negative = np.maximum(-contributions, 0.0).sum(axis=1)
    mixed_denominator = np.sum(positive[active] + negative[active])
    mixed_sign = (
        float(np.sum(2.0 * np.minimum(positive[active], negative[active])) / mixed_denominator)
        if mixed_denominator > 0.0
        else None
    )

    return {
        "cognitive_support": _mode_map(cognitive),
        "affective_support": _mode_map(affective),
        "net_support": _mode_map(net),
        "cognitive_affective_alignment": _optional_number(alignment),
        "cognitive_affective_incongruence": _optional_number(incongruence),
        "support_cancellation": _optional_number(cancellation),
        "mixed_sign_cognitive_support": _optional_number(mixed_sign),
        "undefined_metrics_are_null": True,
    }


def _tension_for(values: np.ndarray) -> Dict[str, Optional[float]]:
    positive = float(np.maximum(values, 0.0).sum())
    negative = float(np.maximum(-values, 0.0).sum())
    total = positive + negative
    if total == 0.0:
        tension = None
        congruence = None
    else:
        tension = negative / total
        congruence = 1.0 - 2.0 * tension
    return {
        "positive_mass": _number(positive),
        "negative_mass": _number(negative),
        "tension": _optional_number(tension),
        "congruence": _optional_number(congruence),
    }


def _active_tension(
    terminal: np.ndarray,
    participant: ParticipantInput,
    engine: HOTCOCTv43,
) -> Dict[str, Any]:
    n = terminal[: engine.n_needs]
    a = terminal[engine.n_needs : engine.n_needs + engine.n_modes]
    v = terminal[engine.n_needs + engine.n_modes :]
    r = np.asarray(participant.beliefs, dtype=np.float64)
    q = np.asarray(participant.availability, dtype=np.float64)
    p = engine.parameters

    need_action = (
        p.cognitive_gain * r * a[:, np.newaxis] * n[np.newaxis, :]
    ).reshape(-1)
    action_valence = p.valence_to_action_gain * a * v
    lateral = np.asarray(
        [
            engine.build_topology(r[None])[0, engine.n_needs+first, engine.n_needs+second] * a[first] * a[second]
            for first in range(engine.n_modes)
            for second in range(first + 1, engine.n_modes)
        ],
        dtype=np.float64,
    )
    all_values = np.concatenate((need_action, action_valence, lateral))
    return {
        "total": _tension_for(all_values),
        "by_family": {
            "need_action": _tension_for(need_action),
            "action_valence": _tension_for(action_valence),
            "modal_compatibility": _tension_for(lateral),
        },
        "interpretation": "state-weighted signed-constraint diagnostic; not an energy or experiential scale",
    }


def build_cognitive_passport(
    participant: ParticipantInput,
    result: SimulationResult,
    engine: HOTCOCTv43,
) -> Dict[str, Any]:
    trajectory = result.trajectory[0]
    terminal = trajectory[-1]
    availability = result.availability[0]
    action_slice = slice(engine.n_needs, engine.n_needs + engine.n_modes)
    terminal_actions = terminal[action_slice]
    action_readout = signed_action_readout(terminal_actions, availability)
    margin, ambiguity, active_count = _terminal_margin(terminal_actions, availability)
    settling = _settling_diagnostics(trajectory, engine, result.numerical[0])
    winner_switches = _winner_switches(trajectory, availability, engine)
    tail_steps = min(len(trajectory)-1, max(1, int(round(engine.parameters.settling_dwell / engine.parameters.dt))))
    tail_drift = float(np.max(np.abs(trajectory[-1] - trajectory[-1 - tail_steps])))

    beliefs_profile = {
        mode: {
            need: _number(participant.beliefs[mode_index][need_index])
            for need_index, need in enumerate(NEEDS)
        }
        for mode_index, mode in enumerate(MODES)
    }
    profile: Dict[str, Any] = {
        "needs": _need_map(participant.needs),
        "beliefs": beliefs_profile,
        "valences": _mode_map(participant.valences),
        "availability": {
            mode: bool(participant.raw_availability[mode]) for mode in MODES
        },
        "environmental_tolerances": {
            name: participant.environmental_tolerances.get(name)
            for name in tolerance_dimensions(participant.environmental_tolerances)
        },
        "environmental_tolerance_metadata": {
            "schema_version": "environmental-tolerances-v2" if "heat" in participant.environmental_tolerances or "cold" in participant.environmental_tolerances else ENVIRONMENTAL_TOLERANCE_SCHEMA_VERSION,
            "value_range": [0.0, 1.0],
            "zero_semantics": "maximum_susceptibility",
            "one_semantics": "maximum_tolerance",
            "missing_semantics": "unknown",
            "complete": all(
                participant.environmental_tolerances.get(name) is not None
                for name in tolerance_dimensions(participant.environmental_tolerances)
            ),
            "missing_dimensions": [
                name
                for name in tolerance_dimensions(participant.environmental_tolerances)
                if participant.environmental_tolerances.get(name) is None
            ],
            "source": "explicit_participant_or_questionnaire_input",
            "transformation": "none",
        },
    }

    input_organization = _input_organization(participant, engine)
    active_tension = _active_tension(terminal, participant, engine)
    terminal_actions_map = _mode_map(terminal_actions)

    passport: Dict[str, Any] = {
        "schema_version": PASSPORT_SCHEMA_VERSION,
        "version": MODEL_VERSION,
        "model": MODEL_NAME,
        "agent_id": participant.agent_id,
        "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "input_provenance": {
            "source_schema": INPUT_SCHEMA_VERSION,
            "policy": "current_user_responses_only",
            "imputation_used": False,
            "population_or_synthetic_values_used": False,
            "missing_required_values": 0,
            "observed_counts": {
                "needs": len(NEEDS),
                "beliefs": len(NEEDS) * len(MODES),
                "valences": len(MODES),
                "availability": len(MODES),
                "environmental_tolerances": sum(
                    participant.environmental_tolerances.get(name) is not None
                    for name in tolerance_dimensions(participant.environmental_tolerances)
                ),
            },
            "availability_resolution": participant.availability_provenance,
            "transformations": {
                "needs": "(raw_rating - 1) / 6",
                "beliefs": "(raw_rating - 4) / 3",
                "valences": "raw_rating / 3",
                "availability": "AvailabilityResolver(user_declared_boolean) -> {0,1}",
                "environmental_tolerances": "none; persisted unchanged",
            },
        },
        "topology": {
            "n_needs": engine.n_needs,
            "n_modes": engine.n_modes,
            "n_nodes": engine.n_nodes,
            "need_names": list(NEEDS),
            "mode_names": list(MODES),
            "belief_edges_observed": len(NEEDS) * len(MODES),
            "belief_edges_imputed": 0,
            "cognitive_scaling": "belief_strength * R (no need-count division)",
            "state_bounds": [-1.0, 1.0],
            "availability_policy": "readout_only_full_four_mode_field",
            "modal_compatibility": engine.build_topology(np.asarray([participant.beliefs]))[0, engine.n_needs:engine.n_needs+engine.n_modes, engine.n_needs:engine.n_needs+engine.n_modes].tolist(),
            "symmetric_reference_topology": True,
        },
        "profile": profile,
        "deliberation": {
            **action_readout,
            "evaluation_status": "ALL_NEGATIVE" if np.max(terminal_actions[availability == 1]) < -1e-6 else "NO_POSITIVE_SUPPORT" if np.max(terminal_actions[availability == 1]) <= 1e-6 else "POSITIVE_SUPPORT",
            "state_interpretation": "signed model evaluation; not observed preference or behavior",
            "terminal_action_activations": terminal_actions_map,
            "terminal_margin": _number(margin),
            "practically_differentiated": bool(
                active_count == 1
                or margin > engine.parameters.practical_margin_threshold
            ),
            "settling_achieved": settling["achieved"],
            "settling_time_model_units": settling["time_model_units"],
            "convergence_achieved": settling["achieved"],
        },
        "process_diagnostics": {
            "input_organization": input_organization,
            "active_constraint_tension_terminal": active_tension,
            "action_process": {
                "terminal_margin": _number(margin),
                "terminal_ambiguity": _number(ambiguity),
                "winner_switch_count": winner_switches,
                "tail_drift_last_model_unit": _number(tail_drift),
                "active_alternatives": active_count,
            },
            "settling": settling,
            "numerical": {
                "solver": "DOP853",
                "output_sampling_interval": engine.parameters.dt,
                "audit": result.numerical[0],
                "state_clipping_applied": False,
                "dt": engine.parameters.dt,
                "horizon": engine.parameters.horizon,
                "tau": engine.parameters.tau,
                "terminal_state_always_read_at_horizon": True,
                "actual_horizon": float(result.times[-1]),
                "root_replaces_integrated_endpoint": False,
                "projection_corrections": result.projection_corrections,
            },
        },
        "routing_parameters": {
            "need_weights": _need_map(participant.needs),
            "availability": {
                mode: bool(participant.raw_availability[mode]) for mode in MODES
            },
            "availability_policy": participant.availability_provenance["policy"],
            "interpretation": "declared inputs; routing utility is independent of signed HOTCO evaluations",
        },
    }
    if participant.ranking is not None:
        passport["top_needs_ranking"] = list(participant.ranking)

    return {"cognitive_passport": passport}
