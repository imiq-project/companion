"""Minimal PanelManager JWT bridge. No provider key, consent inference or uploads."""
from collections import OrderedDict, deque
from hashlib import sha256
import json
import os
import re
import threading
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, build_opener, HTTPRedirectHandler

from flask import jsonify, request
from .panel_atlas_test import enabled as atlas_test_enabled, local_request, MongoParticipantArchive, SCOPE
from passport.mongodb_profile_archive import ProfileStorageUnavailable

PREFIX = '/api/companion/auth/panel'
DEFAULT_ORIGIN = 'https://imiq-staging.et.uni-magdeburg.de'
MAX_RESPONSE = 64 * 1024
CODE = re.compile(r'[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{9}\Z')


class PanelError(Exception):
    def __init__(self, code, status):
        self.code, self.status = code, status


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class PanelClient:
    def __init__(self, origin):
        parts = urlsplit(origin)
        if (parts.scheme != 'https' or not parts.hostname or parts.username or
                parts.password or parts.query or parts.fragment or parts.path not in ('', '/')):
            raise ValueError('Panel origin must be an HTTPS origin without credentials or path')
        self.origin = origin.rstrip('/')
        self.opener = build_opener(NoRedirect())

    def call(self, path, *, payload=None, access=None):
        headers = {'Accept': 'application/json'}
        body = None
        if payload is not None:
            body = json.dumps(payload).encode('utf-8')
            headers['Content-Type'] = 'application/json'
        if access:
            headers['Authorization'] = 'Bearer ' + access
        req = Request(self.origin + path, data=body, headers=headers)
        try:
            with self.opener.open(req, timeout=12) as response:
                raw = response.read(MAX_RESPONSE + 1)
                if len(raw) > MAX_RESPONSE:
                    raise PanelError('panel_invalid_response', 502)
                data = json.loads(raw)
                if not isinstance(data, dict):
                    raise PanelError('panel_invalid_response', 502)
                return data
        except HTTPError as exc:
            status = exc.code
            exc.close()
            if status in (400, 401, 403):
                raise PanelError('panel_authentication_failed', 401) from None
            if status == 429:
                raise PanelError('panel_rate_limited', 429) from None
            raise PanelError('panel_unavailable', 503) from None
        except (URLError, OSError, TimeoutError):
            raise PanelError('panel_unavailable', 503) from None
        except (ValueError, UnicodeError, RecursionError):
            raise PanelError('panel_invalid_response', 502) from None


def token(value):
    if not isinstance(value, str) or not 1 <= len(value) <= 8192 or any(ord(c) <= 32 or ord(c) >= 127 for c in value):
        raise PanelError('invalid_token', 400)
    return value


def identity(account, origin, study):
    code = account.get('participant_code')
    if account.get('is_active') is not True or not isinstance(code, str) or not CODE.fullmatch(code):
        raise PanelError('panel_account_unavailable', 403)
    subject = sha256(json.dumps([origin, study, code], separators=(',', ':')).encode()).hexdigest()
    return {'participant_id': subject, 'participant_code': code, 'panel_origin': origin,
            'study_id': study, 'study_membership': 'not_verified',
            'study_consent': 'not_verified', 'profile_transfer_authorized': False}


