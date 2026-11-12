"""Address lookup through the backend for Android clients connected by USB."""
import math
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

from flask import jsonify, request
from infrastructure.service_config import positive_number
from infrastructure.http_client import read_json_response


def register_geocoding(app):
    @app.get('/api/companion/geocoding')
    def geocoding():
        query = request.args.get('q', '').strip()
        language = request.args.get('lang', 'en')
        try:
            lat = float(request.args.get('lat', '52.13'))
            lon = float(request.args.get('lon', '11.62'))
            if not 3 <= len(query) <= 240 or language not in ('en', 'de'):
                raise ValueError()
            if not math.isfinite(lat) or not math.isfinite(lon) or abs(lat) > 90 or abs(lon) > 180:
                raise ValueError()
        except ValueError:
            return jsonify(error='invalid_geocoding_query'), 400
        params = urlencode(dict(q=query, lang=language, limit=10, lat=lat, lon=lon))
        upstream = Request('https://photon.komoot.io/api/?' + params,
                           headers={'Accept': 'application/json', 'User-Agent': 'Companion/0.2'})
        try:
            with urlopen(upstream, timeout=positive_number('COMPANION_GEOCODING_TIMEOUT_SECONDS',12)) as response:
                data = read_json_response(response, 1024 * 1024)
            if not isinstance(data, dict) or data.get('type') != 'FeatureCollection' or not isinstance(data.get('features'), list):
                return jsonify(error='invalid_geocoding_response'), 502
            return jsonify(data)
        except (HTTPError, URLError, TimeoutError, OSError, ValueError):
            return jsonify(error='geocoding_unavailable'), 503
