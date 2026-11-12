"""Bounded operational summaries and durable timings, independent of model inputs."""
from datetime import datetime,timezone
from functools import wraps
import json
import os
import math
from statistics import median
from time import perf_counter


def setup(db):
    db.executescript('''
      CREATE TABLE IF NOT EXISTS experiment_operation_metrics(
        sequence INTEGER PRIMARY KEY AUTOINCREMENT,operation TEXT NOT NULL,received_at TEXT NOT NULL,
        seconds REAL NOT NULL,status TEXT NOT NULL,details TEXT NOT NULL);
      CREATE INDEX IF NOT EXISTS operation_metric_kind ON experiment_operation_metrics(operation,sequence);
      CREATE TABLE IF NOT EXISTS experiment_processes(
        id TEXT PRIMARY KEY,role TEXT NOT NULL,pid INTEGER NOT NULL,heartbeat TEXT NOT NULL,value TEXT NOT NULL);
      CREATE INDEX IF NOT EXISTS experiment_queue_root ON experiment_queue(root,status);
      CREATE INDEX IF NOT EXISTS experiment_events_root ON experiment_events(root,confirmed);
      CREATE INDEX IF NOT EXISTS experiment_versions_root ON versions(root);
      CREATE INDEX IF NOT EXISTS experiment_searches_root ON experiment_searches(root);
    ''')


def measured(operation):
    def decorate(function):
        @wraps(function)
        def run(self,*args,**kwargs):
            start=perf_counter();status='error';details={}
            try:
                result=function(self,*args,**kwargs);status='ok'
                if isinstance(result,dict):details={k:result[k] for k in ('status','event_id','search_id','source_passport_id') if k in result}
                return result
            except Exception as exc:
                details={'error_type':type(exc).__name__};raise
            finally:
                if details.get('status')!='idle':
                    try:
                        with self.archive() as archive,archive.db:
                            archive.db.execute('INSERT INTO experiment_operation_metrics(operation,received_at,seconds,status,details) VALUES(?,?,?,?,?)',
                                (operation,datetime.now(timezone.utc).isoformat(),perf_counter()-start,status,json.dumps(details)))
                    except Exception as exc:
                        # Failure to write telemetry must not turn a committed Experience into an apparent failed write.
                        import logging
                        logging.getLogger(__name__).error('Operational metric unavailable: %s',type(exc).__name__)
        return run
    return decorate


def heartbeat(runtime,process_id,role,**details):
    if role not in ('worker','server','supervisor'):raise ValueError('Invalid process role')
    with runtime.archive() as archive,archive.db:
        archive.db.execute('INSERT INTO experiment_processes VALUES(?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET heartbeat=excluded.heartbeat,value=excluded.value',
            (process_id,role,os.getpid(),datetime.now(timezone.utc).isoformat(),json.dumps(details)))


def summary(runtime, *, sample_limit=200):
    if type(sample_limit) is not int or not 1 <= sample_limit <= 200:raise ValueError('Bounded metric sample required')
    with runtime.archive() as archive:
        db=archive.db
        counts={name:db.execute('SELECT COUNT(*) FROM '+name).fetchone()[0] for name in
            ('experiment_profiles','experiment_events','versions','experiment_searches','experiment_monitor_evidence')}
        jobs=dict(db.execute('SELECT status,COUNT(*) FROM experiment_queue GROUP BY status'))
        preparation=dict(db.execute('SELECT status,COUNT(*) FROM experiment_preparation GROUP BY status'))
        metrics={}
        for operation, in db.execute('SELECT DISTINCT operation FROM experiment_operation_metrics'):
            rows=db.execute('SELECT seconds,status FROM experiment_operation_metrics WHERE operation=? ORDER BY sequence DESC LIMIT ?',
                (operation,sample_limit)).fetchall()
            values=sorted(t for t,s in rows if s=='ok')
            metrics[operation]={'sample_count':len(rows),'errors':sum(s!='ok' for _,s in rows),
                'median_seconds':median(values) if values else None,
                'p95_seconds':values[math.ceil(.95*len(values))-1] if values else None,
                'window':'most_recent_operations_not_population_benchmark'}
        processes=[dict(id=i,role=r,pid=p,heartbeat=h,details=json.loads(v))
            for i,r,p,h,v in db.execute('SELECT * FROM experiment_processes ORDER BY heartbeat DESC LIMIT 100')]
        transitions=db.execute('SELECT COUNT(*) FROM experiment_monitor_transitions').fetchone()[0]
    sizes={suffix or 'database':p.stat().st_size if p.exists() else 0
        for suffix in ('','-wal','-shm') for p in [runtime.path if not suffix else runtime.path.with_name(runtime.path.name+suffix)]}
    return {'schema_version':'longitudinal-backend-operations-v1','evidence_scope':runtime.scope,
        'reporting_process_pid':os.getpid(),'cache_measurement':'this_process_only_estimated',
        'experiment_enabled':runtime.enabled,'production_authorized':False,'counts':counts,
        'learning_jobs':jobs,'preparation_jobs':preparation,'timings':metrics,'processes':processes,
        'storage_bytes':sizes,'compiled_cache_bytes_estimated':runtime._compiled_bytes,
        'compiled_cache_entries':len(runtime._compiled),'monitor_transitions':transitions,
        'limits':{'records_per_fit':512,'journeys_per_target_mode_preparation':128,
            'cache_bytes_estimated':256*1024*1024,'cache_entries':4},
        'monitor_policy':runtime.monitor_policy.snapshot()}
