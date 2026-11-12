"""Passport HTTP handlers; rules and execution live in their own modules."""
from __future__ import annotations
from typing import Any
from flask import jsonify, request
from model.input_mapping_v4_3 import InputValidationError
from passport.adaptive_passport_v1 import AdaptivePassportError
from passport.adaptive_passport_onboarding import start_adaptive_passport, complete_adaptive_passport
from passport.adaptive_profile_update import AdaptiveProfileUpdateError
from passport.adaptive_hotco_bootstrap import AdaptiveHotcoBootstrapError
from infrastructure.api_blueprint import api, logger
from services.passport_service import run_companion, run_profile_update, run_adaptive_hotco_bootstrap, run_adaptive_profile_update


@api.post("/api/companion")
def companion_route() -> Any:
    try:
        payload = request.get_json(force=True)
        return jsonify(run_companion(payload))
    except InputValidationError as exc:
        return (
            jsonify(
                {
                    "error": "invalid_questionnaire_input",
                    "message": "COMPANION requires explicit current-user responses for every model input.",
                    "issues": exc.issues,
                    "imputation_performed": False,
                }
            ),
            422,
        )
    except Exception as exc:  # pragma: no cover - operational safety net
        logger.error("HOTCO-CT inference failed (%s)", type(exc).__name__)
        return (
            jsonify(
                {
                    "error": "companion_inference_failed",
                    "detail": "Internal operation failed",
                    "imputation_performed": False,
                }
            ),
            500,
        )


@api.post("/api/companion/adaptive-passport/start")
def adaptive_passport_start_route() -> Any:
    """Start five-goal, twenty-question declared Passport onboarding."""

    try:
        payload = request.get_json(force=True)
        return jsonify(
            start_adaptive_passport(
                payload
            )
        )

    except AdaptivePassportError as exc:
        return (
            jsonify(
                {
                    "error":
                        "invalid_adaptive_passport_start",

                    "message":
                        str(exc),

                    "hotco_run":
                        False,

                    "automatic_profile_update":
                        False,
                }
            ),
            422,
        )

    except Exception as exc:  # pragma: no cover - operational safety net
        logger.error("Adaptive Passport start failed (%s)", type(exc).__name__)

        return (
            jsonify(
                {
                    "error":
                        "adaptive_passport_start_failed",

                    "detail":
                        "Internal operation failed",

                    "hotco_run":
                        False,

                    "automatic_profile_update":
                        False,
                }
            ),
            500,
        )


@api.post("/api/companion/adaptive-passport/complete")
def adaptive_passport_complete_route() -> Any:
    """Complete onboarding from twenty declared mode-goal beliefs."""

    try:
        payload = request.get_json(force=True)
        return jsonify(
            complete_adaptive_passport(
                payload
            )
        )

    except AdaptivePassportError as exc:
        return (
            jsonify(
                {
                    "error":
                        "invalid_adaptive_passport_completion",

                    "message":
                        str(exc),

                    "hotco_run":
                        False,

                    "automatic_profile_update":
                        False,
                }
            ),
            422,
        )

    except Exception as exc:  # pragma: no cover - operational safety net
        logger.error("Adaptive Passport completion failed (%s)", type(exc).__name__)

        return (
            jsonify(
                {
                    "error":
                        "adaptive_passport_completion_failed",

                    "detail":
                        "Internal operation failed",

                    "hotco_run":
                        False,

                    "automatic_profile_update":
                        False,
                }
            ),
            500,
        )


@api.post("/api/companion/adaptive-passport/bootstrap")
def adaptive_passport_bootstrap_route() -> Any:
    """Create Adaptive Cognitive Passport revision 1 and run HOTCO-CT."""

    try:
        payload = request.get_json(
            force=True
        )

        return jsonify(
            run_adaptive_hotco_bootstrap(
                payload
            )
        )

    except AdaptiveHotcoBootstrapError as exc:
        return (
            jsonify(
                {
                    "error":
                        "invalid_adaptive_hotco_bootstrap",

                    "message":
                        str(exc),

                    "automatic_profile_update":
                        False,

                    "availability_inferred":
                        False,

                    "hotco_parameters_changed":
                        False,
                }
            ),
            422,
        )

    except Exception as exc:  # pragma: no cover
        logger.error("Adaptive HOTCO bootstrap failed (%s)", type(exc).__name__)

        return (
            jsonify(
                {
                    "error":
                        "adaptive_hotco_bootstrap_failed",

                    "detail":
                        "Internal operation failed",

                    "automatic_profile_update":
                        False,

                    "availability_inferred":
                        False,

                    "hotco_parameters_changed":
                        False,
                }
            ),
            500,
        )


@api.post("/api/companion/adaptive-passport/profile-update")
def adaptive_passport_profile_update_route() -> Any:
    """Apply one explicitly confirmed longitudinal Adaptive Passport update."""

    try:
        payload = request.get_json(
            force=True
        )

        return jsonify(
            run_adaptive_profile_update(
                payload
            )
        )

    except AdaptiveProfileUpdateError as exc:
        return (
            jsonify(
                {
                    "error":
                        "invalid_adaptive_profile_update",

                    "message":
                        str(exc),

                    "automatic_update_performed":
                        False,

                    "explicit_confirmation_required":
                        True,

                    "hotco_parameters_changed":
                        False,
                }
            ),
            422,
        )

    except Exception as exc:  # pragma: no cover
        logger.error("Adaptive Passport profile update failed (%s)", type(exc).__name__)

        return (
            jsonify(
                {
                    "error":
                        "adaptive_profile_update_failed",

                    "detail":
                        "Internal operation failed",

                    "automatic_update_performed":
                        False,

                    "hotco_parameters_changed":
                        False,
                }
            ),
            500,
        )


@api.post("/api/companion/profile-update")
def profile_update_route() -> Any:
    try:
        payload = request.get_json(force=True)
        return jsonify(run_profile_update(payload))
    except InputValidationError as exc:
        return (
            jsonify(
                {
                    "error": "invalid_profile_update",
                    "message": (
                        "Profile updates require the active backend-generated "
                        "micro-question and one explicitly confirmed user response."
                    ),
                    "issues": exc.issues,
                    "automatic_update_performed": False,
                    "imputation_performed": False,
                }
            ),
            422,
        )
    except Exception as exc:  # pragma: no cover - operational safety net
        logger.error("HOTCO-CT profile update failed (%s)", type(exc).__name__)
        return (
            jsonify(
                {
                    "error": "companion_profile_update_failed",
                    "detail": "Internal operation failed",
                    "automatic_update_performed": False,
                    "imputation_performed": False,
                }
            ),
            500,
        )


from passport.daily_passport import confirm_daily_passport
from passport.goal_priorities import prepare_priorities

def register_daily_passport(app, engine, lock):
    @app.post("/api/companion/daily-passport/confirm")
    def confirm_daily():
        try:
            with lock:
                output = confirm_daily_passport(request.get_json(force=True), engine)
            return jsonify(output)
        except (ValueError, KeyError, TypeError) as exc:
            return jsonify(error="invalid_daily_passport", message=str(exc)), 422

def register_goal_priorities(app):
    @app.post('/api/companion/goal-personalization/prepare')
    def prepare():
        try:return jsonify(prepare_priorities(request.get_json(force=True)))
        except (ValueError,KeyError,TypeError,AttributeError) as exc:
            return jsonify(error='invalid_goal_priorities',message=str(exc)),422
