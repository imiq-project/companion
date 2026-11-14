"""Owner-authorized loopback test of identity and block 03, separate from enrollment."""
from datetime import datetime, timezone
import os
from urllib.parse import urlsplit

from flask import request
from passport.mongodb_profile_archive import ProfileStorageUnavailable, local_test_archive

SCOPE = 'local-panel-account-test'


def enabled(app):
    return app.config.get('PANEL_ATLAS_LOCAL_TEST', os.environ.get('COMPANION_PANEL_ATLAS_LOCAL_TEST') == '1')


def local_request():
    return (request.remote_addr in ('127.0.0.1', '::1') and
            urlsplit(request.host_url).hostname in ('localhost', '127.0.0.1', '::1', '10.0.2.2'))


class MongoParticipantArchive:
    def __init__(self, *, profile_archive=None, collection=None):
        self.profile_archive, self._collection = profile_archive, collection

    def collection(self):
        if self._collection is None:
            self.profile_archive = self.profile_archive or local_test_archive()
            profiles = self.profile_archive.collection()
            self._collection = profiles.database.get_collection('participants',
                write_concern=profiles.write_concern, read_concern=profiles.read_concern,
                read_preference=profiles.read_preference)
        return self._collection

    def store_verified(self, identity):
        # Explicit allowlist: never persist email/password, bearer credentials or account PII.
        fixed = {'_id': identity['participant_id'], 'participant_id': identity['participant_id'], 'schema_version': 'companion-panel-test-participant-v1',
                 'scope': SCOPE, 'panel_origin': identity['panel_origin'],
                 'study_id': identity['study_id'], 'participant_code': identity['participant_code']}
        if fixed['study_id'] != 'app_imiq_test' or fixed['panel_origin'] != 'https://imiq-staging.et.uni-magdeburg.de':
            raise ProfileStorageUnavailable('local_test_context_required')
        at = datetime.now(timezone.utc).isoformat()
        try:
            result = self.collection().update_one(fixed, {'$setOnInsert': dict(fixed, created_at=at),
                '$set': {'last_identity_verified_at': at, 'study_membership': 'not_verified',
                         'study_consent': 'not_verified', 'production_authorized': False,
                         'test_authorization': 'owner_authorized_local_integration_test'}}, upsert=True)
            if not result.acknowledged:
                raise ProfileStorageUnavailable('participant_write_not_acknowledged')
        except Exception:
            raise ProfileStorageUnavailable('participant_storage_unavailable') from None
        return {'participant_id': identity['participant_id'], 'identity_stored_on_server': True,
                'collection': 'participants', 'database': 'companion_test', 'verified_at': at, 'scope': SCOPE}
