"""Bounded, deterministic sensitivity checks; never probabilities or learning."""
from dataclasses import replace
import numpy as np
from model.compat import HOTCOCTv43
from model.input_mapping_v4_3 import MODES
from model.action_readout import signed_action_readout


def assess_robustness(participant, result, engine):
    active = np.flatnonzero(np.asarray(participant.availability) == 1)
    if len(active) <= 1:
        return {"status": "NOT_APPLICABLE", "scenario_count": 0}
    sl = slice(engine.n_needs, engine.n_needs + engine.n_modes)
    terminal = result.trajectory[0, -1, sl]
    readout=signed_action_readout(terminal, participant.availability)
    if readout["terminal_tendency"] is None:
        return {"status":"TIED_REFERENCE", "scenario_count":0, "terminal_leaders":readout["terminal_leaders"]}
    order = active[np.argsort(terminal[active])[::-1]]
    winner, rival = int(order[0]), int(order[1])
    reference_margin = float(terminal[winner] - terminal[rival])
    scenarios = []
    # Local stress tests only. +/-0.02 is a declared provisional amplitude,
    # not an estimated measurement error or population distribution.
    for direction in (-1, 1):
        beliefs = np.array(participant.beliefs, dtype=float, copy=True)
        signed_needs = np.sign(np.asarray(participant.needs))
        beliefs[winner] += direction * 0.02 * signed_needs
        beliefs[rival] -= direction * 0.02 * signed_needs
        variant = replace(participant, beliefs=np.clip(beliefs, -1, 1).tolist())
        values = engine.simulate(variant).trajectory[0, -1, sl]
        other = np.max(values[active[active != winner]])
        scenarios.append({"name": "belief_pair_stress", "direction": direction,
                          "winner": signed_action_readout(values, participant.availability)["terminal_tendency"],
                          "terminal_leaders": signed_action_readout(values, participant.availability)["terminal_leaders"],
                          "reference_winner_margin": float(values[winner] - other)})
    fine_engine = HOTCOCTv43(parameters=replace(engine.parameters, rtol=engine.parameters.rtol / 10, atol=engine.parameters.atol / 10, max_step=engine.parameters.max_step / 2))
    fine_result = fine_engine.simulate(participant)
    fine_engine_result_verified = fine_result.numerical[0]["verified_stable_equilibrium"]
    fine = fine_result.trajectory[0, -1, sl]
    error = float(np.max(np.abs(fine[active] - terminal[active])))
    fine_winner = int(active[np.argmax(fine[active])])
    # Two action errors bound uncertainty in their difference conservatively.
    numeric_stable = fine_winner == winner and reference_margin > 2 * error and result.numerical[0]["verified_stable_equilibrium"] and fine_engine_result_verified
    retained = sum(s["winner"] == MODES[winner] for s in scenarios)
    separated = all(s["reference_winner_margin"] > engine.parameters.practical_margin_threshold for s in scenarios)
    return {"status": "STABLE_IN_CHECKED_SCENARIOS" if numeric_stable and separated else "SENSITIVE",
            "scenario_count": len(scenarios), "winner_retained_count": retained,
            "belief_amplitude": 0.02, "scenarios": scenarios,
            "numerical_check": {"refined_max_step": fine_engine.parameters.max_step, "refined_rtol": fine_engine.parameters.rtol,
                                "max_action_difference": error, "winner_preserved": fine_winner == winner,
                                "margin_exceeds_error_bound": numeric_stable},
            "scope": "local belief-pair stress and adaptive-tolerance and maximum-step refinement; not calibrated choice confidence; route-time uncertainty not assessed"}
