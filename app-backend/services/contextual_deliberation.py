"""Phase 5 orchestration for route-conditioned HOTCO deliberation.

Candidate order is preserved. This service composes the existing provider,
normalizer and HOTCO simulator. Context is archived as observations and
does not add external forces to the dynamics.
"""

from __future__ import annotations

from dataclasses import dataclass, field, asdict, replace
from routing.journey_availability import apply_journey_availability
from typing import Any, Mapping

from model.compat import HOTCOCTv43, MODEL_VERSION, SimulationResult
from model.input_mapping_v4_3 import MODES, NEEDS, ParticipantInput, parse_participant_input
from context.orion_context import (
    OrionConfigurationError,
    OrionContextProvider,
    OrionHttpError,
    OrionNetworkError,
    OrionProviderError,
    OrionTimeoutError,
)
from passport.passport_v2 import build_cognitive_passport as build_base_passport
from passport.profile_reconstruction import _reconstruct_questionnaire
from passport.adaptive_reconstruction import (
    AdaptiveProfileUpdateError,
    reconstruct_adaptive_participant,
)
from context.route_context import CandidateRouteInput, ContextObservation, RouteContext
from context.route_context_adapter import NORMALIZATION_VERSION, RouteContextAdapter
from context.weather_provider import WeatherProvider
from model.xai_diagnostics import ambiguity_state, build_xai_diagnostics
from model.recommendation_robustness import assess_robustness
from copy import deepcopy
from infrastructure.service_runtime import phase, timed
import hashlib
import json


REQUEST_SCHEMA_VERSION = "contextual-deliberation-request-v1"
RESPONSE_SCHEMA_VERSION = "contextual-deliberation-response-v1"


class ContextualDeliberationError(ValueError):
    pass


class ContextualProviderConfigurationError(ContextualDeliberationError):
    pass


@dataclass(frozen=True)
class ContextQueryConfiguration:
    query_orion: bool = False
    entity_families: tuple[str, ...] = ("weather", "traffic", "parking", "air_quality", "ev_charging")
    radius_meters: float = 500.0
    weather_radius_meters: float = 5000.0
    limit: int = 100

    def __post_init__(self) -> None:
        if self.radius_meters <= 0 or self.weather_radius_meters <= 0 or self.limit <= 0:
            raise ContextualDeliberationError("context radii and limit must be positive")

    @classmethod
    def from_dict(cls, data: Mapping[str, Any] | None) -> "ContextQueryConfiguration":
        data = data or {}
        return cls(
            bool(data.get("query_orion", False)),
            tuple(data.get("entity_families", cls.entity_families)),
            float(data.get("radius_meters", 500.0)),
            float(data.get("weather_radius_meters", 5000.0)),
            int(data.get("limit", 100)),
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "query_orion": self.query_orion,
            "entity_families": list(self.entity_families),
            "radius_meters": self.radius_meters,
            "weather_radius_meters": self.weather_radius_meters,
            "limit": self.limit,
            "spatial_matching": "segment_midpoint_near_radius",
        }


