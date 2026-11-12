"""Compatibility import path for the current signed HOTCO–Grossberg engine.

Historical v4.3 implementation: hotco_ct_v4_3_legacy.py. New operational
simulations always carry hotco_signed_grossberg_5goals_v1; no historical data migrate.
"""
from model.hotco_signed_v1 import (
    SignedHOTCO, SignedHOTCOParameters, SimulationResult,
    HOTCOCTv43, HOTCOCTParameters, MODEL_VERSION, MODEL_NAME,
)
