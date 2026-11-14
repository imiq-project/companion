"""Request capacity, privacy-preserving timings and keyed cache coordination."""
from contextlib import contextmanager
from functools import wraps
import hmac
import json
import logging
import threading
import time
import uuid
from flask import Request, current_app, g, jsonify, request
from .longitudinal_names import canonical_api_path

LOG = logging.getLogger('companion.requests')
BODY_LIMITS = {
    '/api/companion/data/hotco-simulations': (32 * 1024 * 1024, 'hotco_too_large', None),
    '/api/companion/data/search-results': (32 * 1024 * 1024, 'results_too_large', None),
    '/api/companion/data/search-metadata': (512 * 1024, 'search_too_large', None),
    '/api/companion/data/priority-observations': (2 * 1024 * 1024, 'priority_too_large', None),
    '/api/companion/auth/panel/login': (20 * 1024, 'auth_body_too_large', None),
    '/api/companion/auth/panel/refresh': (20 * 1024, 'auth_body_too_large', None),
    '/api/companion/data/profile-versions': (4 * 1024 * 1024, 'profile_too_large', None),
    '/api/companion/data/test/profile-versions': (4 * 1024 * 1024, 'profile_too_large', None),
    '/api/companion/journeys/validate': (8 * 1024 * 1024, 'journey_too_large', 'Journey exceeds 8 MiB'),
    '/api/companion/choice-explanation': (8 * 1024 * 1024, 'choice_explanation_too_large', None),
    '/api/companion/companion-reflection': (1024 * 1024, 'companion_evidence_too_large', None),
    '/api/companion/longitudinal/context/sample': (1024, 'context_request_too_large', None),
    '/api/companion/longitudinal/comfort-pilot/analyze': (4 * 1024 * 1024, 'pilot_request_too_large', None),
    '/api/companion/longitudinal/affect-pilot/analyze': (4 * 1024 * 1024, 'pilot_request_too_large', None),
    '/api/companion/longitudinal/learned-passport/update': (32 * 1024 * 1024, 'learned_request_too_large', None),
    '/api/companion/longitudinal/learned-passport/predict': (32 * 1024 * 1024, 'learned_request_too_large', None),
    '/api/companion/longitudinal/validate': (64 * 1024 * 1024, 'longitudinal_too_large', 'Use offline validation for exports over 64 MiB'),
}


class BoundedRequest(Request):
    @property
    def max_content_length(self):
        global_limit = super().max_content_length
        local = BODY_LIMITS.get(canonical_api_path(self.path))
        limit = global_limit if local is None else min(global_limit, local[0]) if global_limit is not None else local[0]
        # One sentinel byte detects oversized terminated streams before JSON parsing.
        return limit + 1 if limit is not None and self.content_length is None else limit


def check_body_limit():
    limit = request.max_content_length
    if request.content_length is None and limit is not None:
        if len(request.get_data(cache=True)) >= limit:
            return body_too_large()
    return None


def body_too_large():
    local = BODY_LIMITS.get(canonical_api_path(request.path))
    global_limit = current_app.config.get('MAX_CONTENT_LENGTH')
    if local is not None and (global_limit is None or local[0] <= global_limit):
        payload = {'error': local[1]}
        if local[2] is not None:
            payload['message'] = local[2]
        return jsonify(payload), 413
    return jsonify(error='request_body_too_large'), 413


COMPUTE_PATHS = frozenset(('/api/companion/data/hotco-simulations', '/api/companion/data/search-results', '/api/companion/data/search-metadata', '/api/companion/data/priority-observations', '/api/companion/data/profile-versions', '/api/companion/data/test/profile-versions', '/api/companion', '/api/companion/routed-contextual-deliberation',
    '/api/companion/contextual-deliberation', '/api/companion/adaptive-passport/bootstrap',
    '/api/companion/adaptive-passport/profile-update', '/api/companion/profile-update',
    '/api/companion/daily-passport/confirm', '/api/companion/longitudinal/prospective/forecast',
    '/api/companion/longitudinal/comfort-pilot/analyze', '/api/companion/longitudinal/affect-pilot/analyze',
    '/api/companion/longitudinal/learned-passport/update','/api/companion/longitudinal/learned-passport/predict'))


class KeyedLocks:
    def __init__(self):
        self.guard = threading.Lock()
        self.entries = {}

    @contextmanager
    def hold(self, key):
        with self.guard:
            entry = self.entries.setdefault(key, [threading.Lock(), 0])
            entry[1] += 1
        try:
            with entry[0]:yield
        finally:
            with self.guard:
                entry[1] -= 1
                if not entry[1]:del self.entries[key]


