"""Explicit evidence exclusions; missing GPS is not evidence of non-travel."""
from infrastructure.longitudinal_names import contract_view as _namespace_view
VERSION = "longitudinal-external-evaluation-exclusions-v1"
STORAGE_KEY = "longitudinal.evidence-exclusions.v1"
LEGACY_STORAGE_KEY = "d-day.evidence-exclusions.v1"
CLASSES = {"real_experience", "test", "synthetic", "stationary_test"}


def backup_exclusions(backup, exclusions=None):
    if exclusions is None:
        import json
        storage = backup.get("storage", {})
        current, previous = storage.get(STORAGE_KEY), storage.get(LEGACY_STORAGE_KEY)
        if current is not None and previous is not None and current != previous:
            raise ValueError("Conflicting longitudinal exclusion registries")
        value = current if current is not None else previous
        exclusions = json.loads(value) if isinstance(value, str) else value
    validate_exclusions(exclusions)
    return exclusions


def validate_exclusions(exclusions):
    if exclusions is None:
        return []
    if not isinstance(exclusions, dict) or _namespace_view(exclusions.get("schema_version")) != _namespace_view(VERSION):
        raise ValueError("Unknown evidence exclusion schema")
    rows = exclusions.get("exclusions")
    if not isinstance(rows, list):
        raise ValueError("Exclusions must be a list")
    for row in rows:
        if (not isinstance(row, dict) or not any(isinstance(row.get(k), str) and row[k] for k in ("journey_id", "experience_event_id"))
                or not isinstance(row.get("reason"), str) or not row["reason"]
                or not isinstance(row.get("targets"), list) or not row["targets"]
                or any(t not in ("comfort", "affect") for t in row["targets"])):
            raise ValueError("Invalid evidence exclusion")
    return rows


def exclusion_reason(record, journey, *, target, exclusions=None, evidence_scope='observed'):
    if evidence_scope not in ('observed', 'synthetic_lab_only'):raise ValueError('Unknown evidence scope')
    rows = validate_exclusions(exclusions)
    experience = journey.get("experience") or {}
    if not experience:
        return "experience_not_confirmed"
    classification = journey.get("evidence_classification", record.get("evidence_classification"))
    if classification is not None:
        if classification not in CLASSES:
            raise ValueError("Unknown evidence classification")
        expected = 'synthetic' if evidence_scope == 'synthetic_lab_only' else 'real_experience'
        if classification != expected:
            return "explicit_" + classification
    elif evidence_scope == 'synthetic_lab_only':
        return 'synthetic_lab_requires_explicit_classification'
    if journey.get("status") == "cancelled":
        return "trip_cancelled"
    if experience.get("completion") == "not_taken":
        return "trip_not_taken"
    for row in rows:
        # When both IDs are supplied both must match; no broad OR match.
        if target in row["targets"] and all(row[k] == value for k, value in (
            ("journey_id", journey.get("journey_id")), ("experience_event_id", experience.get("event_id"))) if k in row):
            return row["reason"]
    return None
