"""Stable resource locations, independent of caller modules and working directory."""
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = BACKEND_DIR.parent
CONTRACTS_DIR = PROJECT_ROOT / 'contracts'
ARTIFACTS_DIR = BACKEND_DIR / 'artifacts'
CHOICE_WORDING_FILE = BACKEND_DIR / 'choice_wording_v1.json'
BACKEND_ENV_FILE = PROJECT_ROOT / 'app-backend/.env'
NARRATION_ENV_FILE = PROJECT_ROOT / 'app-backend/.env.local'
