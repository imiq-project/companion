"""Spatial weather fallback; original Orion contracts and archives stay unchanged."""
from collections import OrderedDict
from dataclasses import replace
from datetime import datetime, timezone
import math
import threading
import time
from urllib.parse import urlencode
from urllib.request import urlopen
from urllib.error import URLError

from context.orion_context import OrionProviderError, _coordinate_from_query
from context.orion_query_list import OrionQueryListProvider
from context.route_context import ContextObservation, ContextStatus
from infrastructure.service_config import positive_number
from infrastructure.http_client import read_json_response

CONTRACT = 'companion-weather-fallback-v1'
FIELDS = {
    'temperature': ('temperature_2m', '°C', -90, 65),
    'humidity': ('relative_humidity_2m', '%', 0, 100),
    'windSpeed': ('wind_speed_10m', 'm/s', 0, 150),
}
_cache = OrderedDict()
_lock = threading.Lock()


def fetch_current(lat, lon):
    # Rounded query coordinates are retained explicitly; cache is bounded, 5 min.
    key = (round(lat, 2), round(lon, 2))
    with _lock:
        entry = _cache.get(key)
        if entry and time.monotonic() - entry[0] < 300:
            return entry[1]
    params = dict(latitude=key[0], longitude=key[1],
                  current=','.join(v[0] for v in FIELDS.values()),
                  wind_speed_unit='ms', timezone='UTC', timeformat='unixtime')
    with urlopen('https://api.open-meteo.com/v1/forecast?' + urlencode(params), timeout=positive_number('COMPANION_WEATHER_TIMEOUT_SECONDS',6)) as response:
        payload = read_json_response(response, 262144)
    if not isinstance(payload, dict):
        raise ValueError('Invalid weather response')
    current = payload.get('current', {})
    units = payload.get('current_units', {})
    if not isinstance(current, dict) or not isinstance(units, dict):
        raise ValueError('Invalid current weather fields')
    stamp = current.get('time')
    if type(stamp) not in (int, float) or not math.isfinite(stamp):
        raise ValueError('Missing weather time')
    if not 0 <= time.time() - stamp <= 1800:
        raise ValueError('Weather response outside current time window')
    output = []
    for variable, (field, unit, lower, upper) in FIELDS.items():
        value = current.get(field)
        if (type(value) not in (int, float) or not math.isfinite(value)
                or not lower <= value <= upper or units.get(field) != unit):
            continue
        output.append(ContextObservation(
            source='Open-Meteo', variable=variable, raw_value=value, numeric_value=value,
            unit=unit, timestamp=datetime.fromtimestamp(stamp, timezone.utc).isoformat(),
            status=ContextStatus.DERIVED, source_entity_id=f'Open-Meteo:{key[0]}:{key[1]}',
            spatial_metadata={'query_latitude': key[0], 'query_longitude': key[1],
                              'model_latitude': payload.get('latitude'),
                              'model_longitude': payload.get('longitude')},
            source_metadata={'weather_fallback_contract': CONTRACT,
                             'normalization_eligible': True, 'learning_eligible': False,
                             'measurement_semantics': 'WEATHER_MODEL_CURRENT_ESTIMATE',
                             'source_attribute': field,
                             'reference_height_meters': 10 if variable == 'windSpeed' else 2,
                             'attribution': 'Weather data by Open-Meteo.com (CC BY 4.0)',
                             'provider_response_received_at': datetime.now(timezone.utc).isoformat()}))
    with _lock:
        _cache[key] = (time.monotonic(), tuple(output))
        _cache.move_to_end(key)
        while len(_cache) > 128:
            _cache.popitem(last=False)
    return tuple(output)


class WeatherProvider(OrionQueryListProvider):
    def __init__(self, *, weather_fetch=fetch_current, **kwargs):
        super().__init__(**kwargs)
        self.weather_fetch = weather_fetch
        self._fallbacks = {}

    def get_context(self, family, *, nearest_only=False, **query):
        target = _coordinate_from_query(query.get('coords'))
        if family != 'weather' or target is None or query.get('geometry') != 'point':
            return super().get_context(family, nearest_only=nearest_only, **query)
        try:
            local = super().get_context(family, nearest_only=nearest_only, **query)
        except OrionProviderError:
            local = ()
        if any(o.numeric_value is not None and (o.source_metadata or {}).get('normalization_eligible') is not False for o in local):
            return local
        if target not in self._fallbacks:
            try:
                remote = self.weather_fetch(*target)
            except (URLError, OSError, ValueError, TypeError, KeyError):
                remote = ()
            # Reuse this request's Orion list; no invented unit/window conversion.
            try:
                distant = super().get_context(family, nearest_only=False,
                    **{**query, 'georel': 'near;maxDistance:21000000'})
            except OrionProviderError:
                distant = ()
            supplied = {o.variable for o in remote}
            retained = []
            for o in distant:
                if (o.variable in supplied or o.numeric_value is None or not o.unit
                        or (o.source_metadata or {}).get('normalization_eligible') is False):
                    continue
                retained.append(replace(o, status=ContextStatus.DERIVED,
                    source_metadata={**dict(o.source_metadata or {}),
                        'weather_fallback_contract': CONTRACT, 'learning_eligible': False,
                        'original_status': o.status.value, 'outside_local_coverage': True}))
                supplied.add(o.variable)
            self._fallbacks[target] = tuple(remote) + tuple(retained)
        return self._fallbacks[target]
