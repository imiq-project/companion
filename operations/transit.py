"""Stage and activate coherent transit releases using the shared lifecycle."""
import csv, hashlib, io, json, os, re, shutil, subprocess, time, zipfile, datetime
from pathlib import Path
from urllib.request import Request,urlopen
from .runtime import active_release, checked_release, verify_release, atomic_json, sha, _activate, state_lock, OTP_SHA256
from infrastructure.service_config import settings,positive_integer
URL='https://download.gtfs.de/germany/nv_free/latest.zip'
OTP_URL='https://github.com/opentripplanner/OpenTripPlanner/releases/download/v2.7.0/otp-shaded-2.7.0.jar'
OSM_URL='https://download.bbbike.org/osm/bbbike/Magdeburg/Magdeburg.osm.pbf'


def download(url, destination, maximum, expected_sha=None):
    """Publish a bounded download only after size/hash verification."""
    destination=Path(destination);destination.parent.mkdir(parents=True,exist_ok=True)
    temp=destination.with_name(destination.name+'.partial')
    try:
        with urlopen(url,timeout=90) as response,temp.open('wb') as stream:
            length=response.headers.get('Content-Length');count=0
            if length is not None and not 0<int(length)<=maximum:raise ValueError('Download exceeds size bound')
            for chunk in iter(lambda:response.read(1048576),b''):
                count+=len(chunk)
                if count>maximum:raise ValueError('Download exceeds size bound')
                stream.write(chunk)
        if not count or (length is not None and count!=int(length)):raise ValueError('Incomplete download')
        if expected_sha and sha(temp)!=expected_sha:raise ValueError('Download hash mismatch')
        temp.replace(destination)
    finally:temp.unlink(missing_ok=True)


def bootstrap(osm=None):
    """Create the first release without starting services or replacing an installation."""
    config=settings()
    with state_lock(config.state/'transit-operation.lock'):
        if config.active_pointer.exists():raise ValueError('Transit already initialized; use data transit sync')
        if not config.java:raise ValueError('Java 21 or newer is required; set COMPANION_JAVA')
        version=subprocess.run([config.java,'-version'],capture_output=True,text=True,check=True,timeout=15)
        match=re.search(r'version "(\d+)',version.stdout+version.stderr)
        if not match or int(match[1])<21:raise ValueError('OpenTripPlanner requires Java 21 or newer')
        if shutil.disk_usage(config.state).free < positive_integer('COMPANION_TRANSIT_MIN_FREE_MB',2048)*1024*1024:
            raise ValueError('Insufficient free disk space for GTFS build')
        if config.otp_jar.exists():
            if sha(config.otp_jar)!=OTP_SHA256:raise ValueError('Unexpected OTP executable identity; existing file left unchanged')
        else:
            print('Downloading verified OpenTripPlanner 2.7.0...',flush=True)
            download(OTP_URL,config.otp_jar,200_000_000,OTP_SHA256)
        seed=config.state/'transit-bootstrap';seed.mkdir(exist_ok=True)
        if osm is not None:
            osm=Path(osm).resolve()
            if not osm.is_file() or not 0<osm.stat().st_size<=500_000_000:raise ValueError('OSM PBF must be a nonempty file <=500 MB covering Magdeburg')
            if osm!=(seed/'Magdeburg.osm.pbf').resolve():shutil.copy2(osm,seed/'Magdeburg.osm.pbf')
        else:
            print('Downloading the public Magdeburg OSM extract...',flush=True)
            download(OSM_URL,seed/'Magdeburg.osm.pbf',500_000_000)
        atomic_json(seed/'build-config.json',{'transitFeeds':[{'type':'gtfs','feedId':'gtfsde','source':'mvb.gtfs.zip'}],
                                            'osm':[{'source':'Magdeburg.osm.pbf'}]})
        atomic_json(seed/'router-config.json',{'updaters':[{'type':kind,'feedId':'gtfsde',
            'url':f'http://127.0.0.1:{config.realtime_port}/feed.pb','frequency':'30s','fuzzyTripMatching':False}
            for kind in ('stop-time-updater','real-time-alerts')]})
        release=_stage(config,seed=seed)
        ready=json.loads((release/'ready.json').read_text(encoding='utf-8'))
        pointer={'release':release.name,'source':ready['source'],
                 'initialized_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),
                 'calendar_coverage':calendar_coverage(release),'activation_status':'awaiting_first_start',
                 'readiness_policy':'scheduled-route-required-rt-independent-v1'}
        atomic_json(config.active_pointer,pointer)
        print('Transit initialized. Start services and run check --services; route readiness has not yet been probed.',flush=True)
        return pointer


def remote_version():
    with urlopen(Request(URL,method='HEAD'),timeout=30) as response:
        return {'url':URL,'etag':response.headers.get('ETag'),'last_modified':response.headers.get('Last-Modified'),
                'size':int(response.headers['Content-Length'])}