@contextmanager
def phase(name):
    from flask import has_request_context
    start = time.perf_counter()
    try:yield
    finally:
        if has_request_context() and hasattr(g, 'companion_timings'):
            g.companion_timings[name] = g.companion_timings.get(name, 0) + time.perf_counter()-start


def timed(name):
    def decorate(function):
        @wraps(function)
        def wrapped(*args,**kwargs):
            with phase(name):return function(*args,**kwargs)
        return wrapped
    return decorate


def register_runtime(app, config):
    capacity = threading.BoundedSemaphore(config.max_inflight)
    counters = {'requests':0,'rejected_capacity':0,'responses_5xx':0,'inflight':0}
    lock = threading.Lock()
    app.extensions['companion_runtime'] = {'counters':counters, 'lock':lock, 'capacity':capacity}

    @app.before_request
    def admit_request():
        g.companion_started = time.perf_counter()
        g.companion_timings = {}
        g.companion_request_id = uuid.uuid4().hex
        with lock:counters['requests'] += 1
        # Panel routes implement upstream authentication themselves. The shared
        # operations token must never be bundled into an Android application.
        panel_route = request.path in ('/api/companion/auth/panel/login', '/api/companion/auth/panel/refresh', '/api/companion/auth/panel/me')
        panel_transfer = app.extensions.get('companion_panel_atlas_local_test', False) and request.path in (
            '/api/companion/data/profile-transfer/status', '/api/companion/data/profile-versions')
        panel_transfer = panel_transfer or (app.extensions.get('companion_priority_archive') is not None and request.path in (
            '/api/companion/data/priority-transfer/status','/api/companion/data/priority-observations'))
        panel_transfer = panel_transfer or (app.extensions.get('companion_search_archive') is not None and request.path in (
            '/api/companion/data/search-transfer/status','/api/companion/data/search-metadata'))
        panel_transfer = panel_transfer or (app.extensions.get('companion_results_archive') is not None and request.path in (
            '/api/companion/data/search-results-transfer/status','/api/companion/data/search-results'))
        panel_transfer = panel_transfer or (app.extensions.get('companion_hotco_archive') is not None and request.path in (
            '/api/companion/data/hotco-transfer/status','/api/companion/data/hotco-simulations'))
        if config.api_token and request.path.startswith('/api/') and not (panel_route or panel_transfer):
            supplied = request.headers.get('Authorization','')
            if not hmac.compare_digest(supplied.encode('utf-8'), ('Bearer '+config.api_token).encode('utf-8')):
                return jsonify(error='authentication_required'),401
        if request.method=='POST' and (canonical_api_path(request.path) in COMPUTE_PATHS or canonical_api_path(request.path).startswith('/api/companion/longitudinal/backend-experiment/')):
            if not capacity.acquire(blocking=False):
                with lock:counters['rejected_capacity'] += 1
                response = jsonify(error='service_busy', retryable=True)
                response.status_code=503;response.headers['Retry-After']='1'
                return response
            g.companion_admitted = True
            with lock:counters['inflight'] += 1

    @app.after_request
    def record_request(response):
        if hasattr(g,'companion_started'):
            elapsed = time.perf_counter()-g.companion_started
            g.companion_timings['total'] = elapsed
            response.headers['X-Request-ID'] = g.companion_request_id
            response.headers['Server-Timing'] = ', '.join(f'{key};dur={value*1000:.3f}' for key,value in g.companion_timings.items())
            if response.status_code>=500:
                with lock:counters['responses_5xx'] += 1
            # Endpoint names only: no URLs, query strings, coordinates, identities or payloads.
            LOG.info('%s',json.dumps({'request_id':g.companion_request_id,'endpoint':request.endpoint,
                'status':response.status_code,'seconds':round(elapsed,6),'response_bytes':response.calculate_content_length(),
                'phases_seconds':g.companion_timings}))
        return response

    @app.teardown_request
    def release_request(error):
        if getattr(g,'companion_admitted',False):
            g.companion_admitted=False
            with lock:counters['inflight'] -= 1
            capacity.release()

    @app.get('/health/live')
    def live():return jsonify(status='ok')

    @app.get('/health/ready')
    def ready():
        with lock:inflight=counters['inflight']
        return jsonify(status='ok', capacity_per_worker=config.max_inflight, inflight=inflight,
                       scope='API readiness only; use CLI provider checks separately')
