"""Frozen observed measurements shared by Passport reconstruction and revisions."""
from copy import deepcopy
import math
from model.input_mapping_v4_3 import NEEDS, MODES


def estimation_basis(passport):
    observed = passport.get("observed_measurements", {})
    if "needs_allocation" in observed:
        original_needs = {n: observed["needs_allocation"]["points"][n]/10 for n in NEEDS}
    elif "needs_raw_1_to_7" in observed:
        original_needs = {n: (observed["needs_raw_1_to_7"][n]-1)/6 for n in NEEDS}
    else:
        original_needs = passport["profile"]["needs"]
    original_valences = ({m: observed["valences_raw_minus3_to_3"][m]/3 for m in MODES}
                         if "valences_raw_minus3_to_3" in observed else passport["profile"]["valences"])
    basis = passport.get("belief_estimation_inputs", {"needs": original_needs, "valences": original_valences})
    for name, keys, lower in (("needs", NEEDS, 0), ("valences", MODES, -1)):
        values = basis.get(name)
        if not isinstance(values, dict) or set(values) != set(keys):
            raise ValueError("Invalid frozen belief estimation inputs")
        if any(isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v) or not lower <= v <= 1 for v in values.values()):
            raise ValueError("Frozen belief estimation values are outside their bounds")
    return deepcopy(basis)