@dataclass(frozen=True)
class ContextualDeliberationRequest:
    search_id: str
    timestamp: str
    participant: ParticipantInput
    candidate_routes: tuple[CandidateRouteInput, ...]
    observations: Mapping[str, Any] = field(default_factory=dict)
    query_configuration: ContextQueryConfiguration = ContextQueryConfiguration()
    warnings: tuple[str, ...] = ()
    record_trajectories: bool = False
    context_policy: str = "observations_only"
    goal_priorities: Mapping[str, Any] | None = None

    @classmethod
    def from_dict(cls, data: Mapping[str, Any]) -> "ContextualDeliberationRequest":
        if data.get("required_readout_version", "signed-actions-v1") != "signed-actions-v1":
            raise ContextualDeliberationError("required action readout is unavailable on this backend")
        if data.get("required_model_version", MODEL_VERSION) != MODEL_VERSION:
            raise ContextualDeliberationError("required model version is not available on this backend")
        if data.get("schema_version", REQUEST_SCHEMA_VERSION) != REQUEST_SCHEMA_VERSION:
            raise ContextualDeliberationError("unsupported contextual deliberation request schema")
        if any(key in data for key in ("api_key", "orion_api_key", "COMPANION_ORION_API_KEY")):
            raise ContextualDeliberationError("Orion credentials must be configured in the server environment")
        search_id = str(data.get("search_id", "")).strip()
        if not search_id:
            raise ContextualDeliberationError("search_id is required")
        timestamp = str(data.get("timestamp", "")).strip()
        if not timestamp:
            raise ContextualDeliberationError("timestamp is required")
        candidates = tuple(CandidateRouteInput.from_dict(item) for item in data.get("candidate_routes", ()))
        if not candidates:
            raise ContextualDeliberationError("candidate_routes must contain at least one route")
        passport_value = data.get("cognitive_passport")
        root = passport_value.get("cognitive_passport", passport_value) if isinstance(passport_value, Mapping) else passport_value
        if isinstance(root, Mapping) and "focal_goals" in root:
            raise ContextualDeliberationError("Retired goal selection: migrate to goal personalization before searching")
        if data.get("participant") is not None and isinstance(root, Mapping) and "goal_priorities" in root:
            raise ContextualDeliberationError("Use the prepared Passport inputs")
        if data.get("participant") is not None:
            participant = parse_participant_input(data["participant"])
        elif passport_value is not None:
            root = (
                passport_value.get(
                    "cognitive_passport",
                    passport_value,
                )
                if isinstance(
                    passport_value,
                    Mapping,
                )
                else passport_value
            )

            if (
                isinstance(
                    root,
                    Mapping,
                )
                and "adaptive_passport"
                in root
            ):
                if not isinstance(
                    root.get(
                        "adaptive_passport"
                    ),
                    Mapping,
                ):
                    raise ContextualDeliberationError(
                        "adaptive_passport must be a JSON object"
                    )

                try:
                    participant = (
                        reconstruct_adaptive_participant(
                            root
                        )
                    )
                except AdaptiveProfileUpdateError as exc:
                    raise ContextualDeliberationError(
                        "invalid Adaptive Cognitive Passport: "
                        + str(exc)
                    ) from exc

            else:
                participant = parse_participant_input(
                    _reconstruct_questionnaire(
                        root
                    )
                )
        else:
            raise ContextualDeliberationError("participant or cognitive_passport is required")

        if "journey_availability" in data:
            try:
                participant = apply_journey_availability(participant, data["journey_availability"])
            except ValueError as exc:
                raise ContextualDeliberationError(str(exc)) from exc

        policy = data.get("context_policy", "observations_only")
        if policy != "observations_only":
            raise ContextualDeliberationError("unsupported context_policy")
        priority_reference = deepcopy(root.get("goal_priorities")) if isinstance(root, Mapping) else None
        return cls(
            search_id,
            timestamp,
            participant,
            candidates,
            data.get("contextual_observations", {}),
            ContextQueryConfiguration.from_dict(data.get("contextual_query")),
            (),
            data.get("record_trajectories") is True,
            policy,
            priority_reference,
        )


@dataclass(frozen=True)
class HOTCODeliberationSummary:
    final_action_activations: Mapping[str, float]
    winner: str | None
    context_metadata: Mapping[str, Any]
    process_diagnostics: Mapping[str, Any] = field(default_factory=dict)
    node_activations: Mapping[str, Any] = field(default_factory=dict)
    ambiguity_state: str = "UNKNOWN"
    simulation_evidence: Mapping[str, Any] = field(default_factory=dict)
    terminal_leaders: tuple[str, ...] = ()
    readout_version: str = "signed-actions-v1"

    def to_dict(self) -> dict[str, Any]:
        return {"final_action_activations": dict(self.final_action_activations), "terminal_leaders": list(self.terminal_leaders), "readout_version": self.readout_version, "winner": self.winner, "context_metadata": dict(self.context_metadata), "process_diagnostics": dict(self.process_diagnostics), "node_activations": dict(self.node_activations), "ambiguity_state": self.ambiguity_state, "simulation_evidence": dict(self.simulation_evidence)}


