"""Routing gateway: explicit availability, geometry audit, then HOTCO context.

The colleague's repository remains untouched. Missing route geometry is never
silently replaced with a straight line. Routing utility remains independent
of the HOTCO modal readout.
"""
from __future__ import annotations

from routing.routing_client import RoutingClient, RoutingUnavailableError
from routing.route_adapter import MODES, routing_passport, _geometry, _metric, adapt_route

from routing.validation import RoutingBridgeError, _coordinate, _datetime

from routing.journey_availability import validate_journey_availability
from routing.otp_transit import OtpTransitClient
from model.hotco_signed_v1 import MODEL_VERSION

import copy
import hashlib
import json
import math
import os
from datetime import datetime
from dataclasses import replace
from concurrent.futures import ThreadPoolExecutor
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

from services.contextual_deliberation import ContextualDeliberationRequest, ContextualDeliberationService
from context.route_context import CandidateRouteInput, Coordinate
from routing.graphhopper_geometry import GraphHopperGeometryClient, GeometryError, supplemental_route, independent_route
from routing.parking_planner import parking_path
from context.weather_provider import WeatherProvider as OrionQueryListProvider
from infrastructure.service_config import positive_number, positive_integer, provider_url
from infrastructure.service_runtime import phase, timed
from infrastructure.http_client import read_json_response





















