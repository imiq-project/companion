"""Atomic synthetic profile plus embedded receipt, with immutable revision IDs."""
from copy import deepcopy
from datetime import datetime, timezone
from hashlib import sha256
import json
import sys
from urllib.parse import parse_qs, urlsplit


class ProfileRevisionConflict(ValueError):
    pass


class ProfileStorageUnavailable(RuntimeError):
    pass


def local_test_archive():
    """Load the existing private Atlas configuration without exposing its URI."""
    from resources import PROJECT_ROOT
    from dotenv import dotenv_values
    directory = PROJECT_ROOT / 'runtime/mongodb-atlas/python'
    if directory.is_dir() and str(directory) not in sys.path:
        sys.path.insert(0, str(directory))
    values = dotenv_values(PROJECT_ROOT / 'runtime/state/private/atlas.env')
    return MongoProfileArchive(values.get('COMPANION_MONGODB_URI'), values.get('COMPANION_MONGODB_DATABASE', 'companion_test'))


class MongoProfileArchive:
    def __init__(self, uri=None, database='companion_test', *, collection=None):
        if database != 'companion_test':
            raise ValueError('Synthetic integration requires companion_test')
        self.uri, self.database = uri, database
        self._collection, self._client = collection, None

    def collection(self):
        if self._collection is not None:
            return self._collection
        try:
            parsed = urlsplit(self.uri or '')
            options = {k.lower(): v for k, v in parse_qs(parsed.query).items()}
            if (parsed.scheme != 'mongodb+srv' or not parsed.hostname or not parsed.hostname.endswith('.mongodb.net')
                    or not parsed.username or not parsed.password or parsed.port is not None
                    or any(any(v.lower() not in ('false', '0') for v in options.get(k, []))
                           for k in ('tlsinsecure', 'tlsallowinvalidcertificates', 'tlsallowinvalidhostnames'))
                    or any(v.lower() in ('false', '0') for k in ('tls', 'ssl') for v in options.get(k, []))):
                raise ProfileStorageUnavailable('invalid_atlas_configuration')
            from pymongo import MongoClient, ReadPreference
            from pymongo.server_api import ServerApi
            from pymongo.read_concern import ReadConcern
            from pymongo.write_concern import WriteConcern
            self._client = MongoClient(self.uri, tls=True, server_api=ServerApi('1'),
                appname='Companion-block03-synthetic', serverSelectionTimeoutMS=10000,
                connectTimeoutMS=10000, socketTimeoutMS=15000)
            self._collection = self._client[self.database].get_collection('profile_versions',
                write_concern=WriteConcern(w='majority', j=True, wtimeout=10000),
                read_concern=ReadConcern('majority'), read_preference=ReadPreference.PRIMARY)
            return self._collection
        except Exception:
            self.close()
            raise ProfileStorageUnavailable('atlas_unavailable') from None

    @staticmethod
    def identity(agent_id, revision_id, data_class='synthetic', owner=None):
        if owner is not None:
            return sha256(json.dumps(['companion-block03-panel-local-test-v1', owner['participant_id'],
                owner['panel_origin'], owner['study_id'], agent_id, revision_id], separators=(',', ':')).encode()).hexdigest()
        namespace = 'companion-block03-synthetic-v1' if data_class == 'synthetic' else 'companion-block03-local-validation-v1'
        return sha256(json.dumps([namespace, agent_id, revision_id],
                                 separators=(',', ':')).encode()).hexdigest()

    def store(self, payload, raw, *, owner=None):
        agent = payload['source']['onboarding_request']['agent_id']
        revision = payload['profile_revision_id']
        data_class = payload['data_class']
        if data_class not in ('synthetic', 'participant'):
            raise ProfileRevisionConflict('unsupported_data_class')
        if owner is not None and (data_class != 'participant' or owner.get('test_scope') != 'local-panel-account-test'):
            raise ProfileRevisionConflict('unsupported_owner_scope')
        record_id = self.identity(agent, revision, data_class, owner)
        digest = sha256(raw).hexdigest()
        try:
            collection = self.collection()
            existing = collection.find_one({'_id': record_id}, {'payload_sha256': 1, 'receipt': 1})
            if existing is not None:
                return self.existing_receipt(existing, digest)
            previous = payload['previous_revision_id']
            if previous is not None and collection.find_one({'_id': self.identity(agent, previous, data_class, owner)}, {'_id': 1}) is None:
                raise ProfileRevisionConflict('previous_revision_not_found')
            receipt = {'schema_version': 'companion-profile-storage-receipt-v1',
                'storage_record_id': record_id, 'profile_revision_id': revision,
                'payload_sha256': digest, 'stored_at': datetime.now(timezone.utc).isoformat(),
                'stored_on_server': True, 'data_class': data_class, 'learning_applied': False}
            if owner is not None:
                receipt.update({key: owner[key] for key in ('participant_id', 'study_id', 'panel_origin', 'test_scope')})
            document = {'_id': record_id, 'schema_version': 'companion-profile-archive-v1',
                'scope': 'synthetic-development-block03' if data_class == 'synthetic' else 'local-profile-validation-block03', 'agent_id': agent,
                'profile_revision_id': revision, 'previous_revision_id': previous,
                'questionnaire_attempt_id': payload['questionnaire_attempt_id'],
                'payload_sha256': digest, 'payload': deepcopy(payload), 'receipt': receipt}
            if owner is not None:
                document.update({key: owner[key] for key in ('participant_id', 'study_id', 'panel_origin')})
                document.update(scope='local-panel-account-test-block03', study_membership='not_verified',
                                study_consent='not_verified', production_authorized=False)
            try:
                result = collection.insert_one(document)
                if not result.acknowledged:
                    raise ProfileStorageUnavailable('unacknowledged_write')
            except Exception as error:
                if getattr(error, 'code', None) != 11000:
                    raise
                existing = collection.find_one({'_id': record_id}, {'payload_sha256': 1, 'receipt': 1})
                if existing is None:
                    raise ProfileStorageUnavailable('retry_receipt_unavailable')
                return self.existing_receipt(existing, digest)
            return {**receipt, 'already_stored': False}
        except (ProfileRevisionConflict, ProfileStorageUnavailable):
            raise
        except Exception:
            raise ProfileStorageUnavailable('atlas_write_unavailable') from None

    @staticmethod
    def existing_receipt(document, digest):
        if document['payload_sha256'] != digest:
            raise ProfileRevisionConflict('revision_has_different_payload')
        return {**document['receipt'], 'already_stored': True}

    def close(self):
        if self._client is not None:
            self._client.close()
            self._client = None
            self._collection = None
