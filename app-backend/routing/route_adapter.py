"""Routing transport adaptation with explicit availability and geometry provenance."""
from __future__ import annotations
from routing.validation import RoutingBridgeError
import copy
import hashlib
import json
import math
from context.route_context import CandidateRouteInput, Coordinate

MODES = {"foot": "walk", "walk": "walk", "bike": "bike", "car": "car", "pt": "pt"}

def routing_passport(passport):
    """Translate technical access explicitly; never derive it from preference."""
    if not isinstance(passport, dict):
        raise RoutingBridgeError("cognitive_passport must be an object")
    root = copy.deepcopy(passport.get("cognitive_passport", passport))
    if not isinstance(root, dict):
        raise RoutingBridgeError("cognitive_passport envelope must contain an object")
    profile = root.get("profile", {})
    if not isinstance(profile, dict):
        raise RoutingBridgeError("passport profile must be an object")
    availability = profile.get("availability")
    if availability is None:
        availability = root.get("routing_parameters", {}).get("availability")
    if not isinstance(availability, dict) or any(type(availability.get(mode)) is not bool for mode in ("walk", "bike", "pt", "car")):
        raise RoutingBridgeError("passport must declare boolean availability for walk, bike, pt and car")
    root["beliefs"] = {
        "owns_bike": availability["bike"],
        "owns_car": availability["car"],
        "has_pt_access": availability["pt"],
    }
    return root, availability


def _geometry(value):
    if isinstance(value, dict) and value.get("type") == "LineString":
        points = value.get("coordinates", [])
        try:
            result = [Coordinate(float(p[1]), float(p[0])).to_dict() for p in points]
        except (TypeError, ValueError, IndexError):
            return []
        return result if len(result) >= 2 and all(math.isfinite(v) for p in result for v in p.values()) else []
    return []


def _metric(value, name):
    number = float(value)
    if not math.isfinite(number) or number < 0:
        raise RoutingBridgeError(f"{name} must be finite and nonnegative")
    return number


def adapt_route(route, *, search_id, origin, destination, departure, availability):
    """Return (candidate, rejection_reason). Full leg geometry is required."""
    mode_key = route.get("mode_key")
    required = {"foot": {"walk"}, "walk": {"walk"}, "bike": {"bike"}, "car": {"car"}, "pt": {"pt"}, "bike_pt": {"bike", "pt"}, "car_pt": {"car", "pt"}}.get(mode_key)
    if required is None:
        return None, "unsupported_mode"
    if route.get("available") is False or route.get("feasible") is False or not all(availability[m] for m in required):
        return None, "unavailable_or_infeasible"
    supplied_legs = route.get("legs") or []
    if any(MODES.get(leg.get("mode")) is None for leg in supplied_legs):
        return None, "unsupported_leg_mode"
    if any(not availability[MODES[leg["mode"]]] for leg in supplied_legs):
        return None, "unavailable_leg_mode"
    overall = _geometry(route.get("geometry"))
    summary = route.get("summary") or {}
    if not supplied_legs and len(required) == 1 and overall:
        supplied_legs = [{"mode": next(iter(required)), "geometry": route["geometry"], **summary}]
    if not supplied_legs:
        return None, "missing_legs_or_geometry"
    legs = []
    for i, leg in enumerate(supplied_legs):
        geometry = _geometry(leg.get("geometry"))
        if not geometry and len(supplied_legs) == 1:
            geometry = overall
        if not geometry:
            return None, "missing_leg_geometry"
        mode = MODES[leg["mode"]]
        duration = _metric(leg.get("duration_seconds"), "leg duration")
        distance = _metric(leg.get("distance_meters"), "leg distance")
        if duration <= 0:
            return None, "missing_positive_leg_duration"
        legs.append({"segment_id": f"leg-{i+1}", "mode": mode, "start": geometry[0], "end": geometry[-1], "geometry": geometry, "duration_seconds": duration, "distance_meters": distance, **({"transit": copy.deepcopy(leg["transit"])} if "transit" in leg else {})})
    if not required.issubset({leg["mode"] for leg in legs}):
        return None, "mode_sequence_does_not_match_route"
    _metric(summary.get("duration_seconds"), "route duration")
    _metric(summary.get("distance_meters"), "route distance")
    # Hash excludes rank, which changes when utility is normalised/reordered.
    identity = json.dumps({"search": search_id, "departure": departure, "mode": mode_key, "legs": legs}, sort_keys=True)
    route_id = "routing-" + hashlib.sha256(identity.encode()).hexdigest()[:20]
    evidence = route.get('_geometry_evidence', {})
    return CandidateRouteInput(route_id, origin, destination, summary=summary, legs=tuple(legs), geometry=overall or None, requested_at=departure, transport_modes=tuple(leg["mode"] for leg in legs), source_metadata={"provider": route.get("provider", "imiq-routing"), "geometry_provenance": "ROUTING_LEG_GEOMETRY", "segment_timing_method": "leg_duration_allocated_by_sampled_distance", "unallocated_duration_seconds": max(0.0, float(summary["duration_seconds"]) - sum(leg["duration_seconds"] for leg in legs)), "routing_rank": route.get("rank"), "routing_score": route.get("score"), "mode_key": mode_key, **({"provider_itinerary_id": route["provider_itinerary_id"], "transit": copy.deepcopy(route.get("transit", {}))} if route.get("provider_itinerary_id") else {}), **evidence}), None