@dataclass(frozen=True)
class RouteDeliberationResult:
    route_id: str
    route_context: RouteContext
    hotco: HOTCODeliberationSummary
    difference_from_baseline: Mapping[str, Mapping[str, float]]
    warnings: tuple[str, ...]
    source_status: Mapping[str, str]
    belief_update: Mapping[str, Any] | None = None

    def to_dict(self) -> dict[str, Any]:
        result = {"route_id": self.route_id, "route_context": self.route_context.to_dict(), "hotco": self.hotco.to_dict(), "difference_from_baseline": {key: dict(value) for key, value in self.difference_from_baseline.items()}, "warnings": list(self.warnings), "source_status": dict(self.source_status)}
        if self.belief_update is not None:result['belief_update'] = deepcopy(self.belief_update)
        return result


@dataclass(frozen=True)
class ContextualDeliberationResponse:
    search_id: str
    timestamp: str
    baseline: HOTCODeliberationSummary
    candidate_results: tuple[RouteDeliberationResult, ...]
    model_version: str
    normalization_version: str
    warnings: tuple[str, ...]
    goal_priorities: Mapping[str, Any] | None = None

    def to_dict(self) -> dict[str, Any]:
        output = {"schema_version": RESPONSE_SCHEMA_VERSION, "search_id": self.search_id, "timestamp": self.timestamp, "baseline": self.baseline.to_dict(), "candidate_results": [item.to_dict() for item in self.candidate_results], "model_version": self.model_version, "normalization_version": self.normalization_version, "warnings": list(self.warnings)}
        if self.goal_priorities is not None:
            output["goal_priorities"] = deepcopy(self.goal_priorities)
            output["baseline_role"] = "effective_weekly_and_journey_reference"
        from hashlib import sha256
        import json
        archive = {}
        trajectory_ids = {}
        for summary in [output["baseline"], *(item["hotco"] for item in output["candidate_results"])]:
            original_evidence = summary["simulation_evidence"]
            states = original_evidence.get("states", [])
            summary["simulation_evidence"] = deepcopy({key: value for key, value in original_evidence.items() if key != "states"})
            evidence = summary["simulation_evidence"]
            if states:
                identity = trajectory_ids.get(id(states))
                if identity is None:
                    identity = "trajectory-"+sha256(json.dumps({"model_version": evidence["model_version"], "parameters": evidence["parameters"], "time_grid": evidence.get("time_grid"), "states": states}, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
                    trajectory_ids[id(states)] = identity
                    if identity not in archive:
                        archive[identity] = deepcopy(states)
                evidence["trajectory_id"] = identity
                evidence["sample_count"] = len(states)
        output["trajectory_archive"] = archive
        return output



class ContextualDeliberationService:
    def __init__(self, *, engine: HOTCOCTv43 | None = None, provider: OrionContextProvider | None = None, adapter: RouteContextAdapter | None = None, learned_input_adapter=None) -> None:
        self.engine = engine or HOTCOCTv43()
        self.provider = provider
        self.adapter = adapter or RouteContextAdapter()
        self.learned_input_adapter = learned_input_adapter

    def deliberate(self, request: ContextualDeliberationRequest) -> ContextualDeliberationResponse:
        if request.context_policy != "observations_only":
            raise ContextualDeliberationError("unsupported context_policy")
        # Caches live for one deliberation. Actual state, beliefs, availability
        # and solver parameters determine reuse, never route identity or mode.
        dynamics = {}; summaries = {}; context_queries = {}; provider_holder = {}
        def summarize(participant):
            key = self._effective_input_digest(participant)
            if key not in dynamics:
                with phase('hotco'):result = self.engine.simulate(participant)
                with phase('robustness'):robustness = assess_robustness(participant, result, self.engine)
                dynamics[key] = (result, robustness)
            summary_key = (key, json.dumps(asdict(participant), sort_keys=True, allow_nan=False))
            if summary_key not in summaries:
                result, robustness = dynamics[key]
                summary = self._summary(participant, result, robustness, request.record_trajectories)
                summary.simulation_evidence['effective_input_digest'] = key
                summary.simulation_evidence['reuse_policy'] = 'identical_effective_inputs_and_solver_v1'
                if request.goal_priorities is not None:
                    summary.simulation_evidence['goal_priorities'] = deepcopy(request.goal_priorities)
                    summary.simulation_evidence['effective_initial_needs'] = dict(zip(NEEDS, participant.needs))
                summaries[summary_key] = summary
            return summaries[summary_key]
        baseline = summarize(request.participant)
        base_participant = request.participant
        candidate_results: list[RouteDeliberationResult] = []
        for candidate in request.candidate_routes:
            route_context, source_status, context_warnings = self._enrich_candidate(candidate, request, context_queries, provider_holder)
            participant = self._participant_for_candidate(request, candidate, route_context, base_participant)
            learning = None
            if self.learned_input_adapter is not None:
                participant, learning = self.learned_input_adapter(request,candidate,route_context,participant)
            contextual = summarize(participant)
            if learning is not None:
                contextual = replace(contextual, context_metadata={**contextual.context_metadata,
                    'experimental_learning_applied': bool(learning.get('applied')),
                    'state_carryover_applied': False})
            candidate_results.append(RouteDeliberationResult(candidate.route_id, route_context,
                contextual, self._difference(baseline, contextual), tuple(dict.fromkeys(context_warnings)), source_status, learning))
        return ContextualDeliberationResponse(request.search_id, request.timestamp, baseline,
            tuple(candidate_results), MODEL_VERSION, NORMALIZATION_VERSION, request.warnings, request.goal_priorities)

    def _participant_for_candidate(self, request, candidate, route_context, base_participant):
        """Internal integration seam; current public policy never applies learning.

        A future admitted-input adapter must run after context is frozen. Tests
        exercise differing inputs here without exposing an override in the API.
        """
        return base_participant

    def _effective_input_digest(self, participant):
        digest = hashlib.sha256()
        digest.update(json.dumps({'solver': MODEL_VERSION, 'parameters': asdict(self.engine.parameters),
            'state_columns': [*NEEDS, *MODES, *MODES]}, sort_keys=True, allow_nan=False).encode())
        state, beliefs, availability = self.engine.arrays_from_input(participant)
        for array in (state, beliefs, availability):
            digest.update(str(array.shape).encode('ascii'))
            digest.update(array.astype('<f8').tobytes())
        return digest.hexdigest()

    @timed('context')
    def _enrich_candidate(self, candidate: CandidateRouteInput, request: ContextualDeliberationRequest, query_cache=None, provider_holder=None) -> tuple[RouteContext, dict[str, str], list[str]]:
        supplied = request.observations.get(candidate.route_id, {}) if isinstance(request.observations, Mapping) else {}
        shared = tuple(ContextObservation.from_dict(item) for item in supplied.get("route", ())) if isinstance(supplied, Mapping) else ()
        segment_payload = supplied.get("segments", {}) if isinstance(supplied, Mapping) else {}
        segment_observations = {str(segment_id): [ContextObservation.from_dict(item) for item in values] for segment_id, values in segment_payload.items()}
        preliminary = self.adapter.enrich(candidate, segment_observations=segment_observations)
        for segment in preliminary.segments:
            segment_observations.setdefault(segment.segment_id, []).extend(shared)
        source_status = {"supplied": "OBSERVED" if shared or segment_payload else "EMPTY"}
        warnings: list[str] = []
        query_metadata: dict[str, Any] = {}
        if request.query_configuration.query_orion:
            provider = self.provider or (provider_holder or {}).get('provider')
            if provider is None:
                try:
                    provider = WeatherProvider()
                except OrionConfigurationError as exc:
                    raise ContextualProviderConfigurationError(str(exc)) from exc
            if provider_holder is not None:provider_holder['provider'] = provider
            for segment in preliminary.segments:
                latitude = (segment.start.latitude + segment.end.latitude) / 2.0
                longitude = (segment.start.longitude + segment.end.longitude) / 2.0
                coords = f"{latitude},{longitude}"
                query_metadata[segment.segment_id] = {"method": "segment_midpoint_near_radius", "radius_meters": request.query_configuration.radius_meters, "coordinates": {"latitude": latitude, "longitude": longitude}}
                for family in request.query_configuration.entity_families:
                    radius_meters = request.query_configuration.weather_radius_meters if family == "weather" else request.query_configuration.radius_meters
                    try:
                        query = dict(georel=f"near;maxDistance:{radius_meters:g}", geometry="point",
                                     coords=coords, limit=request.query_configuration.limit, nearest_only=family == "weather")
                        key = (family, tuple(sorted(query.items())))
                        if query_cache is not None and key in query_cache:
                            observations = deepcopy(query_cache[key])
                        else:
                            observations = provider.get_context(family, **query)
                            if query_cache is not None:query_cache[key] = deepcopy(observations)
                        segment_observations.setdefault(segment.segment_id, []).extend(observations)
                        source_status[family] = "OBSERVED" if observations else "EMPTY"
                    except OrionHttpError as exc:
                        source_status[family] = "UNAVAILABLE"
                        warnings.append(f"Orion source {family} unavailable: HTTP {exc.status_code}")
                    except (OrionTimeoutError, OrionNetworkError) as exc:
                        source_status[family] = "UNAVAILABLE"
                        warnings.append(f"Orion source {family} unavailable: {type(exc).__name__}")
                    except OrionProviderError as exc:
                        source_status[family] = "MALFORMED"
                        warnings.append(f"Orion source {family} unusable: {type(exc).__name__}")
        route_context = self.adapter.enrich(candidate, segment_observations=segment_observations, query_metadata=query_metadata)
        return route_context, source_status, warnings

    @timed('summary')
    def _summary(self, participant: ParticipantInput, result: SimulationResult, robustness=None, record_trajectories=False) -> HOTCODeliberationSummary:
        passport = build_base_passport(participant, result, self.engine)["cognitive_passport"]
        deliberation = passport["deliberation"]
        trajectory = result.trajectory[0]
        valence_start = self.engine.n_needs + self.engine.n_modes
        node_activations = {
            "needs": {
                "initial": dict(zip(NEEDS, trajectory[0, : self.engine.n_needs].tolist())),
                "final": dict(zip(NEEDS, trajectory[-1, : self.engine.n_needs].tolist())),
            },
            "valences": {
                "initial": dict(zip(MODES, trajectory[0, valence_start:].tolist())),
                "final": dict(zip(MODES, trajectory[-1, valence_start:].tolist())),
            },
        }
        diagnostics = build_xai_diagnostics(participant, result, self.engine)
        diagnostics["active_constraint_tension_terminal"] = deepcopy(passport["process_diagnostics"]["active_constraint_tension_terminal"])
        diagnostics["robustness"] = robustness or {"status": "NOT_ASSESSED"}
        state = ambiguity_state(diagnostics.get("competition"))
        if state == "CLEAR" and diagnostics["robustness"]["status"] == "SENSITIVE":
            state = "SENSITIVE"
        return HOTCODeliberationSummary(
            deliberation["terminal_action_activations"],
            deliberation["terminal_tendency"],
            result.context_metadata,
            diagnostics,
            node_activations,
            state,
            {"schema_version": "hotco-simulation-evidence-v2", "state_kind": "model_prediction",
             "state_bounds": [-1.0, 1.0], "cognitive_scaling": "unscaled_reciprocal_beliefs",
             "availability_policy": "readout_only_full_four_mode_field",
             "numerical": result.numerical[0], "time_grid": result.times.tolist(),
             "evaluation_status": deliberation["evaluation_status"],
             "readout_version": deliberation["readout_version"],
             "terminal_leaders": deliberation["terminal_leaders"],
             "action_tie_tolerance": deliberation["tie_tolerance"],
             "model_version": MODEL_VERSION, "parameters": asdict(self.engine.parameters),
             "state_columns": [*NEEDS, *("action:"+m for m in MODES), *("valence:"+m for m in MODES)],
             "time_reference": "model_units_not_elapsed_travel_time", "dt": self.engine.parameters.dt,
             "states": trajectory.tolist() if record_trajectories else [], "topology": result.topology[0].tolist(),
             "availability": result.availability[0].tolist(), "projection_corrections": result.projection_corrections},
            terminal_leaders=tuple(deliberation["terminal_leaders"]),
        )

    @staticmethod
    def _difference(baseline: HOTCODeliberationSummary, contextual: HOTCODeliberationSummary) -> dict[str, dict[str, float]]:
        return {
            "final_action_activations": {mode: contextual.final_action_activations[mode] - baseline.final_action_activations[mode] for mode in baseline.final_action_activations},
        }


def run_contextual_deliberation(payload: Mapping[str, Any], *, service: ContextualDeliberationService | None = None) -> dict[str, Any]:
    request = ContextualDeliberationRequest.from_dict(payload)
    return (service or ContextualDeliberationService()).deliberate(request).to_dict()
