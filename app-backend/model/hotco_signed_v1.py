"""Current signed HOTCO–Grossberg field (Paper_Agents manuscript/math.tex).

The four-mode network is evaluated in full. Availability filters the readout,
not the field; unavailable modes are never recommended. No state clipping,
learning, or state carryover occurs. Polished roots are diagnostics only.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

import numpy as np
from scipy.integrate import solve_ivp
from scipy.optimize import root

from model.input_mapping_v4_3 import MODES, NEEDS, ParticipantInput

MODEL_VERSION = "hotco_signed_grossberg_5goals_v1"
MODEL_NAME = "Signed HOTCO–Grossberg"
LEGACY_MODEL_VERSION = "hotco_ct_v4.3-online.2"
MODAL_PATTERN = np.array([[0,-1,1,0],[-1,0,1,0],[1,1,0,1],[0,0,1,0]], dtype=float)


@dataclass(frozen=True)
class SignedHOTCOParameters:
    tau: float = .8
    decay: float = .05
    belief_strength: float = 1.
    modal_coupling: float = .25
    affective_coupling: float = .5
    dt: float = .1  # Output sampling only; independent of the adaptive solver.
    horizon: float = 400.
    max_extension: float = 1600.
    rtol: float = 1e-9
    atol: float = 1e-11
    max_step: float = .1
    residual_tolerance: float = 1e-8
    extension_residual_tolerance: float = 1e-10
    root_tolerance: float = 1e-10
    root_distance_tolerance: float = 1e-3
    settling_dwell: float = 1.
    practical_margin_threshold: float = .01

    def __post_init__(self):
        values = vars(self)
        if not all(np.isfinite(v) for v in values.values()):
            raise ValueError("all model and numerical parameters must be finite")
        positive = ("tau", "decay", "dt", "horizon", "rtol", "atol", "max_step",
                    "residual_tolerance", "extension_residual_tolerance", "root_tolerance",
                    "root_distance_tolerance", "settling_dwell")
        if any(values[k] <= 0 for k in positive) or any(values[k] < 0 for k in (
                "belief_strength", "modal_coupling", "affective_coupling", "max_extension",
                "practical_margin_threshold")):
            raise ValueError("invalid signed model or numerical parameter")

    # Read-only source compatibility for input/explanation consumers. These
    # cannot introduce asymmetric gains or a second architectural family.
    @property
    def cognitive_gain(self): return self.belief_strength
    @property
    def need_to_action_gain(self): return 1.
    @property
    def action_to_need_gain(self): return 1.
    @property
    def valence_to_action_gain(self): return self.affective_coupling
    @property
    def action_to_valence_gain(self): return self.affective_coupling
    @property
    def settling_action_threshold(self): return self.residual_tolerance
    @property
    def settling_state_threshold(self): return self.residual_tolerance


@dataclass
class SimulationResult:
    trajectory: np.ndarray
    topology: np.ndarray
    availability: np.ndarray
    projection_corrections: dict
    context_metadata: dict[str, Any] = field(default_factory=dict)
    times: np.ndarray = field(default_factory=lambda: np.empty(0))
    numerical: list[dict] = field(default_factory=list)

    @property
    def final_state(self): return self.trajectory[:, -1, :]


class SignedHOTCO:
    n_needs = len(NEEDS)
    n_modes = len(MODES)
    n_nodes = n_needs + 2*n_modes

    def __init__(self, *, parameters=None):
        self.parameters = parameters or SignedHOTCOParameters()
        self.n_steps = int(np.ceil(self.parameters.horizon/self.parameters.dt))

    def arrays_from_input(self, participant: ParticipantInput):
        state = np.array([[*participant.needs, *([0.]*self.n_modes), *participant.valences]], dtype=float)
        return state, np.asarray([participant.beliefs], dtype=float), np.asarray([participant.availability], dtype=float)

    def build_topology(self, beliefs):
        beliefs = np.asarray(beliefs, dtype=float)
        if beliefs.ndim != 3 or beliefs.shape[1:] != (self.n_modes, self.n_needs):
            raise ValueError(f"beliefs must have shape [batch,{self.n_modes},{self.n_needs}]")
        if not np.isfinite(beliefs).all() or np.any(np.abs(beliefs)>1):
            raise ValueError("beliefs must be finite and lie in [-1,1]")
        p = self.parameters; k = self.n_needs; v = k+self.n_modes
        w = np.zeros((len(beliefs), self.n_nodes, self.n_nodes))
        w[:,k:v,:k] = p.belief_strength*beliefs
        w[:,:k,k:v] = p.belief_strength*beliefs.transpose(0,2,1)
        w[:,k:v,k:v] = p.modal_coupling*MODAL_PATTERN
        w[:,k:v,v:] = w[:,v:,k:v] = p.affective_coupling*np.eye(self.n_modes)
        return w

    def vector_field(self, state, topology, availability=None):
        net = np.einsum("bij,bj->bi", topology, state)
        return (net-self.parameters.decay*state-state*np.abs(net))/self.parameters.tau

    def jacobian(self, state, w):
        p = self.parameters; net = w@state
        j = (1-state*np.sign(net))[:,None]*w
        j[np.diag_indices_from(j)] -= p.decay+np.abs(net)
        return j/p.tau

    def potential(self, state, w):
        if np.any(np.abs(state)>=1): return float("inf")
        return float(-.5*state@(w@state)+self.parameters.decay*np.sum(-np.abs(state)-np.log1p(-np.abs(state))))

    def _audit(self, endpoint, w):
        p = self.parameters
        f = lambda s: (w@s-p.decay*s-s*np.abs(w@s))/p.tau
        residual = float(np.max(np.abs(f(endpoint))))
        polished = root(f, endpoint, jac=lambda s:self.jacobian(s,w), tol=1e-12)
        r = polished.x
        finite = bool(np.isfinite(r).all())
        bounded = finite and bool(np.max(np.abs(r))<=1+1e-12)
        rr = float(np.max(np.abs(f(r)))) if finite else None
        distance = float(np.max(np.abs(r-endpoint))) if finite else None
        net = w@r
        kink = not finite or bool(np.any((np.abs(net)<=1e-10)&(np.abs(r)>1e-10)&np.any(np.abs(w)>1e-10,axis=1)))
        eigenvalue = float(np.max(np.linalg.eigvals(self.jacobian(r,w)).real)) if finite else None
        verified = bounded and rr<p.root_tolerance and distance<=p.root_distance_tolerance and not kink and eigenvalue<0 and residual<=p.residual_tolerance*(1+1e-8)
        return {"integrated_residual":residual, "root_solver_success":bool(polished.success),
                "root_residual":rr, "endpoint_root_distance":distance, "root_bounded":bounded,
                "nonsmooth_net_coordinate":kink, "max_real_eigenvalue":eigenvalue,
                "verified_stable_equilibrium":bool(verified), "polished_root":r.tolist() if finite else None,
                "root_replaces_integrated_endpoint":False}

    @staticmethod
    def _grid(start, end, dt):
        grid = start+np.arange(1,int(np.floor((end-start)/dt))+1)*dt
        grid = grid[grid<end-1e-12]
        return np.r_[grid,end]

    def simulate(self, participant):
        initial, beliefs, availability = self.arrays_from_input(participant)
        initial = np.asarray(initial, dtype=float); availability = np.asarray(availability, dtype=float)
        if initial.ndim!=2 or initial.shape[1]!=self.n_nodes or not np.isfinite(initial).all() or np.any(np.abs(initial)>1):
            raise ValueError("initial states must be finite and lie in [-1,1]")
        if availability.shape!=(len(initial),self.n_modes) or not np.isin(availability,[0,1]).all() or np.any(availability.sum(axis=1)<1):
            raise ValueError("availability must be binary with at least one available mode per profile")
        w = self.build_topology(beliefs)
        if len(w)!=len(initial): raise ValueError("belief and initial-state batch lengths differ")
        p = self.parameters
        times = np.r_[0.,self._grid(0,p.horizon,p.dt)]
        def integrate(s, wi, start, end, grid):
            def f(t,x):
                net=wi@x
                return (net-p.decay*x-x*np.abs(net))/p.tau
            sol=solve_ivp(f,(start,end),s,method="DOP853",rtol=p.rtol,atol=p.atol,max_step=p.max_step,t_eval=grid)
            if not sol.success or len(sol.t)!=len(grid) or not np.isfinite(sol.y).all():
                raise RuntimeError(f"DOP853 failed: {sol.message}")
            return sol.y.T, int(sol.nfev)
        trajectories=[]; audits=[]; evaluations=[]
        for s,wi in zip(initial,w):
            states,nfev=integrate(s,wi,0,p.horizon,times)
            trajectories.append(states); evaluations.append(nfev); audits.append(self._audit(states[-1],wi))
        primary_audits=[dict(a) for a in audits]
        extended=bool(p.max_extension>0 and any(not a["verified_stable_equilibrium"] for a in audits))
        if extended:
            # A common batch time axis prevents padding frozen states or mixing
            # endpoints from different horizons. Single-profile service calls
            # extend only their own unconfirmed episode.
            end=p.horizon+p.max_extension
            extra=self._grid(p.horizon,end,p.dt)
            for i,wi in enumerate(w):
                states,nfev=integrate(trajectories[i][-1],wi,p.horizon,end,extra)
                trajectories[i]=np.vstack((trajectories[i],states)); evaluations[i]+=nfev
                audits[i]=self._audit(states[-1],wi)
            times=np.r_[times,extra]
        trajectory=np.stack(trajectories)
        violation=float(max(0.,np.max(np.abs(trajectory))-1))
        if violation>1e-10: raise RuntimeError("DOP853 exceeded the signed state bounds; no clipping applied")
        for i,audit in enumerate(audits):
            audit.update({"solver":"DOP853", "rtol":p.rtol,"atol":p.atol,"max_step":p.max_step,
                "primary_horizon":p.horizon,"actual_horizon":float(times[-1]),"extended":extended,
                "primary_audit":primary_audits[i],"nfev":evaluations[i],"bound_violation":violation,
                "extension_residual_target_met": bool(audit["integrated_residual"]<=p.extension_residual_tolerance) if extended else None,
                "state_clipping_applied":False})
        return SimulationResult(trajectory,w,availability,
            {name:{"count":0,"max_abs":0.} for name in ("needs","actions","valences")},
            {"context_applied":False,"applied":False,"context_model":"observations_only",
             "context_policy":"observations_only","context_model_status":"NOT_APPLIED",
             "learning_applied":False,"state_carryover_applied":False,
             "availability_policy":"readout_only_full_four_mode_field","warnings":[]},times,audits)


# Preserve import names while all operational callers migrate to this version.
HOTCOCTParameters = SignedHOTCOParameters
HOTCOCTv43 = SignedHOTCO
