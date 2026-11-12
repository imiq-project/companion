"""Read-only validation of retired selection metadata in immutable archives."""
from copy import deepcopy
from datetime import datetime
import math

from model.input_mapping_v4_3 import NEEDS

FOCAL_METHOD = "focal-initial-selection-v1"


def validate_selection(value):
    if not isinstance(value, dict) or set(value) != {"primary", "secondary", "ratings", "confirmed_at"}:
        raise ValueError("Focal equipment requires primary, secondary, ratings and confirmed_at")
    primary, secondary = value["primary"], value["secondary"]
    if not isinstance(primary, str) or primary not in NEEDS or not isinstance(secondary, list) or len(secondary) > 2:
        raise ValueError("Choose one primary goal and up to two secondary goals")
    selected = [primary, *secondary]
    if any(not isinstance(n, str) or n not in NEEDS for n in selected) or len(set(selected)) != len(selected):
        raise ValueError("Focal goals must be known and distinct")
    ratings = value["ratings"]
    if not isinstance(ratings, dict) or set(ratings) != set(selected) or any(type(r) is not int or not 1 <= r <= 7 for r in ratings.values()):
        raise ValueError("Selected goals require explicit ordinal importance ratings from 1 to 7")
    instant = datetime.fromisoformat(value["confirmed_at"].replace("Z", "+00:00"))
    if instant.tzinfo is None:
        raise ValueError("Confirmation time requires a timezone")
    return deepcopy(value)


def reference_needs(passport):
    """Declared initial inputs, never masked activations or simulated finals."""
    values = passport["profile"]["needs"]
    if not isinstance(values, dict) or set(values) != set(NEEDS) or any(
        isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v) or not 0 <= v <= 1
        for v in values.values()
    ):
        raise ValueError("Invalid complete focal reference needs")
    focal = passport.get("focal_goals")
    if focal is not None:
        if not isinstance(focal, dict):
            raise ValueError("Focal goals must be an object")
        if focal.get("schema_version") != "focal-goals-v1" or focal.get("method_version") != FOCAL_METHOD:
            raise ValueError("Unsupported focal mechanism")
        validate_selection({k: focal[k] for k in ("primary", "secondary", "ratings", "confirmed_at")})
        if focal.get("reference_needs") != values:
            raise ValueError("Focal reference needs must match the declared profile")
        if any(abs(values[n] - (r - 1) / 6) > 1e-8 for n, r in focal["ratings"].items()):
            raise ValueError("Focal ratings and declared needs disagree")
        overrides = focal.get("importance_overrides", {})
        if not isinstance(overrides, dict) or any(n not in NEEDS or type(r) is not int or not 1 <= r <= 7 or abs(values[n] - (r-1)/6) > 1e-8 for n, r in overrides.items()):
            raise ValueError("Invalid cumulative explicit importance adjustments")
    return deepcopy(values)
