"""Owned immutable 06A results; large payloads publish only after all chunks commit."""
from copy import deepcopy
from datetime import datetime,timezone
from hashlib import sha256
import json
from passport.mongodb_profile_archive import MongoProfileArchive,ProfileRevisionConflict,ProfileStorageUnavailable
from passport.mongodb_priority_archive import PriorityDependencyPending
from passport.mongodb_search_archive import MongoSearchArchive
from passport.results_transfer import InvalidResultsTransfer,validate_parent

INLINE_BYTES=8*1024*1024
CHUNK_BYTES=1024*1024

def bson_bytes(value):
    """Size bound for the JSON types accepted by the closed schema, including array keys."""
    if isinstance(value,dict):return 5+sum(2+len(k.encode('utf-8'))+bson_bytes(v) for k,v in value.items())
    if isinstance(value,list):return 5+sum(2+len(str(i))+bson_bytes(v) for i,v in enumerate(value))
    if isinstance(value,str):return 5+len(value.encode('utf-8'))
    if value is None:return 0
    if isinstance(value,bool):return 1
    if isinstance(value,int):return 4 if -(2**31)<=value<2**31 else 8
    return 8

class MongoResultsArchive:
    def __init__(self,*,search_archive=None,collection=None,chunks=None,inline_bytes=INLINE_BYTES):
        self.search_archive=search_archive;self._collection=collection;self._chunks=chunks;self.inline_bytes=inline_bytes

    def collection(self):
        if self._collection is None:
            parent=self.search_archive.collection()
            self._collection=parent.database['search_results'].with_options(write_concern=parent.write_concern,read_concern=parent.read_concern,read_preference=parent.read_preference)
        return self._collection

    def chunks(self):
        if self._chunks is None:
            parent=self.collection()
            self._chunks=parent.database['search_result_chunks'].with_options(write_concern=parent.write_concern,read_concern=parent.read_concern,read_preference=parent.read_preference)
        return self._chunks

    @staticmethod
    def identity(search_id,owner,data_class='participant'):
        return sha256(json.dumps(['companion-block06a-v1',data_class,owner['participant_id'],owner['panel_origin'],owner['study_id'],search_id],separators=(',',':')).encode()).hexdigest()

    def store(self,payload,raw,*,owner):
        try:
            synthetic=payload['data_class']=='synthetic'
            if (payload['data_class'] not in ('synthetic','participant') or owner.get('test_scope')!=('synthetic-block06a' if synthetic else 'local-panel-account-test') or
                synthetic and not owner['participant_id'].startswith('synthetic-') or owner['study_id']!='app_imiq_test' or owner['panel_origin']!='https://imiq-staging.et.uni-magdeburg.de'):
                raise InvalidResultsTransfer('invalid_owner_scope')
            target=self.collection();digest=sha256(raw).hexdigest();record_id=self.identity(payload['search_id'],owner,payload['data_class'])
            existing=target.find_one({'_id':record_id})
            if existing is not None:return MongoProfileArchive.existing_receipt(existing,digest)
            parent_id=MongoSearchArchive.identity(payload['search_id'],owner,payload['data_class'])
            parent=self.search_archive.collection().find_one({'_id':parent_id})
            if parent is None:raise PriorityDependencyPending('results_search_not_stored')
            if any(parent.get(k)!=owner[k] for k in ('participant_id','study_id','panel_origin')):raise InvalidResultsTransfer('parent_owner_mismatch')
            validate_parent(payload,parent['payload'])
            at=datetime.now(timezone.utc).isoformat()
            receipt={'schema_version':'companion-search-results-storage-receipt-v1','storage_record_id':record_id,
                'results_revision_id':payload['results_revision_id'],'search_id':payload['search_id'],
                'search_metadata_storage_record_id':parent_id,'payload_sha256':digest,'stored_at':at,
                'stored_on_server':True,'learning_applied':False,'data_class':payload['data_class'],'collection':'search_results',
                **{k:owner[k] for k in ('participant_id','study_id','panel_origin','test_scope')}}
            document={'_id':record_id,'schema_version':'companion-search-results-archive-v1','record_type':'search_results','block_coverage':['06A'],
                'scope':'synthetic-development-block06a' if synthetic else 'local-panel-account-test-block06a',
                **{k:owner[k] for k in ('participant_id','study_id','panel_origin')},'search_id':payload['search_id'],
                'results_revision_id':payload['results_revision_id'],'search_metadata_storage_record_id':parent_id,
                'priority_storage_record_id':parent['priority_storage_record_id'],'passport_id':parent['passport_id'],
                'backend_generated_at':payload['backend_generated_at'],'response_received_at':payload['response_received_at'],
                'stored_at':at,'payload_sha256':digest,'payload_bytes':len(raw),'committed':True,
                'study_membership':'not_verified','study_consent':'not_verified','production_authorized':False,'receipt':receipt}
            if len(raw)<=self.inline_bytes and bson_bytes(payload)<=INLINE_BYTES:
                document['payload']=deepcopy(payload);document['payload_storage']={'mode':'inline'}
            else:
                manifest=[]
                for index,start in enumerate(range(0,len(raw),CHUNK_BYTES)):
                    data=raw[start:start+CHUNK_BYTES];part_digest=sha256(data).hexdigest()
                    part_id=sha256(f'{record_id}|{digest}|{index}'.encode()).hexdigest()
                    part={'_id':part_id,'storage_record_id':record_id,'participant_id':owner['participant_id'],
                        'payload_sha256':digest,'index':index,'sha256':part_digest,'bytes':len(data),'data':data}
                    try:
                        if not self.chunks().insert_one(part).acknowledged:raise ProfileStorageUnavailable('chunk_unacknowledged')
                    except Exception as error:
                        if getattr(error,'code',None)!=11000:raise
                        previous=self.chunks().find_one({'_id':part_id})
                        if previous is None or previous['sha256']!=part_digest or bytes(previous['data'])!=data:raise ProfileRevisionConflict('chunk_conflict')
                    manifest.append({k:part[k] for k in ('_id','index','sha256','bytes')})
                document['payload_storage']={'mode':'chunks','encoding':'json-utf8','collection':'search_result_chunks','chunks':manifest}
            try:
                if not target.insert_one(document).acknowledged:raise ProfileStorageUnavailable('results_unacknowledged')
            except Exception as error:
                if getattr(error,'code',None)!=11000:raise
                previous=target.find_one({'_id':record_id})
                if previous is None:raise ProfileStorageUnavailable('results_receipt_unavailable')
                return MongoProfileArchive.existing_receipt(previous,digest)
            return {**receipt,'already_stored':False}
        except (InvalidResultsTransfer,PriorityDependencyPending,ProfileRevisionConflict,ProfileStorageUnavailable):raise
        except Exception:raise ProfileStorageUnavailable('results_storage_unavailable') from None

    def read_payload(self,document):
        if document['payload_storage']['mode']=='inline':return deepcopy(document['payload'])
        parts=[]
        for item in document['payload_storage']['chunks']:
            part=self.chunks().find_one({'_id':item['_id']})
            if part is None:raise ProfileStorageUnavailable('chunk_missing')
            data=bytes(part['data'])
            if len(data)!=item['bytes'] or sha256(data).hexdigest()!=item['sha256']:raise ProfileStorageUnavailable('chunk_corrupt')
            parts.append(data)
        raw=b''.join(parts)
        if sha256(raw).hexdigest()!=document['payload_sha256']:raise ProfileStorageUnavailable('payload_corrupt')
        return json.loads(raw)
