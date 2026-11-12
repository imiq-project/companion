"""Direct signed action evaluations. No probability transform or tie breaking."""
import numpy as np
from model.input_mapping_v4_3 import MODES

READOUT_VERSION = "signed-actions-v1"
ACTION_TIE_TOLERANCE = 1e-6


def signed_action_readout(actions, availability, tie_tolerance=ACTION_TIE_TOLERANCE):
    actions=np.asarray(actions,dtype=float);availability=np.asarray(availability,dtype=float)
    if actions.shape!=(len(MODES),) or not np.isfinite(actions).all() or np.any(np.abs(actions)>1+1e-10):
        raise ValueError("signed actions must be four finite values in [-1,1]")
    if availability.shape!=actions.shape or not np.isin(availability,[0,1]).all() or not np.any(availability==1):
        raise ValueError("at least one available mode is required")
    if not np.isfinite(tie_tolerance) or tie_tolerance<0:
        raise ValueError("invalid action tie tolerance")
    active=np.flatnonzero(availability==1)
    highest=float(np.max(actions[active]))
    leaders=[MODES[i] for i in active if highest-actions[i]<=tie_tolerance]
    ordered=sorted(active,key=lambda i:-actions[i])
    groups=[]
    while ordered:
        first=ordered[0];group=[i for i in ordered if actions[first]-actions[i]<=tie_tolerance]
        groups.append([MODES[i] for i in group]);ordered=[i for i in ordered if i not in group]
    return {"readout_version":READOUT_VERSION,"terminal_leaders":leaders,
            "terminal_tendency":leaders[0] if len(leaders)==1 else None,
            "ranking_groups":groups,"tie_tolerance":tie_tolerance,
            "best_available_activation":highest,"available_modes":[MODES[i] for i in active],
            "interpretation":"signed model evaluations; ties retained; no choice probabilities"}
