"""Five-goal declared-belief runtime. No population imputation or removed axes.

The import name is retained for API callers; the artifact/model versions explicitly
identify the new complete 4-by-5 elicitation policy. Historical Ridge artifacts
remain archived and are not used to initialize current passports.
"""
from hashlib import sha256
import json
import math
from pathlib import Path
from typing import Mapping
from model.input_mapping_v4_3 import MODES, NEEDS

ARTIFACT_SCHEMA_VERSION = "cognitive_passport_explicit_artifact_v1"
RUNTIME_VERSION = "cognitive_passport_explicit_runtime_v1"
MODEL_VERSION = "five-goals-explicit-v1"
from resources import ARTIFACTS_DIR

DEFAULT_ARTIFACT_PATH = ARTIFACTS_DIR / "five_goals_explicit_v1.json"

class AdaptivePassportError(ValueError):
    pass

def _finite_number(value, *, path, minimum, maximum):
    if isinstance(value, bool):
        raise AdaptivePassportError(f"{path} must be numeric")
    try: number = float(value)
    except (TypeError, ValueError) as exc:
        raise AdaptivePassportError(f"{path} must be numeric") from exc
    if not math.isfinite(number) or not minimum <= number <= maximum:
        raise AdaptivePassportError(f"{path} outside supported range")
    return number

def raw_belief_rating_to_normalized(raw_rating):
    value = _finite_number(raw_rating, path="belief rating", minimum=1, maximum=7)
    if not value.is_integer():
        raise AdaptivePassportError("belief rating must be an integer")
    return (value - 4) / 3

class AdaptivePassportV1:
    def __init__(self, artifact_path=DEFAULT_ARTIFACT_PATH):
        self.artifact_path = Path(artifact_path).resolve()
        data = self.artifact_path.read_bytes()
        self.artifact_sha256 = sha256(data).hexdigest()
        self.artifact = json.loads(data)
        expected = {"modes": list(MODES), "needs": list(NEEDS), "belief_matrix_shape": [len(MODES), len(NEEDS)]}
        if (self.artifact.get("schema_version") != ARTIFACT_SCHEMA_VERSION
            or self.artifact.get("model_version") != MODEL_VERSION
            or self.artifact.get("canonical_contract") != expected):
            raise AdaptivePassportError("Unsupported goal-set/declared-belief artifact")
        self.all_cells = tuple(f"{mode}__{need}" for mode in MODES for need in NEEDS)
        self.k = len(self.all_cells)
        self.global_lambda = 0.0
        self.cell_metadata = {cell: {"hotco_mode": cell.split("__")[0], "model_need": cell.split("__")[1], "utility": 0.0} for cell in self.all_cells}
        self.utility_by_cell = {cell: 0.0 for cell in self.all_cells}

    def select_questions(self, *, candidate_cells=None, k=None):
        candidates = self.all_cells if candidate_cells is None else tuple(dict.fromkeys(candidate_cells))
        if any(cell not in self.all_cells for cell in candidates):
            raise AdaptivePassportError("Unknown belief cell")
        count = self.k if k is None else int(k)
        if count < 1:
            raise AdaptivePassportError("Question count must be positive")
        return [{"position": i+1, "cell_id": cell, **self.cell_metadata[cell],
                 "selection_method": "complete_declared_matrix_canonical_order",
                 "response_scale": {"minimum": 1, "maximum": 7, "integer_only": True,
                                    "normalization": "(raw_rating - 4) / 3"}}
                for i, cell in enumerate(candidates[:count])]

    def personalize(self, *, needs, valences, observed_answers, answers_are_raw_1_to_7=False, require_complete_k=True):
        if not isinstance(needs, Mapping) or set(needs) != set(NEEDS):
            raise AdaptivePassportError("Exactly five declared needs are required")
        if not isinstance(valences, Mapping) or set(valences) != set(MODES):
            raise AdaptivePassportError("Exactly four declared valences are required")
        for key, value in needs.items():
            _finite_number(value, path=key, minimum=0, maximum=1)
        for key, value in valences.items():
            _finite_number(value, path=key, minimum=-1, maximum=1)
        if not isinstance(observed_answers, Mapping) or set(observed_answers) != set(self.all_cells):
            raise AdaptivePassportError("All twenty declared belief answers are required; no imputation is performed")
        observations = {cell: raw_belief_rating_to_normalized(value) if answers_are_raw_1_to_7
                        else _finite_number(value, path=cell, minimum=-1, maximum=1)
                        for cell, value in observed_answers.items()}
        return {"schema_version": RUNTIME_VERSION, "model_version": MODEL_VERSION,
                "artifact": {"path": self.artifact_path.name, "sha256": self.artifact_sha256},
                "beliefs": {mode: {need: observations[f"{mode}__{need}"] for need in NEEDS} for mode in MODES},
                "belief_provenance": {mode: {need: {"source": "observed", "estimator": None, "direct_user_response": True} for need in NEEDS} for mode in MODES},
                "personalization": {"observed_answer_count": self.k, "expected_answer_count": self.k,
                    "global_shrinkage_lambda": 0.0, "shrinkage_weight": 1.0,
                    "mean_residual": 0.0, "personal_offset": 0.0,
                    "selected_answers_override_estimates": False, "imputation_performed": False},
                "policy": {"automatic_behavioral_profile_updates": False,
                           "route_choice_is_psychological_measurement": False,
                           "context_updates_stable_profile": False}}

    def predict_all(self, **kwargs):
        raise AdaptivePassportError("Population belief estimation is not part of the five-goal declared model")

    def predict_cell(self, **kwargs):
        raise AdaptivePassportError("Population belief estimation is not part of the five-goal declared model")
