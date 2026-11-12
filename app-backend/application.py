"""Application factory. API contracts remain registered through their modules."""
from importlib import import_module
import logging
from flask import Flask
from infrastructure.service_config import settings
from resources import BACKEND_DIR

REGISTRARS = (
    ('explanations.choice_explanation_api','register_choice_explanation'),('explanations.api','register_companion_reflection'),
    ('routing.geocoding_api','register_geocoding'),('routing.transit_stop_api','register_transit_stops'),
    ('routing.transit_departures','register_departures'),('context.weather_api','register_weather'),
    ('comfort_pilot.context_capture','register'),('routing.map_tiles_api','register_map_tiles'),
    ('journeys.api','register_journeys'),('journeys.api','register_longitudinal'),
    ('comfort_pilot.api','register'),('comfort_pilot.affect','register_affect'),
    ('comfort_pilot.prospective','register'),('comfort_pilot.learned_passport','register'),
    ('comfort_pilot.backend_experiment_api','register'),('passport.api','register_goal_priorities'),
)


def create_app(config=None):
    runtime = settings()
    from api_routes import api, engine, model_lock
    from infrastructure.response_transport import register_json_compression
    from infrastructure.service_runtime import BoundedRequest, body_too_large, register_runtime
    from passport.api import register_daily_passport
    app = Flask(__name__, root_path=str(BACKEND_DIR))
    app.request_class = BoundedRequest
    # Local development access logs otherwise include GPS/address query strings.
    # Production Gunicorn also leaves raw access logging disabled.
    logging.getLogger('werkzeug').setLevel(logging.ERROR)
    app.config.update(MAX_CONTENT_LENGTH=runtime.max_body_bytes)
    app.config.update(config or {})
    app.extensions['companion_settings'] = runtime
    register_runtime(app, runtime)
    app.register_blueprint(api)
    for module, name in REGISTRARS:
        getattr(import_module(module),name)(app)
    register_daily_passport(app, engine, model_lock)
    from infrastructure.longitudinal_names import register_legacy_urls
    register_legacy_urls(app)
    register_json_compression(app)

    app.register_error_handler(413, lambda error: body_too_large())
    return app