def stage():
    config=settings()
    with state_lock(config.state/'transit-operation.lock'):
        return _stage(config)


def _stage(config,seed=None):
    from .transit_runtime.gtfs_subset import subset
    if seed is None:
        active=active_release(config);verify_release(active,config)
        inputs=active/'graph'
    else:inputs=Path(seed)
    source=remote_version()
    if not 0<source['size']<=1_000_000_000:raise ValueError('GTFS source exceeds bound')
    release=config.releases/hashlib.sha256(json.dumps(source,sort_keys=True).encode()).hexdigest()[:16]
    graph=release/'graph';graph.mkdir(parents=True,exist_ok=True)
    if shutil.disk_usage(config.state).free < positive_integer('COMPANION_TRANSIT_MIN_FREE_MB',2048)*1024*1024:
        raise ValueError('Insufficient free disk space for GTFS build')
    national=release/'national.gtfs.zip'
    if not national.exists() and not (release/'ready.json').exists():
        temp=release/'national.partial';headers={'If-Match':source['etag']} if source['etag'] else {}
        with urlopen(Request(URL,headers=headers),timeout=90) as response, temp.open('wb') as stream:
            count=0
            for chunk in iter(lambda:response.read(1048576),b''):
                count+=len(chunk)
                if count>source['size']:raise ValueError('GTFS download exceeds declared size')
                stream.write(chunk)
        if count!=source['size']:raise ValueError('Incomplete national GTFS')
        temp.replace(national)
    if not (release/'ready.json').exists():
        if national.stat().st_size!=source['size']:raise ValueError('Cached national GTFS size mismatch')
        subset(national,graph/'mvb.gtfs.zip')
        manifest_path=graph/'mvb.gtfs.manifest.json'
        manifest=json.loads(manifest_path.read_text(encoding='utf-8'))
        with zipfile.ZipFile(graph/'mvb.gtfs.zip') as archive:
            def rows(name):return csv.DictReader(io.TextIOWrapper(archive.open(name),encoding='utf-8-sig'))
            index={r['trip_id']:{'route_id':r['route_id'],'service_id':r['service_id'],'stops':{}} for r in rows('trips.txt')}
            for row in rows('stop_times.txt'):
                index[row['trip_id']]['stops'][row['stop_sequence']]=[row['stop_id'],row['arrival_time'],row['departure_time']]
        manifest.update(schema_version='mvb-gtfs-identity-v2',static_dataset_sha256=sha(graph/'mvb.gtfs.zip'),
            source={**source,'national_sha256':sha(national)},trip_index=index,
            compatibility_policy={'reject_unknown_trip_stop_pairs':True,'max_invalid_trip_fraction':.1})
        atomic_json(manifest_path,manifest)
        for name in ('Magdeburg.osm.pbf','build-config.json','router-config.json'):
            shutil.copy2(inputs/name,graph/name)
        router=json.loads((graph/'router-config.json').read_text())
        for updater in router.get('updaters',[]):
            if updater.get('type') in ('stop-time-updater','real-time-alerts'):updater['frequency']='30s'
        atomic_json(graph/'router-config.json',router)
        print('Building isolated OTP graph',flush=True)
        with (release/'build.log').open('w',encoding='utf-8') as log:
            subprocess.run([config.java,'-Xmx'+os.environ.get('COMPANION_OTP_BUILD_HEAP','4G'),'-jar',str(config.otp_jar),'--build','--save',str(graph)],stdout=log,stderr=subprocess.STDOUT,check=True,timeout=positive_integer('COMPANION_TRANSIT_BUILD_TIMEOUT',1800))
        names=('mvb.gtfs.zip','mvb.gtfs.manifest.json','Magdeburg.osm.pbf','build-config.json','router-config.json','graph.obj')
        state={name:sha(graph/name) for name in names}
        atomic_json(release/'build-state.json',state)
        atomic_json(release/'ready.json',{'release':release.name,'source':source,'state':state})
    verify_release(release,config)
    calendar_coverage(release)
    if remote_version()!=source:raise ValueError('Static source changed during build; current release retained')
    atomic_json(config.state/'transit-staged.json',{'release':release.name})
    national.unlink(missing_ok=True)
    print('Release staged: '+str(release),flush=True)
    return release


def deploy(release):
    config=settings()
    with state_lock(config.state/'transit-operation.lock'):
        return _deploy(release,config)


def _deploy(release,config):
    release=resolve_release(release,config)
    ready=json.loads((release/'ready.json').read_text(encoding='utf-8-sig'))
    if ready['source']!=remote_version():raise ValueError('Staged static GTFS superseded; rebuild current source')
    return _activate(release,config)


def resolve_release(release,config):
    path=Path(release)
    return checked_release(path if path.is_absolute() else config.releases/path,config)


