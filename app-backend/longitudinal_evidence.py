"""Compatibility imports and CLI for longitudinal evidence validation."""
from journeys.evidence import (
    PASSPORT, SCHEMA, VALIDATOR, longitudinal_index, validate_backup, validate_signed_evidence, main,
)
from journeys.api import register_longitudinal

if __name__ == '__main__':
    main()
