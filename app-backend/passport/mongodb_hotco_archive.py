"""Atomic HOTCO manifests with owned, lossless deduplicated scientific artifacts."""
import base64
from copy import deepcopy
from datetime import datetime,timezone
import json
from passport.hotco_transfer import InvalidHotcoTransfer,require,digest,canonical,unpack,input_digest
from passport.mongodb_profile_archive import MongoProfileArchive,ProfileRevisionConflict,ProfileStorageUnavailable
from passport.mongodb_priority_archive import PriorityDependencyPending
from passport.mongodb_results_archive import MongoResultsArchive,bson_bytes
from model.input_mapping_v4_3 import MODES

BLOB_INLINE_BYTES=8*1024*1024

class MongoHotcoArchive:
    def __init__(self,*,results_archive=None,collection=None,artifacts=None,chunks=None):
        self.results_archive=results_archive;self._collection=collection;self._artifacts=artifacts;self._chunks=chunks
    def named(self,name):
        parent=self.results_archive.collection()
        return parent.database[name].with_options(write_concern=parent.write_concern,read_concern=parent.read_concern,read_preference=parent.read_preference)
    def collection(self):
        if self._collection is None:self._collection=self.named('hotco_simulations')
        return self._collection
    def artifacts(self,kind):return self._artifacts[kind] if self._artifacts is not None else self.named('hotco_trajectories' if kind=='trajectory' else 'hotco_normalizations')
    def chunks(self):
        if self._chunks is None:self._chunks=self.named('hotco_blob_chunks')
        return self._chunks
    @staticmethod
    def identity(logical,owner,data_class='participant',kind='search'):
        return digest(canonical(['companion-block06b-v1',data_class,owner['participant_id'],owner['panel_origin'],owner['study_id'],kind,logical]))

    def save_artifact(self,artifact,owner,data_class):
        kind=artifact['kind'];require(kind in ('trajectory','normalization'))
        blob=artifact['blob'];require(artifact['artifact_id']==kind+'-'+blob['raw_sha256'])
        target=self.artifacts(kind);identifier=self.identity(blob['raw_sha256'],owner,data_class,kind)
        previous=target.find_one({'_id':identifier})
        if previous is not None:
            require(previous['raw_sha256']==blob['raw_sha256'] and previous['raw_bytes']==blob['raw_bytes'])
            return {'artifact_id':artifact['artifact_id'],'storage_record_id':identifier,'kind':kind}
        # Verify the entire original JSON before publishing compressed bytes.
        value=unpack(blob)
        if kind=='trajectory':require(isinstance(value,list) and all(isinstance(row,list) and len(row)==13 for row in value))
        else:require(set(value)=={'normalized_stressors','mode_specific_stressors','contextual_data_completeness','segments'})
        data=base64.b64decode(blob['data_base64'],validate=True)
        document={'_id':identifier,'schema_version':'companion-hotco-artifact-v1','kind':kind,'data_class':data_class,
            **{k:owner[k] for k in ('participant_id','study_id','panel_origin')},'codec':blob['codec'],'raw_sha256':blob['raw_sha256'],
            'compressed_sha256':blob['compressed_sha256'],'raw_bytes':blob['raw_bytes'],'compressed_bytes':len(data)}
        if len(data)<=BLOB_INLINE_BYTES:document.update(storage_mode='inline',data=data)
        else:
            manifest=[]
            for index,start in enumerate(range(0,len(data),1024*1024)):
                part=data[start:start+1024*1024];part_id=digest(f'{identifier}|{blob["compressed_sha256"]}|{index}'.encode())
                entry={'_id':part_id,'artifact_storage_record_id':identifier,'index':index,'sha256':digest(part),'data':part}
                try:
                    if not self.chunks().insert_one(entry).acknowledged:raise ProfileStorageUnavailable('hotco_chunk_unacknowledged')
                except Exception as error:
                    if getattr(error,'code',None)!=11000:raise
                    old=self.chunks().find_one({'_id':part_id});require(old is not None and bytes(old['data'])==part)
                manifest.append({'_id':part_id,'sha256':entry['sha256'],'index':index})
            document.update(storage_mode='chunks',chunks=manifest)
        try:
            if not target.insert_one(document).acknowledged:raise ProfileStorageUnavailable('hotco_artifact_unacknowledged')
        except Exception as error:
            if getattr(error,'code',None)!=11000:raise
            old=target.find_one({'_id':identifier});require(old is not None and old['raw_sha256']==blob['raw_sha256'])
        return {'artifact_id':artifact['artifact_id'],'storage_record_id':identifier,'kind':kind}

    def store(self,wire,evidence,raw,*,owner):
        try:
            synthetic=wire['data_class']=='synthetic'
            require(wire['data_class'] in ('synthetic','participant') and owner.get('test_scope')==('synthetic-block06b' if synthetic else 'local-panel-account-test'))
            require(not synthetic or owner['participant_id'].startswith('synthetic-'))
            require(owner['study_id']=='app_imiq_test' and owner['panel_origin']=='https://imiq-staging.et.uni-magdeburg.de')
            target=self.collection();identifier=self.identity(wire['search_id'],owner,wire['data_class']);payload_hash=digest(raw)
            previous=target.find_one({'_id':identifier})
            if previous is not None:return MongoProfileArchive.existing_receipt(previous,payload_hash)
            parent_id=MongoResultsArchive.identity(wire['search_id'],owner,wire['data_class'])
            parent=self.results_archive.collection().find_one({'_id':parent_id})
            if parent is None:raise PriorityDependencyPending('hotco_results_not_stored')
            require(all(parent.get(k)==owner[k] for k in ('participant_id','study_id','panel_origin')))
            require(parent['results_revision_id']==wire['results_reference']['results_revision_id'])
            payload=self.results_archive.read_payload(parent)
            require(payload['search_id']==wire['search_id'] and payload['app_version']==wire['app_version'])
            priority=self.results_archive.search_archive.collection().find_one({'_id':parent['priority_storage_record_id']})
            if priority is None:raise PriorityDependencyPending('hotco_priority_not_stored')
            require(all(priority.get(k)==owner[k] for k in ('participant_id','study_id','panel_origin')))
            require(wire['passport_input_sha256']==input_digest(priority['payload']['contextual_passport_snapshot']))
            require([r['route_id'] for r in evidence['route_runs']]==[r['route_id'] for r in payload['candidate_routes']])
            for config in evidence['configurations']:
                require(config['availability']==[int(payload['journey_availability'][m]) for m in MODES])
            normalized=deepcopy({k:v for k,v in evidence.items() if k!='artifacts'})
            normalized['artifact_manifest']=[self.save_artifact(a,owner,wire['data_class']) for a in evidence['artifacts']]
            require(bson_bytes(normalized)<=8*1024*1024)
            at=datetime.now(timezone.utc).isoformat()
            receipt={'schema_version':'companion-hotco-storage-receipt-v1','storage_record_id':identifier,'hotco_revision_id':wire['hotco_revision_id'],
                'search_id':wire['search_id'],'results_storage_record_id':parent_id,'payload_sha256':payload_hash,'evidence_sha256':wire['packet']['raw_sha256'],
                'stored_at':at,'stored_on_server':True,'learning_applied':False,'data_class':wire['data_class'],'collection':'hotco_simulations',
                **{k:owner[k] for k in ('participant_id','study_id','panel_origin','test_scope')}}
            document={'_id':identifier,'schema_version':'companion-hotco-archive-v1','record_type':'hotco_simulation','block_coverage':['06B'],
                'scope':'synthetic-development-block06b' if synthetic else 'local-panel-account-test-block06b',
                **{k:owner[k] for k in ('participant_id','study_id','panel_origin')},'search_id':wire['search_id'],'hotco_revision_id':wire['hotco_revision_id'],
                'results_storage_record_id':parent_id,'search_metadata_storage_record_id':parent['search_metadata_storage_record_id'],
                'priority_storage_record_id':parent['priority_storage_record_id'],'passport_id':parent['passport_id'],
                'backend_generated_at':wire['backend_generated_at'],'response_received_at':wire['response_received_at'],
                'payload_sha256':payload_hash,'evidence_sha256':wire['packet']['raw_sha256'],'backend_packet_verified':True,'signing_key_id':wire['key_id'],
                'study_membership':'not_verified','study_consent':'not_verified','production_authorized':False,
                'committed':True,'stored_at':at,'evidence':normalized,'receipt':receipt}
            try:
                if not target.insert_one(document).acknowledged:raise ProfileStorageUnavailable('hotco_manifest_unacknowledged')
            except Exception as error:
                if getattr(error,'code',None)!=11000:raise
                old=target.find_one({'_id':identifier})
                if old is None:raise ProfileStorageUnavailable('hotco_receipt_unavailable')
                return MongoProfileArchive.existing_receipt(old,payload_hash)
            return {**receipt,'already_stored':False}
        except (InvalidHotcoTransfer,PriorityDependencyPending,ProfileRevisionConflict,ProfileStorageUnavailable):raise
        except Exception:raise ProfileStorageUnavailable('hotco_storage_unavailable') from None

    def read_artifact(self,entry):
        value=self.artifacts(entry['kind']).find_one({'_id':entry['storage_record_id']})
        if value is None:raise ProfileStorageUnavailable('hotco_artifact_missing')
        if value['storage_mode']=='inline':data=bytes(value['data'])
        else:
            parts=[]
            for p in value['chunks']:
                part=self.chunks().find_one({'_id':p['_id']})
                if part is None or digest(bytes(part['data']))!=p['sha256']:raise ProfileStorageUnavailable('hotco_chunk_corrupt')
                parts.append(bytes(part['data']))
            data=b''.join(parts)
        return unpack({k:value[k] for k in ('codec','raw_sha256','compressed_sha256','raw_bytes','compressed_bytes')}|{'data_base64':base64.b64encode(data).decode('ascii')})
