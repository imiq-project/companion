"""Faithful, deterministic XAI for already-computed contextual deliberation.

This module only projects existing observed route context,
and HOTCO readout/process diagnostics into a minimized evidence document. It
never reruns HOTCO, normalizes context, or infers a user's real choice.
"""

from __future__ import annotations

from typing import Any, Mapping

from explanations.digital_companion_contract import evidence_version
from context.context_facts import build_context_facts
from model.xai_diagnostics import ambiguity_state
from explanations.companion_interpretation import interpretation_metrics, metric_changes, personal_deliberation_profile


XAI_SCHEMA_VERSION = "contextual-xai-v2"
XAI_SCOPE = "simulated_model_tendency"


class ContextualXAIError(ValueError):
    pass


def _delta(current: Mapping[str, Any], baseline: Mapping[str, Any]) -> dict[str, float]:
    keys = sorted(set(current) | set(baseline))
    return {
        key: float(current.get(key, 0.0) or 0.0) - float(baseline.get(key, 0.0) or 0.0)
        for key in keys
    }


def _structural(diag: Mapping[str, Any], mode: str | None) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    by_mode = diag.get("mixed_cognitive_support", {}).get("by_mode", {}) if isinstance(diag, Mapping) else {}
    selected = by_mode.get(mode, {}) if isinstance(by_mode, Mapping) else {}
    supporters = selected.get("top_supporters_terminal", []) if isinstance(selected, Mapping) else []
    inhibitors = selected.get("top_inhibitors_terminal", []) if isinstance(selected, Mapping) else []
    return list(supporters or []), list(inhibitors or [])


def _context_status(facts: list[dict[str, Any]]) -> str:
    """Report observation availability independently of model influence."""
    visible = [item for item in facts if item.get("displayable", True)]
    available = [item for item in visible if item.get("status") == "AVAILABLE"]
    if not available:
        return "UNAVAILABLE"
    return "FULL" if len(available) == len(visible) else "PARTIAL"


def _context_facts(route_context: Mapping[str, Any]) -> list[dict[str, Any]]:
    """Project only contract-safe observations for the optional companion.

    Source eligibility is owned by the upstream context contract; this
    presentation helper never normalizes data or changes eligibility.
    """
    supplied = route_context.get("context_facts")
    if isinstance(supplied, list) and all(isinstance(item, Mapping) for item in supplied):
        return [dict(item) for item in supplied]
    return build_context_facts(route_context)


