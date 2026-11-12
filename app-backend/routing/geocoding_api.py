"""Address lookup through the backend for Android clients connected by USB."""
import math
import threading
import time
from collections import OrderedDict
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

from flask import jsonify, request
from infrastructure.service_config import positive_number, provider_url
from infrastructure.http_client import read_json_response


class GeocodingUnavailable(Exception):
    pass


class GeocodingClient:
    """Short provider timeout, bounded in-memory cache and outage backoff."""
    def __init__(self):
        self.lock = threading.Lock()
        self.cache = OrderedDict()
        self.unavailable_until = {}

    def search(self, query, language, lat, lon):
        base = provider_url('COMPANION_GEOCODING_BASE_URL', 'https://photon.komoot.io')
        if not base:
            raise GeocodingUnavailable()
        key = (base, query.casefold(), language, lat, lon)
        with self.lock:
            cached = self.cache.get(key)
            if cached and time.monotonic() - cached[0] < 300:
                self.cache.move_to_end(key)
                return cached[1]
            if time.monotonic() < self.unavailable_until.get(base, 0):
                raise GeocodingUnavailable()
        params = urlencode(dict(q=query, lang=language, limit=10, lat=lat, lon=lon))
        upstream = Request(base + '/api?' + params, headers={
            'Accept': 'application/json',
            'User-Agent': 'Companion (+https://github.com/imiq-project/app-frontend)'})
        try:
            with urlopen(upstream, timeout=positive_number('COMPANION_GEOCODING_TIMEOUT_SECONDS', 2)) as response:
                data = read_json_response(response, 1024 * 1024)
            if not isinstance(data, dict) or data.get('type') != 'FeatureCollection' or not isinstance(data.get('features'), list):
                raise ValueError('Invalid geocoding response')
        except (HTTPError, URLError, TimeoutError, OSError, ValueError) as exc:
            with self.lock:
                self.unavailable_until[base] = time.monotonic() + 30
            raise GeocodingUnavailable() from exc
        with self.lock:
            self.unavailable_until.pop(base, None)
            self.cache[key] = (time.monotonic(), data)
            self.cache.move_to_end(key)
            while len(self.cache) > 64:
                self.cache.popitem(last=False)
        return data


def register_geocoding(app):
    client = GeocodingClient()
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
        try:
            response = jsonify(client.search(query, language, lat, lon))
        except GeocodingUnavailable:
            response = jsonify(error='geocoding_unavailable')
            response.status_code = 503
            response.headers['Retry-After'] = '30'
        response.headers['Cache-Control'] = 'no-store'
        return response
