"""Authenticated loopback-only block-05 transfer in the existing Atlas test context."""
import os
from flask import jsonify,request

from infrastructure.panel_atlas_test import enabled as panel_test_enabled,local_request
from infrastructure.panel_login import PanelError
from .mongodb_search_archive import MongoSearchArchive
from .mongodb_priority_archive import PriorityDependencyPending
from .mongodb_profile_archive import ProfileStorageUnavailable,ProfileRevisionConflict
from .search_transfer import decode_search_transfer,InvalidSearchTransfer

PATH='/api/companion/data/search-metadata'
CAPABILITY='/api/companion/data/search-transfer/status'
MAX_BYTES=512*1024


def register_search_transfer(app):
    if not app.config.get('BLOCK05_LOCAL_TEST',os.environ.get('COMPANION_BLOCK05_LOCAL_TEST')=='1'):
        return
    if not panel_test_enabled(app) or 'companion_panel_identity_verifier' not in app.extensions:
        raise ValueError('Block 05 requires local panel/Atlas test authentication')
    if 'companion_priority_archive' not in app.extensions:
        raise ValueError('Block 05 requires the block 04 archive')
    archive=app.config.get('SEARCH_ARCHIVE') or MongoSearchArchive(priority_archive=app.extensions.get('companion_priority_archive'))
    app.extensions['companion_search_archive']=archive

    def owner():
        if not local_request():
            raise PanelError('local_panel_test_only',403)
        authorization=request.headers.get('Authorization','')
        if not authorization.startswith('Bearer '):
            raise PanelError('panel_authentication_required',401)
        subject=app.extensions['companion_panel_identity_verifier'](authorization[7:])
        if (subject.get('test_scope')!='local-panel-account-test' or not subject.get('local_test_profile_upload') or
                request.headers.get('X-Companion-Participant-ID')!=subject['participant_id']):
            raise PanelError('participant_binding_mismatch',403)
        return subject

    @app.after_request
    def private_search_response(response):
        if request.path in (PATH,CAPABILITY):
            response.headers['Cache-Control']='no-store'
            code=(response.get_json(silent=True) or {}).get('error','none')
            app.logger.info('search_transfer_http endpoint=%s status=%d code=%s',request.endpoint,response.status_code,code)
        return response

    @app.get(CAPABILITY)
    def search_transfer_status():
        try:
            subject=owner()
        except PanelError as error:
            return jsonify(error=error.code),error.status
        return jsonify(schema_version='companion-search-transfer-capability-v1',enabled=True,
            mode='local-panel-account-test',search_path=PATH,max_bytes=MAX_BYTES,
            **{key:subject[key] for key in ('participant_id','study_id','panel_origin')})

    @app.post(PATH)
    def store_search_metadata():
        try:
            subject=owner()
            if not request.is_json or request.headers.get('Content-Encoding','identity')!='identity':
                return jsonify(error='expected_uncompressed_json',stored_on_server=False),415
            if request.content_length is not None and request.content_length>MAX_BYTES:
                return jsonify(error='search_too_large',stored_on_server=False),413
            raw=request.get_data(cache=True)
            if len(raw)>MAX_BYTES:
                return jsonify(error='search_too_large',stored_on_server=False),413
            payload=decode_search_transfer(raw)
            receipt=archive.store(payload,raw,owner=subject)
        except PanelError as error:
            return jsonify(error=error.code,stored_on_server=False),error.status
        except InvalidSearchTransfer:
            return jsonify(error='invalid_search_evidence',stored_on_server=False),422
        except ProfileRevisionConflict:
            return jsonify(error='search_revision_conflict',stored_on_server=False),409
        except (PriorityDependencyPending,ProfileStorageUnavailable) as error:
            response=jsonify(error='search_dependency_pending' if isinstance(error,PriorityDependencyPending) else 'search_storage_unavailable',stored_on_server=False,retryable=True)
            response.status_code=503;response.headers['Retry-After']='5'
            return response
        return jsonify(receipt),200 if receipt['already_stored'] else 201