def build_contextual_xai(deliberation: Mapping[str, Any], route_id: str, alternative_mode: str | None = None) -> dict[str, Any]:
    """Build explanation evidence from a serialized Phase 5 response."""
    if not isinstance(deliberation, Mapping):
        raise ContextualXAIError("deliberation must be a JSON object")
    baseline = deliberation.get("baseline")
    results = deliberation.get("candidate_results", ())
    candidate = next((item for item in results if isinstance(item, Mapping) and item.get("route_id") == route_id), None)
    if not isinstance(baseline, Mapping) or not isinstance(candidate, Mapping):
        raise ContextualXAIError("route_id does not identify a result in the deliberation")
    contextual = candidate.get("hotco", {})
    route_context = candidate.get("route_context", {})
    if not isinstance(contextual, Mapping) or not isinstance(route_context, Mapping):
        raise ContextualXAIError("deliberation result is missing contextual evidence")

    baseline_winner = str(baseline.get("winner", ""))
    contextual_winner = str(contextual.get("winner", ""))
    contextual_ambiguity = str(contextual.get("ambiguity_state") or ambiguity_state(contextual.get("process_diagnostics", {}).get("competition", {})))
    # Retired forcing fields in saved input are ignored. Observations never
    # become node contributions, including when reading historical documents.
    observation_facts = _context_facts(route_context)
    facts: list[dict[str, Any]] = []
    positive: list[dict[str, Any]] = []
    negative: list[dict[str, Any]] = []
    target_mode = alternative_mode or contextual_winner
    supporters, inhibitors = _structural(contextual.get("process_diagnostics", {}), target_mode)
    alternative = None
    if alternative_mode:
        alt = next((item for item in results if isinstance(item, Mapping) and item.get("hotco", {}).get("winner") == alternative_mode), None)
        if isinstance(alt, Mapping):
            alt_hotco = alt.get("hotco", {})
            alternative = {
                "mode": alternative_mode,
                "action_activation_difference": _delta(contextual.get("final_action_activations", {}), alt_hotco.get("final_action_activations", {})),
                "opposing_contextual_drivers": [item for item in negative if item.get("mode") == alternative_mode or item.get("target") == f"valence_{alternative_mode}"],
            }

    process = contextual.get("process_diagnostics", {})
    competition = process.get("competition", {}) if isinstance(process, Mapping) else {}
    baseline_nodes = baseline.get("node_activations", {})
    contextual_nodes = contextual.get("node_activations", {})
    need_changes = _delta(
        contextual_nodes.get("needs", {}).get("final", {}),
        baseline_nodes.get("needs", {}).get("final", {}),
    )
    valence_changes = _delta(
        contextual_nodes.get("valences", {}).get("final", {}),
        baseline_nodes.get("valences", {}).get("final", {}),
    )
    data_quality = {
        "context_status": _context_status(observation_facts),
        "observation_completeness": dict(route_context.get("contextual_data_completeness", {})),
        "missing_observations": [item.get("variable") for item in observation_facts
                                 if item.get("status") != "AVAILABLE"],
        "warnings": list(dict.fromkeys([*(route_context.get("source_metadata", {}).get("warnings", []) if isinstance(route_context.get("source_metadata", {}), Mapping) else []), *candidate.get("warnings", [])])),
        "spatial_matching": {
            "completeness": route_context.get("contextual_data_completeness", {}),
            "metadata": route_context.get("source_metadata", {}).get("spatial_matching") if isinstance(route_context.get("source_metadata", {}), Mapping) else None,
            "radius_meters": route_context.get("source_metadata", {}).get("search_radius_meters") if isinstance(route_context.get("source_metadata", {}), Mapping) else None,
            "coordinate_limitations": route_context.get("source_metadata", {}).get("leg_coordinate_limitation") if isinstance(route_context.get("source_metadata", {}), Mapping) else None,
            "geometry_provenance": route_context.get("source_metadata", {}).get("geometry_provenance") if isinstance(route_context.get("source_metadata", {}), Mapping) else None,
            "context_spatial_fidelity": route_context.get("source_metadata", {}).get("context_spatial_fidelity") if isinstance(route_context.get("source_metadata", {}), Mapping) else "UNKNOWN",
            "geometry_sample_count": route_context.get("source_metadata", {}).get("geometry_sample_count") if isinstance(route_context.get("source_metadata", {}), Mapping) else 0,
        },
    }
    summary: list[str] = []
    if contextual_ambiguity in {"NEAR_TIE", "UNRESOLVED"}:
        summary.append("The exploratory analysis shows similar support for the leading alternatives.")
    elif contextual_ambiguity == "UNSETTLED":
        summary.append("The terminal dynamics have not established a stable preference.")
    elif contextual_ambiguity == "SENSITIVE":
        summary.append("The leading option is sensitive to the checked local belief or integration variations.")
    elif baseline_winner != contextual_winner and baseline_winner and contextual_winner:
        summary.append(f"The simulated model tendency changed from {baseline_winner} to {contextual_winner} compared with the no-context simulation.")

    baseline_metrics = interpretation_metrics(baseline)
    contextual_metrics = interpretation_metrics(contextual)
    selected_route_modes = [
        segment.get("mode") for segment in route_context.get("segments", [])
        if isinstance(segment, Mapping) and segment.get("mode")
    ]
    companion_profile = personal_deliberation_profile(
        baseline_tendency=baseline_winner,
        contextual_tendency=contextual_winner,
        ambiguity_state=contextual_ambiguity,
        leader_changed=baseline_winner != contextual_winner,
        selected_route_modes=selected_route_modes,
        contextual_metrics=contextual_metrics,
        active_need_values=contextual_nodes.get("needs", {}).get("final", {}),
        supporting_constraints=supporters,
        opposing_constraints=inhibitors,
    )
    minimized_evidence = {
        "xai_schema_version": XAI_SCHEMA_VERSION,
        "route_id": route_id,
        "baseline_tendency": baseline_winner,
        "baseline_leaders": list(baseline.get("terminal_leaders", [baseline_winner] if baseline_winner else [])),
        "contextual_leaders": list(contextual.get("terminal_leaders", [contextual_winner] if contextual_winner else [])),
        "contextual_tendency": contextual_winner,
        "leader_changed": baseline_winner != contextual_winner,
        "ambiguity_state": contextual_ambiguity,
        "context_model": contextual.get("context_metadata", {}).get("context_model", "observations_only"),
        "context_model_status": contextual.get("context_metadata", {}).get("context_model_status", "NOT_APPLIED"),
        "deliberation_metrics": {
            "baseline": baseline_metrics,
            "contextual": contextual_metrics,
            "changes": metric_changes(baseline_metrics, contextual_metrics),
        },
        "personal_deliberation": companion_profile,
        "context_spatial_fidelity": data_quality["spatial_matching"]["context_spatial_fidelity"],
        "baseline_action_activations": dict(baseline.get("final_action_activations", {})),
        "contextual_action_activations": dict(contextual.get("final_action_activations", {})),
        "action_activation_change": _delta(contextual.get("final_action_activations", {}), baseline.get("final_action_activations", {})),
        "top_positive_context_drivers": positive[:5],
        "top_negative_context_drivers": negative[:5],
        "need_state": contextual_nodes.get("needs", {}),
        "baseline_need_state": baseline_nodes.get("needs", {}),
        "need_state_change": need_changes,
        "valence_state": contextual_nodes.get("valences", {}),
        "baseline_valence_state": baseline_nodes.get("valences", {}),
        "valence_state_change": valence_changes,
        "supporting_constraints": supporters,
        "opposing_constraints": inhibitors,
        "uncertainty": {"context_status": data_quality["context_status"], "minimum_top2_gap": competition.get("minimum_top2_gap"), "co_dominance_fraction": competition.get("co_dominance_fraction"), "winner_lead_fraction": competition.get("winner_lead_fraction")},
        "data_quality": data_quality,
        "warnings": data_quality["warnings"],
        "context_facts": observation_facts,
        "versions": {"model": deliberation.get("model_version"), "normalization": deliberation.get("normalization_version")},
    }
    evidence_hash = evidence_version(minimized_evidence)
    return {
        "schema_version": XAI_SCHEMA_VERSION,
        "scope": XAI_SCOPE,
        "route_id": route_id,
        "baseline_tendency": baseline_winner,
        "baseline_leaders": list(baseline.get("terminal_leaders", [baseline_winner] if baseline_winner else [])),
        "contextual_leaders": list(contextual.get("terminal_leaders", [contextual_winner] if contextual_winner else [])),
        "contextual_tendency": contextual_winner,
        "leader_changed": baseline_winner != contextual_winner,
        "ambiguity_state": contextual_ambiguity,
        "baseline_action_activations": dict(baseline.get("final_action_activations", {})),
        "contextual_action_activations": dict(contextual.get("final_action_activations", {})),
        "action_activation_change": _delta(contextual.get("final_action_activations", {}), baseline.get("final_action_activations", {})),
        "contextual_drivers": {"all": facts, "top_positive": positive[:5], "top_negative": negative[:5]},
        "need_drivers": [item for item in facts if item.get("target_family") == "need"],
        "valence_drivers": [item for item in facts if item.get("target_family") == "valence"],
        "need_state": contextual_nodes.get("needs", {}),
        "baseline_need_state": baseline_nodes.get("needs", {}),
        "need_state_change": need_changes,
        "valence_state": contextual_nodes.get("valences", {}),
        "baseline_valence_state": baseline_nodes.get("valences", {}),
        "valence_state_change": valence_changes,
        "supporting_constraints": supporters,
        "opposing_constraints": inhibitors,
        "trajectory": {"competition": competition, "process_diagnostics": process},
        "uncertainty": {
            "context_status": data_quality["context_status"],
            "minimum_top2_gap": competition.get("minimum_top2_gap", competition.get("final_margin")),
            "co_dominance_fraction": competition.get("co_dominance_fraction"),
            "winner_lead_fraction": competition.get("winner_lead_fraction"),
            "entropy": competition.get("terminal_entropy"),
            "warnings": data_quality["warnings"],
        },
        "data_quality": data_quality,
        "counterfactual_readiness": {"counterfactual_ready": True, "requires_new_hotco_simulation": True, "computed": False},
        "facts": facts,
        "summary": summary,
        "why_this_mode": {"mode": target_mode, "supporting_constraints": supporters, "contextual_drivers": positive, "opposing_constraints": inhibitors, "uncertainty": data_quality["warnings"]},
        "why_not_mode": alternative,
        "versions": {"xai": XAI_SCHEMA_VERSION, "model": deliberation.get("model_version"), "normalization": deliberation.get("normalization_version")},
        "warnings": data_quality["warnings"],
        "minimized_evidence": minimized_evidence,
        "evidence_version": evidence_hash,
    }