def register_panel_login(app):
    enabled = app.config.get('PANEL_LOGIN_ENABLED', os.environ.get('COMPANION_PANEL_LOGIN_ENABLED') == '1')
    if not enabled:
        if atlas_test_enabled(app):
            raise ValueError('Panel Atlas test requires panel login')
        return
    origin = app.config.get('PANEL_ORIGIN', os.environ.get('COMPANION_PANEL_ORIGIN', DEFAULT_ORIGIN)).rstrip('/')
    study = app.config.get('PANEL_STUDY_ID', os.environ.get('COMPANION_PANEL_STUDY_ID', 'app_imiq_test'))
    if not isinstance(study, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', study):
        raise ValueError('Invalid Companion study identifier')
    client = app.config.get('PANEL_CLIENT') or PanelClient(origin)
    test_enabled = atlas_test_enabled(app)
    app.extensions['companion_panel_atlas_local_test'] = test_enabled
    if test_enabled:
        app.logger.setLevel('INFO')
    if test_enabled and (study != 'app_imiq_test' or origin != DEFAULT_ORIGIN):
        raise ValueError('Panel Atlas test requires staging and app_imiq_test')
    participants = app.config.get('PANEL_PARTICIPANT_ARCHIVE') or MongoParticipantArchive()
    attempts, lock = OrderedDict(), threading.Lock()
    capacity = threading.BoundedSemaphore(4)

    @app.before_request
    def protect_panel_auth():
        if not request.path.startswith(PREFIX + '/'):
            return None
        if test_enabled and not local_request():
            return jsonify(error='local_panel_test_only'), 403
        # Refuse credentials over public cleartext; local USB validation is explicit.
        host = urlsplit(request.host_url).hostname
        local = request.remote_addr in ('127.0.0.1', '::1') and host in ('localhost', '127.0.0.1', '::1', '10.0.2.2')
        if not request.is_secure and not local:
            return jsonify(error='https_required'), 403
        if request.method == 'POST':
            if len(request.get_data(cache=True)) > 20 * 1024:
                return jsonify(error='auth_body_too_large'), 413
            if not request.is_json:
                return jsonify(error='expected_json'), 415
        if request.method in ('GET', 'POST'):
            now = time.monotonic()
            with lock:
                key = request.remote_addr
                while attempts and now - next(iter(attempts.values()))[-1] > 60:
                    attempts.popitem(last=False)
                values = attempts.pop(key, deque())
                while values and now - values[0] > 60:
                    values.popleft()
                allowed = len(values) < 20 and len(attempts) < 4096
                if allowed:
                    values.append(now)
                if values:
                    attempts[key] = values
            if not allowed:
                response = jsonify(error='login_rate_limited')
                response.status_code = 429
                response.headers['Retry-After'] = '60'
                return response

    @app.after_request
    def private_auth_response(response):
        if request.path.startswith(PREFIX + '/'):
            response.headers['Cache-Control'] = 'no-store'
            response.headers['Pragma'] = 'no-cache'
            if test_enabled:
                error = (response.get_json(silent=True) or {}).get('error', 'none')
                app.logger.info('panel_auth_http endpoint=%s status=%d code=%s', request.endpoint, response.status_code, error)
        return response

    def run(operation):
        if not capacity.acquire(blocking=False):
            return jsonify(error='panel_busy'), 503
        try:
            return jsonify(operation())
        except PanelError as exc:
            return jsonify(error=exc.code), exc.status
        finally:
            capacity.release()

    def complete(tokens):
        access = token(tokens.get('access'))
        refresh = token(tokens.get('refresh'))
        return {'schema_version': 'companion-panel-session-v1', 'access': access,
                'refresh': refresh, 'identity': verified_identity(access)}

    def verified_identity(access):
        if test_enabled and not local_request():
            raise PanelError('local_panel_test_only', 403)
        account = client.call('/api/auth/me/', access=token(access))
        subject = identity(account, origin, study)
        if test_enabled:
            try:
                receipt = participants.store_verified(subject)
            except ProfileStorageUnavailable:
                raise PanelError('participant_storage_unavailable', 503) from None
            subject.update(test_scope=SCOPE, local_test_profile_upload=True, identity_storage=receipt)
        return subject

    app.extensions['companion_panel_identity_verifier'] = verified_identity

    @app.post(PREFIX + '/login')
    def panel_login():
        def operation():
            data = request.get_json(silent=True)
            if not isinstance(data, dict) or set(data) != {'email', 'password'}:
                raise PanelError('invalid_login', 400)
            email, password = data['email'], data['password']
            if (not isinstance(email, str) or not isinstance(password, str) or
                    not 3 <= len(email.strip()) <= 254 or '@' not in email or not 1 <= len(password) <= 1024):
                raise PanelError('invalid_login', 400)
            return complete(client.call('/api/auth/login/', payload={'email': email.strip().lower(), 'password': password}))
        return run(operation)

    @app.post(PREFIX + '/refresh')
    def panel_refresh():
        def operation():
            data = request.get_json(silent=True)
            if not isinstance(data, dict) or set(data) != {'refresh'}:
                raise PanelError('invalid_refresh', 400)
            refresh = token(data['refresh'])
            tokens = client.call('/api/auth/refresh/', payload={'refresh': refresh})
            return complete(dict(tokens, refresh=tokens.get('refresh', refresh)))
        return run(operation)

    @app.get(PREFIX + '/me')
    def panel_me():
        def operation():
            authorization = request.headers.get('Authorization', '')
            if not authorization.startswith('Bearer '):
                raise PanelError('panel_authentication_required', 401)
            return {'schema_version': 'companion-panel-identity-v1', 'identity': verified_identity(authorization[7:])}
        return run(operation)
