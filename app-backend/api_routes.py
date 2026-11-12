"""Common HTTP validation, health and compatibility registration."""
from __future__ import annotations
import logging
import os
import threading
from typing import Any, Dict, Mapping
from flask import Blueprint, current_app, jsonify, request
from werkzeug.exceptions import BadRequest
from infrastructure.longitudinal_names import canonical_api_path
from model.compat import HOTCOCTv43, MODEL_NAME, MODEL_VERSION
from model.action_readout import READOUT_VERSION
from infrastructure.service_config import positive_integer
from model.input_mapping_v4_3 import INPUT_SCHEMA_VERSION, InputValidationError, parse_participant_input
from passport.passport_xai import PASSPORT_SCHEMA_VERSION, build_cognitive_passport
from passport.profile_update import PROFILE_UPDATE_SCHEMA_VERSION, prepare_profile_update
from passport.adaptive_passport_v1 import AdaptivePassportError
from passport.adaptive_passport_onboarding import ONBOARDING_SCHEMA_VERSION, start_adaptive_passport, complete_adaptive_passport
from passport.adaptive_profile_update import ADAPTIVE_PROFILE_UPDATE_SCHEMA_VERSION, AdaptiveProfileUpdateError, prepare_adaptive_profile_update
from passport.adaptive_passport_xai import build_adaptive_cognitive_passport
from passport.adaptive_hotco_bootstrap import ADAPTIVE_HOTCO_BOOTSTRAP_SCHEMA_VERSION, AdaptiveHotcoBootstrapError, prepare_adaptive_hotco_bootstrap
from services.contextual_deliberation import ContextualDeliberationError, ContextualProviderConfigurationError, run_contextual_deliberation
from explanations.contextual_xai import ContextualXAIError, build_contextual_xai
from explanations.contextual_xai_narrator import NarrationError, digital_companion_contract_status, narrate_minimized_evidence
from context.orion_context import DEFAULT_BASE_URL, PUBLIC_TIMEOUT_SECONDS
from services.routing_bridge import RoutingBridgeError, RoutingUnavailableError, run_routed_deliberation

from infrastructure.api_blueprint import api, logger
from services.model_runtime import engine, model_lock
from services.passport_service import _run_one, run_companion, run_profile_update, run_adaptive_hotco_bootstrap, run_adaptive_profile_update

@api.before_app_request
def validate_companion_json_body() -> Any:
    """Reject malformed JSON consistently before endpoint error handlers."""
    is_companion = (
        request.path == "/api/companion"
        or request.path.startswith("/api/companion/")
    )
    if request.method != "POST" or not is_companion:
        return None

    if canonical_api_path(request.path) == '/api/companion/longitudinal/prospective/forecast' or canonical_api_path(request.path).startswith('/api/companion/longitudinal/backend-experiment/'):
        # This endpoint performs bounded decoding before JSON parsing, including
        # lossless gzip bodies. Do not pre-parse its wire bytes as plain JSON.
        return None

    try:
        from infrastructure.service_runtime import check_body_limit
        rejection = check_body_limit()
        if rejection is not None:
            return rejection
        # Flask caches the parsed body for the endpoint.
        request.get_json(force=True)
    except (BadRequest, RecursionError):
        return jsonify({
            "error": "invalid_json",
            "message": "Request body must contain valid JSON.",
        }), 400

    return None


@api.get("/health")
def health() -> Any:
    return jsonify(
        {
            "status": "ok",
            "service": "companion",
            "model": MODEL_NAME,
            "model_version": MODEL_VERSION,
            "action_readout_version": READOUT_VERSION,
            "goal_priorities_version": "goal-priorities-5-v1",
            "input_schema_version": INPUT_SCHEMA_VERSION,
            "passport_schema_version": PASSPORT_SCHEMA_VERSION,
            "profile_update_schema_version": PROFILE_UPDATE_SCHEMA_VERSION,
            "adaptive_questioning": True,
            "longitudinal_passport_lineage": True,
            "learned_passport": {"schema_version":"longitudinal-learned-passport-v1",
                "status":"versioned_shadow","persistence":"client_owned",
                "active_passport_update":False,"hotco_update":False},
            "prospective_forecast": {"request_versions": ["longitudinal-prospective-request-v1", "longitudinal-prospective-request-v2"],
                "archived_prediction_without_refit": True, "request_gzip": True,
                "operational_learning_enabled": False},
            "belief_admission": {"schema_version": "longitudinal-admission-shadow-v1",
                "status": "candidate_shadow_only", "method_qualified": False,
                "active_passport_update": False, "hotco_update": False},
            "backend_experiment": {"schema_version":"longitudinal-backend-search-v1",
                "configured": current_app.extensions.get('longitudinal_backend_experiment') is not None,
                "production_authorized":False},
            "comfort_pilot": {"model_version":"longitudinal-comfort-ordinal-v1","status":"experimental_shadow",
                "active_passport_update":False,"hotco_update":False,"uv_excluded":True},
            "affect_pilot": {"model_version":"longitudinal-affect-ordinal-v1","status":"experimental_shadow","normalization":"raw/3",
                "active_passport_update":False,"hotco_update":False,"uv_excluded":True},
            "input_policy": "current_user_responses_only",
            "imputation": False,
            "adaptive_passport": {
                "status": "experimental",
                "schema_version": ONBOARDING_SCHEMA_VERSION,
                "model_version": "five-goals-explicit-v1",
                "initial_question_count": 20,
                "initial_question_policy": "complete_declared_five_goal_matrix",
                "estimated_beliefs": False,
                "explicit_belief_provenance": True,
                "strict_companion_endpoint_unchanged": True,
                "profile_update_schema_version": ADAPTIVE_PROFILE_UPDATE_SCHEMA_VERSION,
                "bootstrap_schema_version": ADAPTIVE_HOTCO_BOOTSTRAP_SCHEMA_VERSION,
                "initial_hotco_bootstrap": True,
                "longitudinal_estimated_to_observed_updates": False,
            },
            "runtime": "numpy/cpu",
            "transit": {"provider": "otp", "configured": bool(os.environ.get("COMPANION_OTP_BASE_URL"))},
            "digital_companion": digital_companion_contract_status(),
            "orion": {
                "mode": "public",
                "base_url": DEFAULT_BASE_URL,
                "auth_required": False,
                "timeout_seconds": PUBLIC_TIMEOUT_SECONDS,
                "context_contract_version": "route-context-v1",
            },
            "context_model": {"active_context_model": "observations_only", "context_model_status": "NOT_APPLIED", "external_context_drive_supported": False, "bayesian_learning_applied": False},
        }
    )


from explanations.api import contextual_explanation_route, contextual_explanation_narrate_route

from passport.api import companion_route, adaptive_passport_start_route, adaptive_passport_complete_route, adaptive_passport_bootstrap_route, adaptive_passport_profile_update_route, profile_update_route
from services.api import routed_contextual_deliberation_route, contextual_deliberation_route