def calendar_coverage(release,days=7,today=None):
    """Count effective service IDs on each day, including removal exceptions."""
    from zoneinfo import ZoneInfo
    today=today or datetime.datetime.now(ZoneInfo('Europe/Berlin')).date()
    with zipfile.ZipFile(Path(release)/'graph/mvb.gtfs.zip') as archive:
        def rows(name):
            return list(csv.DictReader(io.TextIOWrapper(archive.open(name),encoding='utf-8-sig'))) if name in archive.namelist() else []
        services={r['service_id'] for r in rows('trips.txt')}
        calendars=rows('calendar.txt');exceptions=rows('calendar_dates.txt')
    coverage={}
    for offset in range(days):
        day=today+datetime.timedelta(days=offset);key=day.strftime('%Y%m%d')
        weekday=('monday','tuesday','wednesday','thursday','friday','saturday','sunday')[day.weekday()]
        active={r['service_id'] for r in calendars if r['start_date']<=key<=r['end_date'] and r[weekday]=='1'}
        for row in exceptions:
            if row['date']==key:
                if row['exception_type']=='1':active.add(row['service_id'])
                elif row['exception_type']=='2':active.discard(row['service_id'])
        coverage[day.isoformat()]=len(active & services)
    if not any(coverage.values()):raise ValueError('GTFS has no effective service in the next seven days')
    return coverage


def cleanup_releases(config):
    """Retain active, rollback, staged and two newest other generations."""
    pointer=json.loads(config.active_pointer.read_text(encoding='utf-8-sig'))
    protected={active_release(config)}
    if pointer.get('previous_release'):protected.add(resolve_release(pointer['previous_release'],config))
    staged=config.state/'transit-staged.json'
    if staged.exists():protected.add(resolve_release(json.loads(staged.read_text())['release'],config))
    candidates=[]
    for path in config.releases.iterdir():
        if path.is_symlink() or getattr(path,'is_junction',lambda:False)():continue
        if path.is_dir() and path not in protected:
            candidates.append(checked_release(path,config))
    removed=[]
    for path in sorted(candidates,key=lambda p:p.stat().st_mtime,reverse=True)[2:]:
        # Refuse any tree with redirections before recursive removal on Windows.
        if any(p.is_symlink() or getattr(p,'is_junction',lambda:False)() for p in path.rglob('*')):continue
        checked_release(path,config)
        shutil.rmtree(path);removed.append(path.name)
    return removed


def sync():
    config=settings()
    with state_lock(config.state/'transit-operation.lock'):
        status_path=config.state/'transit-sync.json'
        previous=json.loads(status_path.read_text()) if status_path.exists() else {}
        now=time.time();record={'checked_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'active_release':active_release(config).name,'failures':previous.get('failures',0)}
        try:
            source=remote_version();record['available_source']=source
            current=json.loads(config.active_pointer.read_text(encoding='utf-8-sig'))
            if current.get('source')!=source and previous.get('available_source')==source and now<previous.get('next_attempt_epoch',0):
                deferred={**previous,'checked_at':record['checked_at'],'active_release':record['active_release'],
                    'available_source':source,'status':'retry_deferred'}
                atomic_json(status_path,deferred)
                return deferred
            if current.get('source')!=source:
                _deploy(_stage(config),config);record['status']='updated'
            else:
                verify_release(active_release(config),config);record['status']='unchanged'
            record.update(active_release=active_release(config).name,calendar_coverage=calendar_coverage(active_release(config)),
                failures=0,last_success_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),next_attempt_epoch=time.time()+600)
            record['alerts']=[]
            if not all(record['calendar_coverage'].values()):record['alerts'].append('Some dates in next seven days have no effective service')
            try:
                with urlopen(f'http://127.0.0.1:{config.realtime_port}/status',timeout=3) as response:rt=json.load(response)
                record['realtime_ready']=rt.get('ready') is True and rt.get('dataset_compatible') is True
            except (OSError,ValueError):record['realtime_ready']=False
            if not record['realtime_ready']:record['alerts'].append('Realtime unavailable; use scheduled routes')
            record['removed_releases']=cleanup_releases(config)
        except Exception as exc:
            failures=previous.get('failures',0)+1
            record.update(status='failed',failures=failures,error=type(exc).__name__+': '+str(exc),
                last_success_at=previous.get('last_success_at'),next_attempt_epoch=time.time()+min(21600,600*2**min(failures-1,6)),
                alerts=['GTFS synchronization failed; check active schedule validity'])
            atomic_json(status_path,record)
            raise
        atomic_json(status_path,record)
        return record


def watch():
    config=settings()
    with state_lock(config.state/'transit-watch.lock'):
        atomic_json(config.state/'transit-sync.pid.json',{'pid':os.getpid()})
        while True:
            try:
                print(json.dumps(sync()),flush=True)
            except Exception as exc:print('Sync failed; current scheduled dataset retained: '+type(exc).__name__,flush=True)
            time.sleep(600)
