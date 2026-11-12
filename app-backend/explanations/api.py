"""HTTP handlers for deterministic evidence and optional narration."""
from typing import Any, Mapping
from flask import jsonify, request
from infrastructure.api_blueprint import api, logger
from explanations.contextual_xai import ContextualXAIError, build_contextual_xai
from explanations.contextual_xai_narrator import NarrationError, narrate_minimized_evidence
from explanations.companion_reflection import reflect

@api.post("/api/companion/contextual-explanation")
def contextual_explanation_route() -> Any:
    """Explain an existing contextual result without rerunning HOTCO."""

    try:
        payload = request.get_json(force=True) or {}
        if not isinstance(payload, Mapping):
            raise ContextualXAIError('Expected an object body')
        deliberation = payload.get("deliberation", payload)
        route_id = str(payload.get("route_id", "")).strip()
        if not route_id:
            raise ContextualXAIError("route_id is required")
        return jsonify(build_contextual_xai(deliberation, route_id, payload.get("alternative_mode")))
    except ContextualXAIError as exc:
        return jsonify({"error": "invalid_contextual_explanation_request", "message": str(exc)}), 422


@api.post("/api/companion/contextual-explanation/narrate")
def contextual_explanation_narrate_route() -> Any:
    """Optionally narrate minimized XAI evidence; deterministic fallback is returned on provider failure."""

    try:
        payload = request.get_json(force=True) or {}
        if not isinstance(payload, Mapping):
            raise ContextualXAIError('Expected an object body')
        deliberation = payload.get("deliberation")
        if isinstance(deliberation, Mapping):
            route_id = str(payload.get("route_id", "")).strip()
            if not route_id:
                raise ContextualXAIError("route_id is required")
            deterministic = build_contextual_xai(deliberation, route_id, payload.get("alternative_mode"))
            # Evidence identity is reconstructed and owned entirely by the
            # backend. Android only transports the deterministic deliberation;
            # Gson therefore cannot mutate the canonical evidence document.
            payload = {
                "route_id": route_id,
                "language": payload.get("language", "en"),
                "explanation_type": payload.get("explanation_type", "WHY_THIS_TENDENCY"),
                "evidence_version": deterministic["evidence_version"],
                "minimized_evidence": deterministic["minimized_evidence"],
            }
        return jsonify(narrate_minimized_evidence(payload))
    except (NarrationError, ContextualXAIError) as exc:
        error_code = exc.code if isinstance(exc, NarrationError) else "NARRATION_VALIDATION_FAILED"
        logger.warning('%s', error_code)
        return jsonify({"error": "invalid_contextual_narration_request", "message": str(exc)}), 422


def register_companion_reflection(app):
    @app.post('/api/companion/companion-reflection')
    def companion_reflection():
        if request.content_length is not None and request.content_length>1024*1024:
            return jsonify(error='companion_evidence_too_large'),413
        try:
            return jsonify(reflect(request.get_json(silent=True)))
        except (ValueError,KeyError,TypeError,AttributeError):
            return jsonify(error='invalid_companion_evidence'),422
