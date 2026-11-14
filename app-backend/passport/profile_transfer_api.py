"""Explicitly enabled loopback-only synthetic Atlas integration, not participant API."""
import os
from urllib.parse import urlsplit
from flask import jsonify, request

from .mongodb_profile_archive import MongoProfileArchive, ProfileRevisionConflict, ProfileStorageUnavailable, local_test_archive
from .profile_transfer import InvalidProfileTransfer, decode_profile_transfer

PATH = '/api/companion/data/test/profile-versions'
AUTO_PATH = '/api/companion/data/profile-versions'
CAPABILITY_PATH = '/api/companion/data/profile-transfer/status'
MAX_BYTES = 4 * 1024 * 1024


def register_profile_transfer(app):
    enabled = app.config.get('BLOCK03_ATLAS_TEST_ENABLED', os.environ.get('COMPANION_BLOCK03_ATLAS_TEST_ENABLED') == '1')
    if not enabled:
        return
    archive = app.config.get('BLOCK03_ATLAS_ARCHIVE')
    if archive is None:
        archive = MongoProfileArchive(os.environ.get('COMPANION_MONGODB_URI'),
                                     os.environ.get('COMPANION_MONGODB_DATABASE', 'companion_test'))
    app.extensions['companion_block03_archive'] = archive

    @app.post(PATH)
    def store_synthetic_profile():
        return receive_profile(archive, allow_participant=False)


def register_automatic_profile_transfer(app):
    """Owner-operated loopback validation; not university participant enrollment."""
    enabled = app.config.get('PROFILE_UPLOAD_LOCAL_VALIDATION', os.environ.get('COMPANION_PROFILE_UPLOAD_LOCAL_VALIDATION') == '1')
    if not enabled:
        return
    archive = app.config.get('PROFILE_UPLOAD_ARCHIVE')
    if archive is None:
        archive = local_test_archive()
    app.extensions['companion_profile_upload_archive'] = archive
    from infrastructure.panel_atlas_test import enabled as panel_test_enabled
    from infrastructure.panel_login import PanelError
    panel_test = panel_test_enabled(app)

    @app.after_request
    def profile_transfer_failure_diagnostic(response):
        # Fixed endpoint/status/error codes only. Never log headers, payloads or identities.
        if panel_test and request.path in (CAPABILITY_PATH, AUTO_PATH):
            error = (response.get_json(silent=True) or {}).get('error', 'unspecified')
            app.logger.info('profile_transfer_http endpoint=%s status=%d error=%s',
                               request.endpoint, response.status_code, error)
        return response

    def test_owner():
        verifier = app.extensions.get('companion_panel_identity_verifier')
        authorization = request.headers.get('Authorization', '')
        if verifier is None or not authorization.startswith('Bearer '):
            raise PanelError('panel_authentication_required', 401)
        owner = verifier(authorization[7:])
        if (owner.get('test_scope') != 'local-panel-account-test' or not owner.get('local_test_profile_upload') or
                request.headers.get('X-Companion-Participant-ID') != owner['participant_id']):
            raise PanelError('participant_binding_mismatch', 403)
        return owner

    @app.get(CAPABILITY_PATH)
    def profile_transfer_status():
        if not local_request():
            return jsonify(error='local_validation_only'), 403
        owner = None
        if panel_test:
            try:
                owner = test_owner()
            except PanelError as error:
                return jsonify(error=error.code), error.status
        response = jsonify(schema_version='companion-profile-transfer-capability-v1',
            enabled=True, mode='local-panel-account-test' if panel_test else 'local-profile-validation', automatic_profile_upload=True,
            max_bytes=MAX_BYTES, profile_path=AUTO_PATH,
            **({'participant_id': owner['participant_id'], 'study_id': owner['study_id'], 'panel_origin': owner['panel_origin']} if owner else {}))
        response.headers['Cache-Control'] = 'no-store'
        return response

    @app.post(AUTO_PATH)
    def store_confirmed_profile():
        if not local_request():
            return jsonify(error='local_validation_only', stored_on_server=False), 403
        owner = None
        if panel_test:
            try:
                owner = test_owner()
            except PanelError as error:
                return jsonify(error=error.code, stored_on_server=False), error.status
        return receive_profile(archive, allow_participant=True, owner=owner)


def local_request():
    return request.remote_addr in ('127.0.0.1', '::1') and urlsplit(request.host_url).hostname in ('localhost', '127.0.0.1', '::1', '10.0.2.2')


def receive_profile(archive, *, allow_participant, owner=None):
        if request.remote_addr not in ('127.0.0.1', '::1'):
            return jsonify(error='loopback_test_only', stored_on_server=False), 403
        if request.content_length is not None and request.content_length > MAX_BYTES:
            return jsonify(error='profile_too_large', stored_on_server=False), 413
        if not request.is_json or request.headers.get('Content-Encoding', 'identity') != 'identity':
            return jsonify(error='expected_uncompressed_json', stored_on_server=False), 415
        raw = request.get_data(cache=True)
        if len(raw) > MAX_BYTES:
            return jsonify(error='profile_too_large', stored_on_server=False), 413
        try:
            payload = decode_profile_transfer(raw, allow_participant=allow_participant)
            receipt = archive.store(payload, raw, owner=owner) if owner is not None else archive.store(payload, raw)
        except InvalidProfileTransfer:
            return jsonify(error='invalid_profile_evidence', stored_on_server=False), 422
        except ProfileRevisionConflict:
            return jsonify(error='profile_revision_conflict', stored_on_server=False), 409
        except ProfileStorageUnavailable:
            response = jsonify(error='profile_storage_unavailable', stored_on_server=False, retryable=True)
            response.status_code = 503
            response.headers['Retry-After'] = '5'
            return response
        response = jsonify(receipt)
        response.status_code = 200 if receipt['already_stored'] else 201
        response.headers['Cache-Control'] = 'no-store'
        return response
