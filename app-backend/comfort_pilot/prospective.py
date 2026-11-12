"""Prospective read-only forecasts from compact Android evidence snapshots."""
from infrastructure.longitudinal_names import contract_view as _namespace_view
from datetime import datetime, timezone
from flask import request, jsonify
from journeys.contract import timestamp
from .api import analyze
from .evidence import observations_from_records, _route_context
from .model import probabilities, initial_location, identity

VERSION = "longitudinal-prospective-forecast-v1"

def forecast(data):
    from .weather_contract import LEGACY, INTENSITY, validate_version
    version = validate_version(data.get('weather_model_version', LEGACY))
    if _namespace_view(data.get("schema_version")) != _namespace_view("longitudinal-prospective-request-v1"): raise ValueError("Unknown schema")
    as_of=data["as_of"]; timestamp(as_of)
    passport=data["initial_passport"];subject=passport["agent_id"]
    if not data.get("search_id") or not 1 <= len(data["candidates"]) <= 32: raise ValueError("Invalid candidates")
    if len(data["records"])>512: raise ValueError("Use offline analysis for larger histories")
    predictions=[]
    for c in data["candidates"]:
        mode=c["mode"]
        synthetic={"selection":{"mode_key":mode,"chosen_at":as_of},"experience":{"completion":"as_planned"},
            "plan":{"route":c["route"],"context_at_selection":{"snapshot":c.get("snapshot")}}}
        context=_route_context({"completed_at":as_of},synthetic,mode=mode,weather_model_version=version)
        predictions.append({"prediction_id":c["prediction_id"],"mode":mode,"as_of":as_of,"context":context})
    results={};excluded={}
    for target in ("comfort","affect"):
        observations,excluded[target]=observations_from_records(data["records"],subject=subject,target=target,exclusions=data.get("evidence_exclusions"),weather_model_version=version)
        # The device snapshot may include a concurrently confirmed experience.
        # Freeze the same strictly earlier evidence for every model/candidate.
        observations=[o for o in observations if timestamp(o["confirmed_at"]) < timestamp(as_of)]
        if len(observations)>512:raise ValueError("Use offline analysis for larger histories")
        results[target]=analyze({"schema_version":f"longitudinal-{target}-pilot-request-v1","subject_id":subject,
            "initial_passport":passport,"observations":observations,"prediction_contexts":predictions,
            "prior_profile":"moderate","draws":512, **({'weather_model_version': version} if _namespace_view(version) == _namespace_view(INTENSITY) else {})},target=target)
        for p in results[target]["predictions"]:
            value=passport["profile"]["valences"][p["mode"]] if target=="affect" else passport["profile"]["beliefs"][p["mode"]]["comfort"]
            probs=probabilities(initial_location(value)).tolist()
            p["models"]["static"]={"category_probabilities":probs,"prediction_usable_for_research":True,
                "observation_count":0,"reference":"initial_declared_or_ridge_point_expectation", "initial_normalized_value":value}
    return {"schema_version":VERSION,"search_id":data["search_id"],"as_of":as_of,
        "request_digest":identity(data),"computed_at":datetime.now(timezone.utc).isoformat(),
        "targets":results,"excluded_legacy_events":excluded,
        "learning_applied_to_active_passport":False,"hotco_modified":False,"stored_on_server":False}

def register(app):
    @app.post("/api/companion/longitudinal/prospective/forecast")
    def prospective_forecast():
        from .forecast_transport import read_forecast_json, ForecastBodyError
        from .prospective_archived import REQUEST, forecast_archived
        try:
            data = read_forecast_json(request)
            return jsonify(forecast_archived(data) if isinstance(data, dict) and _namespace_view(data.get('schema_version')) == _namespace_view(REQUEST) else forecast(data))
        except ForecastBodyError as exc:
            return jsonify(error=exc.error, message=str(exc)), exc.status
        except (ValueError,KeyError,TypeError,IndexError,AttributeError):
            return jsonify(error="invalid_prospective_request",message="Invalid prospective evidence or context"),422
