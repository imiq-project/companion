"""Shared routing input checks, independent of orchestration and HTTP handlers."""
import math
from datetime import datetime
from context.route_context import Coordinate


class RoutingBridgeError(ValueError):
    pass

def _coordinate(value):
    if not isinstance(value, dict):
        raise RoutingBridgeError("coordinates must be objects with lat and lon")
    lat, lon = float(value["lat"]), float(value["lon"])
    if not math.isfinite(lat) or not math.isfinite(lon):
        raise RoutingBridgeError("coordinates must be finite")
    return Coordinate(lat, lon)

def _datetime(value):
    if not isinstance(value, str):
        raise RoutingBridgeError("datetime is required, including a timezone")
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise RoutingBridgeError("datetime must include a timezone")
    return value