@timed('routing_total')
def run_routed_deliberation(payload, *, client=None, deliberate=None, geometry_client=None, transit_client=None):
    if not isinstance(payload, dict):
        raise RoutingBridgeError("request must be an object")
    climate_enabled = 'climate_profile' in payload
    if climate_enabled:
        from climate.climate_impact import normalize_profile
        climate_profile = normalize_profile(payload['climate_profile'])
    search_id = payload.get("search_id")
    if not isinstance(search_id, str) or not search_id.strip():
        raise RoutingBridgeError("search_id is required")
    departure = _datetime(payload.get("datetime"))
    origin, destination = _coordinate(payload["start"]), _coordinate(payload["stop"])
    passport, availability = routing_passport(payload.get("cognitive_passport"))
    if "journey_availability" in payload:
        availability = validate_journey_availability(payload["journey_availability"])
        # This is a disposable transport envelope; the saved Passport stays original.
        passport["profile"]["availability"] = dict(availability)
        passport.setdefault("routing_parameters", {})["availability"] = dict(availability)
        passport["beliefs"] = {"owns_bike": availability["bike"], "owns_car": availability["car"], "has_pt_access": availability["pt"]}
    max_walk = payload.get("max_walk_m", 500)
    if type(max_walk) is not int or max_walk < 0:
        raise RoutingBridgeError("max_walk_m must be a nonnegative integer")
    # Validate the original passport through the existing strict HOTCO contract
    # BEFORE disclosing it to the server-configured routing service.
    base = {"required_readout_version": payload.get("required_readout_version", "signed-actions-v1"), "required_model_version": payload.get("required_model_version", MODEL_VERSION), "context_policy": payload.get("context_policy", "observations_only"), "record_trajectories": payload.get("record_trajectories") is True, "search_id": search_id, "timestamp": departure, "cognitive_passport": payload["cognitive_passport"], "contextual_query": payload.get("contextual_query", {"query_orion": True}), "candidate_routes": [CandidateRouteInput("validation", origin, destination).to_dict()]}
    if "journey_availability" in payload:
        base["journey_availability"] = dict(availability)
    if "tolerance_profile" in payload:
        base["tolerance_profile"] = payload["tolerance_profile"]
    validated_request = ContextualDeliberationRequest.from_dict(base)
    otp_enabled = transit_client is not None or bool(os.environ.get('COMPANION_OTP_BASE_URL'))
    geometry_enabled = geometry_client is not None or bool(os.environ.get('COMPANION_GRAPHHOPPER_BASE_URL'))
    geometry_cache = {}
    def geometry_path(mode):
        geometry = geometry_client or GraphHopperGeometryClient()
        if mode == 'car' and payload.get('car_parking') is not None:
            return parking_path(geometry, payload['start'], payload['stop'], payload['car_parking'])
        return geometry.path(mode, payload['start'], payload['stop'])
    provider_warnings = []
    routing_failure = None
    def retrieve_transit():
        if payload.get('boarding_stop_id'):
            from routing.stop_planning import plan_from_stop
            routes, audit, _ = plan_from_stop({**payload, 'availability': availability, 'datetime': departure}, client=transit_client, geometry=geometry_client)
            return routes, audit
        return (transit_client or OtpTransitClient()).routes(start=payload['start'], stop=payload['stop'], departure=departure, availability=availability, max_walk=max_walk)
    # Independent providers use the same validated request. Retain their order,
    # errors and RT identity checks, but overlap their network/geometry waits.
    parallelism=positive_integer('COMPANION_PROVIDER_PARALLELISM',3)
    with phase('providers'), ThreadPoolExecutor(max_workers=min(2,parallelism)) as providers:
        ranked_query = providers.submit((client or RoutingClient()).ranked_routes, {"cognitive_passport": passport, "start": payload["start"], "stop": payload["stop"], "datetime": departure, "max_walk_m": max_walk, "include_unavailable": False})
        transit_query = providers.submit(retrieve_transit) if otp_enabled and availability['pt'] else None
    try:
        routing = ranked_query.result()
        routing = copy.deepcopy(routing)
    except RoutingUnavailableError as exc:
        if not (otp_enabled and availability['pt']) and not geometry_enabled:raise
        routing_failure = exc
        routing = {"routes": []}
        provider_warnings.append('Existing routing unavailable; independently retrieved OTP and GraphHopper options are used when available.')
    routing.setdefault('provider_audit', {})['imiq-routing'] = {
        'provider': 'imiq-routing', 'status': 'UNAVAILABLE' if routing_failure else 'AVAILABLE',
        'route_count': len(routing['routes']),
    }
    if not otp_enabled or not availability['pt']:
        routing.setdefault('provider_audit', {})['otp'] = {
            'provider': 'otp', 'status': 'DISABLED' if not otp_enabled else 'NOT_REQUESTED',
            'reason': 'NOT_CONFIGURED' if not otp_enabled else 'PUBLIC_TRANSPORT_UNAVAILABLE'}
    if payload.get('boarding_stop_id'):
        routing['routes']=[r for r in routing['routes'] if MODES.get(r.get('mode_key'))!='pt']
    if otp_enabled and availability['pt']:
        try:
            transit_routes, transit_audit = transit_query.result()
            # Ranks are unique presentation positions, not comparable provider scores.
            last_rank = max((r.get('rank', 0) for r in routing['routes'] if isinstance(r, dict) and type(r.get('rank')) is int), default=0)
            for offset, route in enumerate(transit_routes, 1):
                route['rank'] = last_rank + offset
                routing['routes'].append(route)
            routing.setdefault('provider_audit', {})['otp'] = transit_audit
            routing['ordering_policy'] = 'existing_provider_order_then_otp_order_no_combined_utility'
            provider_warnings.append('OTP alternatives retain independent provider order; their presentation positions and scores are not a combined utility ranking.')
            if transit_routes:
                provider_warnings.append('OTP transit geometry is approximate without shapes.txt. Waiting intervals and delays are retained as travel facts, not additional HOTCO forcing.')
        except (OSError, ValueError, TypeError, KeyError) as exc:
            routing.setdefault('provider_audit', {})['otp'] = {'provider': 'otp', 'status': 'UNAVAILABLE', 'reason': type(exc).__name__}
            provider_warnings.append('OTP unavailable; existing routes retained. No transit itinerary was fabricated.')
        # A successful OTP query with no matching itinerary is a valid search
        # result. Preserve its rejection reasons so the UI can explain the
        # walking limit, even if the independent routing provider failed.
    # A missing provider option must not suppress another available travel mode.
    # These paths have independent provider order and no imported utility score.
    missing_modes = [mode for mode in ('walk', 'bike', 'car') if availability[mode]
                     and not any(MODES.get(r.get('mode_key')) == mode and r.get('available') is True
                                 and r.get('feasible') is not False for r in routing['routes'])]
    geometry_audit = {}
    if geometry_enabled and missing_modes:
        def retrieve(mode):
            try:
                return mode, geometry_path(mode), None
            except (OSError, ValueError, TypeError, KeyError) as exc:
                return mode, None, type(exc).__name__
        with phase('geometry'), ThreadPoolExecutor(max_workers=min(3,parallelism)) as pool:
            for mode, path, failure in pool.map(retrieve, missing_modes):
                geometry_audit[mode] = {'status': 'UNAVAILABLE' if failure else 'AVAILABLE', 'reason': failure}
                if path:
                    if path.get('walk_distance_meters', 0) > max_walk or (path.get('walk_distance_meters', 0) > 0 and not availability['walk']):
                        geometry_audit[mode] = {'status': 'UNAVAILABLE', 'reason': 'pedestrian_access_not_allowed'}
                        continue
                    geometry_cache[mode] = path
                    rank = max((r.get('rank', 0) for r in routing['routes'] if type(r.get('rank')) is int), default=0) + 1
                    routing['routes'].append(independent_route(mode, path, rank))
        routing['ordering_policy'] = 'independent_provider_order_no_combined_utility'
    routing['provider_audit']['graphhopper'] = {
        'provider': 'graphhopper', 'configured': geometry_enabled, 'modes': geometry_audit,
    }
    if routing_failure and not routing['routes'] and routing['provider_audit'].get('otp', {}).get('status') != 'NO_MATCHING_ITINERARIES':
        raise routing_failure
    candidates, audit = [], []
    for route in routing["routes"]:
        if not isinstance(route, dict):
            raise RoutingUnavailableError("routing returned a malformed route")
        try:
            candidate, reason = adapt_route(route, search_id=search_id, origin=origin, destination=destination, departure=departure, availability=availability)
        except (TypeError, ValueError, KeyError):
            candidate, reason = None, "invalid_route_metrics_or_geometry"
        if reason in ("unavailable_or_infeasible", "unavailable_leg_mode"):
            route["available"] = False
        geometry_failure = None
        parking_requested = route.get('mode_key') == 'car' and payload.get('car_parking') is not None
        if (candidate is None and reason in ('missing_leg_geometry', 'missing_legs_or_geometry') or parking_requested) and geometry_enabled and route.get('mode_key') in ('car', 'bike', 'foot', 'walk'):
            mode = route['mode_key']
            # Never replace an intermodal leg sequence with one single-mode path.
            if parking_requested or all(MODES.get(leg.get('mode')) == MODES[mode] for leg in route.get('legs', [])):
                try:
                    if mode not in geometry_cache:
                        geometry_cache[mode] = geometry_path(mode)
                    independent = supplemental_route(route, geometry_cache[mode])
                    walking = independent['summary'].get('walk_distance_meters', 0)
                    if walking > max_walk or (walking > 0 and not availability['walk']):
                        route['available'] = False
                        route['feasible'] = False
                        route['unavailable_reason'] = 'walking_limit_exceeded' if walking > max_walk else 'walking_unavailable'
                        raise GeometryError('Pedestrian access exceeds walking limit or walking is unavailable')
                    candidate, reason = adapt_route(independent, search_id=search_id, origin=origin, destination=destination, departure=departure, availability=availability)
                    if candidate:
                        # Display and execution must use the same legs/times as
                        # the independently evaluated supplemental candidate.
                        route.update(independent)
                except (OSError, ValueError, TypeError, KeyError) as exc:
                    if parking_requested:
                        candidate = None
                        route['available'] = False
                        route['unavailable_reason'] = 'parking_route_unavailable'
                    # Source outages do not erase valid routing results.
                    geometry_failure = type(exc).__name__
                    reason = 'supplemental_geometry_unavailable'
        audit.append({"routing_rank": route.get("rank"), "mode_key": route.get("mode_key"), "route_id": candidate.route_id if candidate else None, "context_ready": candidate is not None, "reason": reason, "geometry_failure": geometry_failure, "geometry_evidence": dict(candidate.source_metadata) if candidate else None})
        if candidate:
            candidates.append(candidate.to_dict())
    contextual = None
    if candidates:
        base["candidate_routes"] = candidates
        if deliberate is not None:
            contextual = deliberate(base)
        else:
            service = ContextualDeliberationService(provider=OrionQueryListProvider())
            contextual = service.deliberate(ContextualDeliberationRequest.from_dict(base)).to_dict()
    elif any(route.get('available') is True for route in routing['routes']):
        # Internal empty-candidate run provides only the saved Passport baseline.
        # No placeholder route is queried, simulated as exposure, or returned.
        service = ContextualDeliberationService(provider=OrionQueryListProvider())
        contextual = service.deliberate(replace(validated_request, candidate_routes=())).to_dict()
    supplemental = any(c['source_metadata'].get('route_identity_verified') is False for c in candidates)
    result = {"schema_version": "routed-contextual-deliberation-v1", "search_id": search_id, "journey_availability": dict(availability), "availability_source": "current_journey_declaration" if "journey_availability" in payload else "confirmed_passport", "status": "contextual_complete" if candidates and len(candidates) == len(audit) else "contextual_partial" if candidates else "routing_only", "routing": routing, "route_audit": audit, "candidate_routes": candidates, "contextual_deliberation": contextual, "warnings": provider_warnings + ["Routing utility and HOTCO modal readouts have different meanings; no combined route ranking is computed."] + (["Independent GraphHopper paths are supplemental same-mode candidates; identity with externally ranked routes is NOT verified. Their geometry and timing both come from GraphHopper."] if supplemental else []) + (["Some routes were excluded from contextual simulation; consult route_audit."] if len(candidates) != len(audit) else [])}
    from datetime import datetime, timezone
    if climate_enabled:
        from climate.climate_impact import annotate
        annotate(result, climate_profile, datetime.now(timezone.utc).isoformat())
    from comfort_pilot.prospective_archived import routed_prediction_contexts
    try:
        result['d_day_prediction_contexts'] = routed_prediction_contexts(result, datetime.now(timezone.utc).isoformat())
    except (ValueError, KeyError, TypeError):
        # Research projection is auxiliary: do not discard otherwise valid routes.
        # An empty projection becomes an explicit failed prechoice forecast.
        result['d_day_prediction_contexts'] = []
        result['d_day_prediction_context_failure'] = 'compact_context_projection_unavailable'
    return result
