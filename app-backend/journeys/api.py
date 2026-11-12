"""HTTP handlers for journey validation and longitudinal evidence."""
from datetime import datetime, timezone
import sqlite3
from flask import jsonify, request
from .archive import JourneyIdentityConflict, archive_journey, canonical, storage_configuration
from .contract import SCHEMA, evidence_id, validate_journey
from .evidence import SCHEMA as EVIDENCE_SCHEMA, validate_backup


def register_journeys(app):
    enabled,path=storage_configuration()
    app.config.setdefault('JOURNEY_STORAGE_ENABLED',enabled)
    app.config.setdefault('JOURNEY_DB',path)
    @app.get("/api/companion/journeys/contract")
    def journey_contract():
        return jsonify(SCHEMA)

    @app.post("/api/companion/journeys/validate")
    def validate():
        if request.content_length is not None and request.content_length > 8 * 1024 * 1024:
            return jsonify(error="journey_too_large", message="Journey exceeds 8 MiB"), 413
        payload = request.get_json(silent=True)
        if not isinstance(payload, dict) or set(payload) != {"record", "journey"}:
            return jsonify(error="invalid_journey_request", message="Expected record and journey"), 422
        try:
            journey = validate_journey(payload["record"], payload["journey"])
            digest = evidence_id(journey)
            canonical(payload['record'])
        except (ValueError, KeyError, TypeError, IndexError, OverflowError):
            return jsonify(error="invalid_journey_evidence", message="Journey contract or evidence identity is invalid"), 422
        receipt={'stored_on_server':False}
        if app.config['JOURNEY_STORAGE_ENABLED']:
            try:receipt=archive_journey(app.config['JOURNEY_DB'],payload['record'],journey)
            except JourneyIdentityConflict:
                return jsonify(error='journey_identity_conflict',message='Journey ID already belongs to different evidence'),409
            except (sqlite3.Error,OSError):
                response=jsonify(error='journey_storage_unavailable',stored_on_server=False,retryable=True)
                response.status_code=503;response.headers['Retry-After']='5'
                return response
        response=jsonify(schema_version="companion-journey-validation-v1", journey_id=journey["journey_id"],
                       evidence_id=digest, status="valid", **receipt, learning_applied=False,
                       validated_at=datetime.now(timezone.utc).isoformat())
        response.headers['Cache-Control']='no-store'
        return response


def register_longitudinal(app):
    @app.get("/api/companion/longitudinal/contract")
    def longitudinal_contract():
        return jsonify(EVIDENCE_SCHEMA)

    @app.post("/api/companion/longitudinal/validate")
    def validate_longitudinal():
        if request.content_length is not None and request.content_length > 64 * 1024 * 1024:
            return jsonify(error="longitudinal_too_large", message="Use offline validation for exports over 64 MiB"), 413
        try:
            payload = request.get_json(silent=True)
            if not isinstance(payload, dict):
                raise ValueError("Expected a Longitudinal backup")
            index = validate_backup(payload)
        except (ValueError, KeyError, TypeError, IndexError, OverflowError, AttributeError):
            return jsonify(error="invalid_longitudinal_evidence", message="Longitudinal sources, links or index are inconsistent"), 422
        return jsonify(schema_version="longitudinal-validation-v1", status="valid", stored_on_server=False,
                       learning_applied=False, state_carryover_applied=False,
                       passport_versions=len(index["passport_versions"]), searches=len(index["searches"]),
                       experience_events=len(index["experience_events"]), warnings=index["warnings"])


