"""HTTP handlers for routed and contextual deliberation."""
from __future__ import annotations
from typing import Any
from flask import jsonify, request
from model.input_mapping_v4_3 import InputValidationError
from services.contextual_deliberation import ContextualDeliberationError, ContextualProviderConfigurationError, run_contextual_deliberation
from services.routing_bridge import RoutingBridgeError, RoutingUnavailableError, run_routed_deliberation
from infrastructure.api_blueprint import api


@api.post("/api/companion/routed-contextual-deliberation")
def routed_contextual_deliberation_route() -> Any:
    """Fetch real routing candidates, audit geometry, and deliberate ready routes."""
    try:
        return jsonify(run_routed_deliberation(request.get_json(force=True)))
    except RoutingUnavailableError as exc:
        return jsonify({"error": "routing_unavailable", "message": str(exc)}), 502
    except ContextualProviderConfigurationError as exc:
        return jsonify({"error": "context_provider_configuration", "message": str(exc)}), 503
    except (RoutingBridgeError, ContextualDeliberationError, InputValidationError, TypeError, ValueError, KeyError) as exc:
        return jsonify({"error": "invalid_routed_deliberation_request", "message": str(exc)}), 422


@api.post("/api/companion/contextual-deliberation")
def contextual_deliberation_route() -> Any:
    """Run HOTCO and archive each route's observations without external forcing."""

    try:
        return jsonify(run_contextual_deliberation(request.get_json(force=True)))
    except ContextualProviderConfigurationError as exc:
        return jsonify({"error": "context_provider_configuration", "message": str(exc)}), 503
    except (ContextualDeliberationError, InputValidationError, TypeError, ValueError, KeyError) as exc:
        return jsonify({"error": "invalid_contextual_deliberation_request", "message": str(exc)}), 422
