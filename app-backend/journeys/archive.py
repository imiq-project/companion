"""Durable immutable journey snapshots and read-only archive diagnostics."""
from datetime import datetime, timezone
from contextlib import closing
from hashlib import sha256
from pathlib import Path
import gzip
import json
import os
import sqlite3
import time
from .contract import evidence_id
from infrastructure.service_config import settings


class JourneyIdentityConflict(ValueError):pass


def storage_configuration():
    config=settings()
    value=os.environ.get('COMPANION_JOURNEY_STORAGE_ENABLED','1')
    if value not in ('0','1'):raise ValueError('COMPANION_JOURNEY_STORAGE_ENABLED must be 0 or 1')
    return value=='1',Path(os.environ.get('COMPANION_JOURNEY_DB',config.state/'user-data/journeys.sqlite3')).resolve()


def canonical(value):
    return json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=False,allow_nan=False).encode('utf-8')


def archive_journey(path,record,journey):
    """Immutable snapshots, compressed payloads and shared search evidence."""
    record_raw=canonical(record);journey_raw=canonical(journey)
    record_digest=sha256(record_raw).hexdigest();digest=evidence_id(journey)
    revision=sha256(canonical([journey['journey_id'],digest,record_digest])).hexdigest()
    identity=sha256(canonical({k:journey[k] for k in ('journey_id','search_id','choice_event_id',
        'choice_index','selection','passport_reference','created_at','started_at','capture_mode')})).hexdigest()
    compressed_record=gzip.compress(record_raw,compresslevel=6,mtime=0)
    compressed_journey=gzip.compress(journey_raw,compresslevel=6,mtime=0)
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
    with closing(sqlite3.connect(path,timeout=10)) as db:
        db.execute('PRAGMA foreign_keys=ON')
        # Two workers may create a new DB together; journal-mode changes can
        # report SQLITE_BUSY immediately even with a configured busy timeout.
        for attempt in range(6):
            try:
                if db.execute('PRAGMA journal_mode').fetchone()[0].lower()!='wal':
                    db.execute('PRAGMA journal_mode=WAL')
                break
            except sqlite3.OperationalError as exc:
                if getattr(exc,'sqlite_errorcode',0) not in (sqlite3.SQLITE_BUSY,sqlite3.SQLITE_LOCKED) or attempt==5:raise
                time.sleep(.05*2**attempt)
        db.execute('PRAGMA synchronous=FULL')
        with db:
            db.execute('BEGIN IMMEDIATE')
            db.execute('CREATE TABLE IF NOT EXISTS journey_identity (journey_id TEXT PRIMARY KEY, search_id TEXT NOT NULL, identity_sha256 TEXT NOT NULL)')
            db.execute('CREATE TABLE IF NOT EXISTS search_records (digest TEXT PRIMARY KEY, payload_gzip BLOB NOT NULL)')
            db.execute('''CREATE TABLE IF NOT EXISTS journey_snapshots (
                revision_id TEXT PRIMARY KEY, journey_id TEXT NOT NULL REFERENCES journey_identity(journey_id),
                evidence_id TEXT NOT NULL, record_digest TEXT NOT NULL REFERENCES search_records(digest),
                payload_gzip BLOB NOT NULL, gps_points INTEGER NOT NULL, status TEXT NOT NULL, received_at TEXT NOT NULL)''')
            db.execute('CREATE INDEX IF NOT EXISTS journey_snapshots_by_journey ON journey_snapshots(journey_id,received_at)')
            existing=db.execute('SELECT identity_sha256 FROM journey_identity WHERE journey_id=?',(journey['journey_id'],)).fetchone()
            if existing and existing[0]!=identity:raise JourneyIdentityConflict('Journey ID already belongs to different evidence')
            db.execute('INSERT OR IGNORE INTO journey_identity VALUES(?,?,?)',(journey['journey_id'],journey['search_id'],identity))
            db.execute('INSERT OR IGNORE INTO search_records VALUES(?,?)',(record_digest,compressed_record))
            previous=db.execute('SELECT received_at FROM journey_snapshots WHERE revision_id=?',(revision,)).fetchone()
            saved_at=previous[0] if previous else datetime.now(timezone.utc).isoformat()
            if not previous:
                db.execute('INSERT INTO journey_snapshots VALUES(?,?,?,?,?,?,?,?)',
                    (revision,journey['journey_id'],digest,record_digest,compressed_journey,len(journey['tracking']['points']),journey['status'],saved_at))
        # Receipt returned only after the transaction commits successfully.
        return {'stored_on_server':True,'storage_schema_version':'companion-journey-archive-v1',
                'storage_revision_id':revision,'stored_at':saved_at,'already_stored':previous is not None,
                'stored_gps_points':len(journey['tracking']['points'])}


def storage_status(path=None):
    enabled,configured=storage_configuration();path=Path(path or configured)
    result={'enabled':enabled,'path':str(path),'exists':path.is_file(),'journey_count':0,'snapshot_count':0,'search_record_count':0}
    if not path.is_file():return result
    with closing(sqlite3.connect(path.resolve().as_uri()+'?mode=ro',uri=True,timeout=10)) as db:
        for key,table in (('journey_count','journey_identity'),('snapshot_count','journey_snapshots'),('search_record_count','search_records')):
            result[key]=db.execute('SELECT COUNT(*) FROM '+table).fetchone()[0]
        result['integrity']=db.execute('PRAGMA quick_check').fetchone()[0]
    result['database_bytes']=path.stat().st_size
    return result


