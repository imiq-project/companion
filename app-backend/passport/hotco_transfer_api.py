"""Authenticated loopback-only block-06B transfer in the existing Atlas test context."""
import os
from flask import jsonify,request

from infrastructure.panel_atlas_test import enabled as panel_test_enabled,local_request
from infrastructure.panel_login import PanelError
from .mongodb_hotco_archive import MongoHotcoArchive
from .mongodb_priority_archive import PriorityDependencyPending
from .mongodb_profile_archive import ProfileStorageUnavailable,ProfileRevisionConflict
from .hotco_transfer import decode_hotco_transfer,InvalidHotcoTransfer,MAX_BYTES,local_signer

PATH='/api/companion/data/hotco-simulations'
CAPABILITY='/api/companion/data/hotco-transfer/status'



def register_hotco_transfer(app):
    if not app.config.get('BLOCK06B_LOCAL_TEST',os.environ.get('COMPANION_BLOCK06B_LOCAL_TEST')=='1'):
        return
    if not panel_test_enabled(app) or 'companion_panel_identity_verifier' not in app.extensions:
        raise ValueError('Block 06B requires local panel/Atlas test authentication')
    if 'companion_results_archive' not in app.extensions:
        raise ValueError('Block 06B requires the block 06A archive')
    archive=app.config.get('HOTCO_ARCHIVE') or MongoHotcoArchive(results_archive=app.extensions.get('companion_results_archive'))
    app.extensions['companion_hotco_archive']=archive
    signer=app.config.get('HOTCO_SIGNER') or local_signer()
    app.extensions['companion_hotco_signer']=signer

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
    def private_hotco_response(response):
        if request.path in (PATH,CAPABILITY):
            response.headers['Cache-Control']='no-store'
            code=(response.get_json(silent=True) or {}).get('error','none')
            app.logger.info('hotco_transfer_http endpoint=%s status=%d code=%s',request.endpoint,response.status_code,code)
        return response

    @app.get(CAPABILITY)
    def hotco_transfer_status():
        try:
            subject=owner()
        except PanelError as error:
            return jsonify(error=error.code),error.status
        return jsonify(schema_version='companion-hotco-transfer-capability-v1',enabled=True,
            mode='local-panel-account-test',hotco_path=PATH,max_bytes=MAX_BYTES,
            **{key:subject[key] for key in ('participant_id','study_id','panel_origin')})

    @app.post(PATH)
    def store_hotco_simulation():
        try:
            subject=owner()
            if not request.is_json or request.headers.get('Content-Encoding','identity')!='identity':
                return jsonify(error='expected_uncompressed_json',stored_on_server=False),415
            if request.content_length is not None and request.content_length>MAX_BYTES:
                return jsonify(error='hotco_too_large',stored_on_server=False),413
            raw=request.get_data(cache=True)
            if len(raw)>MAX_BYTES:
                return jsonify(error='hotco_too_large',stored_on_server=False),413
            wire,evidence=decode_hotco_transfer(raw,signer)
            receipt=archive.store(wire,evidence,raw,owner=subject)
        except PanelError as error:
            return jsonify(error=error.code,stored_on_server=False),error.status
        except InvalidHotcoTransfer:
            return jsonify(error='invalid_hotco_evidence',stored_on_server=False),422
        except ProfileRevisionConflict:
            return jsonify(error='hotco_revision_conflict',stored_on_server=False),409
        except (PriorityDependencyPending,ProfileStorageUnavailable) as error:
            response=jsonify(error='hotco_dependency_pending' if isinstance(error,PriorityDependencyPending) else 'hotco_storage_unavailable',stored_on_server=False,retryable=True)
            response.status_code=503;response.headers['Retry-After']='5'
            return response
        return jsonify(receipt),200 if receipt['already_stored'] else 201
