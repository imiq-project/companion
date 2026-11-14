"""Atomic terminal search headers in search_revisions, owned and linked to block 04."""
from copy import deepcopy
from datetime import datetime,timezone
from hashlib import sha256
import json
from passport.mongodb_priority_archive import MongoPriorityArchive,PriorityDependencyPending
from passport.mongodb_profile_archive import MongoProfileArchive,ProfileStorageUnavailable,ProfileRevisionConflict
from passport.search_transfer import InvalidSearchTransfer,validate_search_parent


class MongoSearchArchive:
    def __init__(self,*,priority_archive=None,collection=None):
        self.priority_archive=priority_archive
        self._collection=collection

    def collection(self):
        if self._collection is None:
            self.priority_archive=self.priority_archive or MongoPriorityArchive()
            self._collection=self.priority_archive.collections()[1]
        return self._collection

    @staticmethod
    def identity(search_id,owner,data_class='participant'):
        return sha256(json.dumps(['companion-block05-v1',data_class,owner['participant_id'],owner['panel_origin'],owner['study_id'],search_id],separators=(',',':')).encode()).hexdigest()

    def store(self,payload,raw,*,owner):
        try:
            synthetic=payload['data_class']=='synthetic'
            if (payload['data_class'] not in ('synthetic','participant') or
                    owner.get('test_scope')!=('synthetic-block05' if synthetic else 'local-panel-account-test') or
                    synthetic and not owner['participant_id'].startswith('synthetic-') or
                    owner['study_id']!='app_imiq_test' or owner['panel_origin']!='https://imiq-staging.et.uni-magdeburg.de'):
                raise InvalidSearchTransfer('invalid_owner_scope')
            collection=self.collection();digest=sha256(raw).hexdigest()
            record_id=self.identity(payload['search_id'],owner,payload['data_class'])
            existing=collection.find_one({'_id':record_id})
            if existing is not None:
                return MongoProfileArchive.existing_receipt(existing,digest)
            parent_id=MongoPriorityArchive.identity('predecision_ema',payload['search_id'],owner,payload['data_class'])
            parent=collection.find_one({'_id':parent_id})
            if parent is None:
                raise PriorityDependencyPending('search_priority_not_stored')
            if any(parent.get(key)!=owner[key] for key in ('participant_id','study_id','panel_origin')):
                raise InvalidSearchTransfer('search_priority_owner_mismatch')
            validate_search_parent(payload,parent['payload'])
            at=datetime.now(timezone.utc).isoformat()
            receipt={'schema_version':'companion-search-storage-receipt-v1','storage_record_id':record_id,
                'search_revision_id':payload['search_revision_id'],'search_id':payload['search_id'],'status':payload['status'],
                'priority_storage_record_id':parent_id,'payload_sha256':digest,'stored_at':at,
                'stored_on_server':True,'learning_applied':False,'data_class':payload['data_class'],
                **{key:owner[key] for key in ('participant_id','study_id','panel_origin','test_scope')}}
            document={'_id':record_id,'schema_version':'companion-search-archive-v1','record_type':'search_metadata',
                'block_coverage':['05'],'scope':'synthetic-development-block05' if synthetic else 'local-panel-account-test-block05',
                **{key:owner[key] for key in ('participant_id','study_id','panel_origin')},
                'search_revision_id':payload['search_revision_id'],'search_id':payload['search_id'],'search_status':payload['status'],
                'priority_storage_record_id':parent_id,'priority_revision_id':payload['priority_reference']['priority_revision_id'],
                'passport_id':payload['passport_reference']['passport_id'],'created_at':payload['created_at'],
                'completed_at':payload['completed_at'],'stored_at':at,'payload_sha256':digest,
                'study_membership':'not_verified','study_consent':'not_verified','production_authorized':False,
                'payload':deepcopy(payload),'receipt':receipt}
            try:
                result=collection.insert_one(document)
                if not result.acknowledged:
                    raise ProfileStorageUnavailable('search_write_not_acknowledged')
            except Exception as error:
                if getattr(error,'code',None)!=11000:
                    raise
                existing=collection.find_one({'_id':record_id})
                if existing is None:
                    raise ProfileStorageUnavailable('search_receipt_unavailable')
                return MongoProfileArchive.existing_receipt(existing,digest)
            return {**receipt,'already_stored':False}
        except (InvalidSearchTransfer,PriorityDependencyPending,ProfileRevisionConflict,ProfileStorageUnavailable):
            raise
        except Exception:
            raise ProfileStorageUnavailable('search_storage_unavailable') from None
