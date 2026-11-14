"""HTTP handlers for routed and contextual deliberation."""
from __future__ import annotations
from typing import Any
from flask import jsonify, request, current_app
from model.input_mapping_v4_3 import InputValidationError
from services.contextual_deliberation import ContextualDeliberationError, ContextualProviderConfigurationError, run_contextual_deliberation
from services.routing_bridge import RoutingBridgeError, RoutingUnavailableError, run_routed_deliberation
from infrastructure.api_blueprint import api


@api.post("/api/companion/routed-contextual-deliberation")
def routed_contextual_deliberation_route() -> Any:
    """Fetch real routing candidates, audit geometry, and deliberate ready routes."""
    try:
        source=request.get_json(force=True)
        result=run_routed_deliberation(source)
        if (current_app.extensions.get('companion_results_archive') is not None and
                request.headers.get('X-Companion-Results-Projection')=='companion-search-results-v1'):
            from passport.results_transfer import project,InvalidResultsTransfer
            try:
                result['search_results_transfer']={'available':True,'payload':project(result)}
            except (InvalidResultsTransfer,ValueError,KeyError,TypeError,RecursionError):
                # Archival drift must not erase otherwise valid routes or expose raw data.
                result['search_results_transfer']={'available':False,'error':'results_projection_needs_review'}
        if (current_app.extensions.get('companion_hotco_archive') is not None and
                request.headers.get('X-Companion-Hotco-Archive')=='companion-hotco-transfer-v1'):
            from passport.hotco_transfer import project as project_hotco,InvalidHotcoTransfer
            if result.get('contextual_deliberation') is None:
                result['hotco_transfer']={'available':False,'reason':'no_simulation'}
            else:
                try:
                    result['hotco_transfer']={'available':True,'payload':project_hotco(result,source['cognitive_passport'],current_app.extensions['companion_hotco_signer'])}
                except (InvalidHotcoTransfer,ValueError,KeyError,TypeError,RecursionError):
                    result['hotco_transfer']={'available':False,'error':'hotco_projection_needs_review'}
        return jsonify(result)
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
