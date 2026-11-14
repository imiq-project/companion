"""Block 04 archives: owned parent lookup and atomic immutable payload/receipt."""
from copy import deepcopy
from datetime import datetime, timezone
from hashlib import sha256
import json

from .mongodb_profile_archive import MongoProfileArchive, ProfileStorageUnavailable, ProfileRevisionConflict, local_test_archive
from .priority_transfer import validate_priority_transfer, InvalidPriorityTransfer


class PriorityDependencyPending(RuntimeError):
    pass


class MongoPriorityArchive:
    def __init__(self, *, profile_archive=None, profile_collection=None, search_collection=None):
        self.profile_archive=profile_archive
        self.profiles=profile_collection
        self.searches=search_collection

    def collections(self):
        if self.profiles is None:
            self.profile_archive=self.profile_archive or local_test_archive()
            self.profiles=self.profile_archive.collection()
        if self.searches is None:
            self.searches=self.profiles.database.get_collection('search_revisions',write_concern=self.profiles.write_concern,
                read_concern=self.profiles.read_concern,read_preference=self.profiles.read_preference)
        return self.profiles,self.searches

    @staticmethod
    def identity(kind, logical_id, owner, data_class='participant'):
        return sha256(json.dumps(['companion-block04-v1',data_class,owner['participant_id'],owner['panel_origin'],
            owner['study_id'],kind,logical_id],separators=(',',':')).encode()).hexdigest()

    def store(self, payload, raw, *, owner):
        try:
            if payload.get('data_class') not in ('synthetic','participant'):
                raise InvalidPriorityTransfer('invalid_data_class')
            synthetic=payload['data_class']=='synthetic'
            if owner.get('test_scope')!=('synthetic-block04' if synthetic else 'local-panel-account-test'):
                raise InvalidPriorityTransfer('invalid_owner_scope')
            if synthetic and not owner['participant_id'].startswith('synthetic-'):
                raise InvalidPriorityTransfer('invalid_synthetic_owner')
            if owner['study_id']!='app_imiq_test' or owner['panel_origin']!='https://imiq-staging.et.uni-magdeburg.de':
                raise InvalidPriorityTransfer('invalid_test_context')
            profiles,searches=self.collections()
            current=payload['contextual_passport_snapshot']
            logical=payload['search_id'] if payload['kind']=='predecision_ema' else current['lineage']['passport_id']
            record_id=self.identity(payload['kind'],logical,owner,payload['data_class'])
            target=searches if payload['kind']=='predecision_ema' else profiles
            digest=sha256(raw).hexdigest()
            existing=target.find_one({'_id':record_id})
            if existing is not None:
                return MongoProfileArchive.existing_receipt(existing,digest)
            initial_id=MongoProfileArchive.identity(current['agent_id'],payload['initial_profile_revision_id'],
                payload['data_class'],None if synthetic else owner)
            initial=profiles.find_one({'_id':initial_id})
            if initial is None:
                raise PriorityDependencyPending('initial_profile_not_stored')
            if not synthetic and any(initial.get(key)!=owner[key] for key in ('participant_id','study_id','panel_origin')):
                raise InvalidPriorityTransfer('initial_profile_owner_mismatch')
            if initial['payload']['questionnaire_attempt_id']!=payload['questionnaire_attempt_id']:
                raise InvalidPriorityTransfer('questionnaire_mismatch')
            base=initial['payload']['android_profile_snapshot']['response']['cognitive_passport']
            parent_id=payload['base_passport_reference']['passport_id']
            if base['lineage']['passport_id']!=parent_id:
                parent=profiles.find_one({'_id':self.identity('weekly_reference_confirmation',parent_id,owner,payload['data_class'])})
                if parent is None:
                    raise PriorityDependencyPending('weekly_parent_not_stored')
                if parent['payload']['initial_profile_revision_id']!=payload['initial_profile_revision_id']:
                    raise InvalidPriorityTransfer('parent_root_mismatch')
                base=parent['payload']['contextual_passport_snapshot']
            validate_priority_transfer(payload,base)
            at=datetime.now(timezone.utc).isoformat()
            receipt={'schema_version':'companion-priority-storage-receipt-v1','storage_record_id':record_id,
                'priority_revision_id':payload['priority_revision_id'],'kind':payload['kind'],'search_id':payload['search_id'],
                'payload_sha256':digest,'stored_at':at,'stored_on_server':True,'learning_applied':False,
                'data_class':payload['data_class'],**{key:owner[key] for key in ('participant_id','study_id','panel_origin','test_scope')}}
            document={'_id':record_id,'schema_version':'companion-priority-archive-v1','block_coverage':['04'],
                'record_type':payload['kind'],'scope':'synthetic-development-block04' if synthetic else 'local-panel-account-test-block04',
                **{key:owner[key] for key in ('participant_id','study_id','panel_origin')},
                'priority_revision_id':payload['priority_revision_id'],'search_id':payload['search_id'],
                'created_at':payload['created_at'],'stored_at':at,
                'passport_id':current['lineage']['passport_id'],'parent_passport_id':current['lineage']['parent_passport_id'],
                'initial_profile_revision_id':payload['initial_profile_revision_id'],
                'study_membership':'not_verified','study_consent':'not_verified','production_authorized':False,
                'payload_sha256':digest,'payload':deepcopy(payload),'receipt':receipt}
            try:
                result=target.insert_one(document)
                if not result.acknowledged:
                    raise ProfileStorageUnavailable('priority_write_not_acknowledged')
            except Exception as error:
                if getattr(error,'code',None)!=11000:
                    raise
                existing=target.find_one({'_id':record_id})
                if existing is None:
                    raise ProfileStorageUnavailable('priority_receipt_unavailable')
                return MongoProfileArchive.existing_receipt(existing,digest)
            return {**receipt,'already_stored':False}
        except (InvalidPriorityTransfer,PriorityDependencyPending,ProfileRevisionConflict,ProfileStorageUnavailable):
            raise
        except Exception:
            raise ProfileStorageUnavailable('priority_storage_unavailable') from None
