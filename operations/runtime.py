"""One release-based lifecycle for API, OTP and realtime, with checked ownership."""
from datetime import datetime, timezone
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time
import uuid
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
BACKEND = ROOT/'app-backend'
sys.path.insert(0,str(BACKEND))
from infrastructure.service_config import settings

GRAPH_FILES = frozenset(('mvb.gtfs.zip','mvb.gtfs.manifest.json','Magdeburg.osm.pbf','build-config.json','router-config.json','graph.obj'))
OTP_SHA256 = '72b48c8b8e71803fd48e4596c14d73b06c38a1ec0610f673efc86790331fa42f'


@contextmanager
def state_lock(path):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    with path.open('a+b') as lock:
        lock.seek(0);lock.write(b'0');lock.flush();lock.seek(0)
        if os.name=='nt':
            import msvcrt
            msvcrt.locking(lock.fileno(),msvcrt.LK_NBLCK,1)
        else:
            import fcntl
            fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        try:yield
        finally:
            if os.name=='nt':
                lock.seek(0);msvcrt.locking(lock.fileno(),msvcrt.LK_UNLCK,1)
            else:fcntl.flock(lock,fcntl.LOCK_UN)


def sha(path):
    digest=hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda:stream.read(1024*1024),b''):digest.update(chunk)
    return digest.hexdigest()


def atomic_json(path, value):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    temp=path.with_name(path.name+'.'+uuid.uuid4().hex+'.tmp')
    try:
        temp.write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8')
        temp.replace(path)
    finally:
        temp.unlink(missing_ok=True)


def active_release(config=None):
    config=config or settings()
    value=json.loads(config.active_pointer.read_text(encoding='utf-8-sig'))
    path=Path(value['release'])
    return checked_release(path if path.is_absolute() else config.releases/path, config)


def checked_release(path, config=None):
    config=config or settings();path=Path(path).resolve()
    if not path.is_relative_to(config.releases.resolve()) or path==config.releases.resolve():
        raise ValueError('Release must be inside the configured transit-releases directory')
    return path


def verify_release(release, config=None):
    config=config or settings();release=checked_release(release,config)
    state=json.loads((release/'build-state.json').read_text(encoding='utf-8-sig'))
    if set(state)!=GRAPH_FILES:raise ValueError('Transit build manifest must name exactly the six graph inputs')
    for name,digest in state.items():
        path=(release/'graph'/name).resolve()
        if not path.is_relative_to(release) or sha(path)!=str(digest).lower():
            raise ValueError('Transit input hash mismatch: '+name)
    manifest=json.loads((release/'graph/mvb.gtfs.manifest.json').read_text(encoding='utf-8-sig'))
    if manifest.get('schema_version')!='mvb-gtfs-identity-v2' or manifest.get('static_dataset_sha256')!=state['mvb.gtfs.zip'].lower():
        raise ValueError('Graph and static identity manifest differ')
    if sha(config.otp_jar)!=OTP_SHA256:raise ValueError('Unexpected OTP executable identity')
    if not config.java or not (Path(config.java).is_file() or shutil.which(config.java)):
        raise ValueError('Java executable missing; set COMPANION_JAVA')
    return state


def command(role, release, config=None):
    config=config or settings()
    if role=='backend':return [sys.executable,str(BACKEND/'main.py')]
    release=checked_release(release,config)
    return {
        'backend':[sys.executable,str(BACKEND/'main.py')],
        'otp':[config.java,'-Xmx'+os.environ.get('COMPANION_OTP_HEAP','2G'),'-jar',str(config.otp_jar),'--load','--serve','--port',str(config.otp_port),str(release/'graph')],
        'realtime':[sys.executable,str(ROOT/'operations/transit_runtime/rt_proxy.py'),'--root',str(release),'--port',str(config.realtime_port)],
    }[role]


def split_windows_command(value):
    import ctypes
    from ctypes import wintypes
    count=ctypes.c_int()
    parse=ctypes.windll.shell32.CommandLineToArgvW
    parse.argtypes=[wintypes.LPCWSTR,ctypes.POINTER(ctypes.c_int)]
    parse.restype=ctypes.POINTER(wintypes.LPWSTR)
    result=parse(value,ctypes.byref(count))
    if not result:raise ValueError('Cannot parse process command line')
    try:return [result[i] for i in range(count.value)]
    finally:ctypes.windll.kernel32.LocalFree(ctypes.cast(result,ctypes.c_void_p))


def listeners(ports):
    if os.name=='nt':
        code="$ErrorActionPreference='Stop'; $taskRows=@(); foreach($taskPort in @("+','.join(str(int(p)) for p in ports)+")){foreach($taskConn in @(Get-NetTCPConnection -LocalPort $taskPort -State Listen -ErrorAction SilentlyContinue)){ $taskProc=Get-CimInstance Win32_Process -Filter ('ProcessId='+$taskConn.OwningProcess); $taskRows+=@{pid=$taskProc.ProcessId;command=$taskProc.CommandLine;port=$taskPort}}}; ConvertTo-Json -InputObject @($taskRows) -Compress"
        result=subprocess.run(['pwsh','-NoProfile','-Command',code],check=True,capture_output=True,text=True)
        return [{'pid':r['pid'],'port':r['port'],'args':split_windows_command(r['command'] or '')} for r in json.loads(result.stdout or '[]')]
    inodes={}
    for table in ('/proc/net/tcp','/proc/net/tcp6'):
        for line in Path(table).read_text().splitlines()[1:]:
            fields=line.split();port=int(fields[1].split(':')[1],16)
            if fields[3]=='0A' and port in ports:inodes[fields[9]]=port
    rows=[]
    for path in Path('/proc').iterdir():
        if not path.name.isdigit():continue
        try:
            for fd in (path/'fd').iterdir():
                target=os.readlink(fd)
                if target.startswith('socket:[') and target[8:-1] in inodes:
                    args=(path/'cmdline').read_bytes().decode().strip('\0').split('\0')
                    rows.append({'pid':int(path.name),'port':inodes[target[8:-1]],'args':args})
        except (OSError,UnicodeError):continue
    return rows


def occupied(port):
    with socket.socket() as sock:
        sock.settimeout(.3)
        return sock.connect_ex(('127.0.0.1',port))==0


def owned(rows, roles, release, config=None):
    config=config or settings()
    ports={'backend':config.backend_port,'otp':config.otp_port,'realtime':config.realtime_port}
    result=[]
    for row in rows:
        role=next((role for role in roles if ports[role]==row['port']),None)
        if not role:continue
        args=row['args'];expected=command(role,release,config)
        def contains_path(target):
            return any(Path(a).is_absolute() and os.path.normcase(str(Path(a).resolve()))==os.path.normcase(str(Path(target).resolve())) for a in args)
        valid=(contains_path(expected[1]) if role=='backend' else
               contains_path(config.otp_jar) and contains_path(release/'graph') if role=='otp' else
               contains_path(ROOT/'operations/transit_runtime/rt_proxy.py') and contains_path(release))
        if not valid:raise ValueError(f'Unexpected listener on port {row["port"]}; left running')
        result.append({**row,'role':role})
    return result


def status(config=None):
    config=config or settings()
    ports={'backend':config.backend_port,'otp':config.otp_port,'realtime':config.realtime_port}
    return {role:{'port':port,'listening':occupied(port)} for role,port in ports.items()}


def stop_rows(rows):
    for pid in sorted({int(r['pid']) for r in rows}):
        if os.name=='nt':subprocess.run(['taskkill','/PID',str(pid),'/F'],check=True,capture_output=True)
        else:
            import signal
            os.kill(pid,signal.SIGTERM)
    for _ in range(100):
        if not any(occupied(row['port']) for row in rows):return
        time.sleep(.1)
    raise RuntimeError('Services did not release their ports')


def systemd(action,roles):
    if os.name=='nt':raise ValueError('Systemd service management requires Linux')
    units={'backend':'companion-backend.service','otp':'companion-otp.service','realtime':'companion-realtime.service'}
    prefix=['sudo','-n','/usr/bin/systemctl'] if os.environ.get('COMPANION_SYSTEMCTL_SUDO')=='1' else ['systemctl']
    subprocess.run([*prefix,action,*[units[role] for role in roles]],check=True,timeout=120)


def start(roles=('realtime','otp','backend'), *, release=None, config=None, restart=False):
    config=config or settings()
    with state_lock(config.state/'services.lock'):
        return _start(roles,release=release,config=config,restart=restart)


def stop(roles=('backend','realtime','otp'),config=None):
    config=config or settings()
    with state_lock(config.state/'services.lock'):
        if os.environ.get('COMPANION_SERVICE_MANAGER','processes')=='systemd':
            systemd('stop',roles);return [{'role':role,'state':'supervisor_stop_requested'} for role in roles]
        release=None if set(roles)=={'backend'} else active_release(config)
        ports={'backend':config.backend_port,'otp':config.otp_port,'realtime':config.realtime_port}
        rows=owned(listeners([ports[role] for role in roles]),roles,release,config)
        for role in roles:
            if occupied(ports[role]) and not any(row['role']==role for row in rows):
                raise ValueError('Unverified listener; no services stopped')
        stop_rows(rows)
        return [{'role':role,'state':'stopped_or_absent'} for role in roles]


def _start(roles, *, release=None, config=None, restart=False):
    config=config or settings()
    if set(roles)!={'backend'}:
        release=release or active_release(config)
        verify_release(release,config)
    if os.environ.get('COMPANION_SERVICE_MANAGER','processes')=='systemd':
        systemd('restart' if restart else 'start',roles)
        return [{'role':role,'state':'supervisor_requested'} for role in roles]
    ports={'backend':config.backend_port,'otp':config.otp_port,'realtime':config.realtime_port}
    rows=owned(listeners([ports[r] for r in roles]),roles,release,config)
    # Check every port before starting or stopping any process.
    for role in roles:
        if occupied(ports[role]) and not any(r['role']==role for r in rows):
            raise ValueError('Unverified listener; services left unchanged')
    if restart:stop_rows(rows)
    config.state.mkdir(parents=True,exist_ok=True)
    logs=config.state/'services';logs.mkdir(exist_ok=True)
    stamp=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    result=[]
    for role in roles:
        if occupied(ports[role]):
            result.append({'role':role,'state':'already_listening'});continue
        env=os.environ.copy();env.update(PORT=str(config.backend_port),FLASK_DEBUG='0',OPENBLAS_NUM_THREADS='1',OMP_NUM_THREADS='1')
        with (logs/f'{role}-{stamp}.out.log').open('wb') as out, (logs/f'{role}-{stamp}.err.log').open('wb') as err:
            process=subprocess.Popen(command(role,release,config),cwd=ROOT,env=env,stdout=out,stderr=err,
                creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0),start_new_session=os.name!='nt')
        result.append({'role':role,'pid':process.pid,'state':'started'})
    return result


def wait_transit(timeout=90, config=None):
    config=config or settings();deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        try:
            with urlopen(f'http://127.0.0.1:{config.otp_port}/',timeout=2) as response:
                otp=response.status==200
            if otp:
                # Explicitly ignore RT, including any updates cached by OTP.
                from routing.otp_transit import QUERY,plan_of
                from urllib.request import Request
                from zoneinfo import ZoneInfo
                when=datetime.now(ZoneInfo('Europe/Berlin')).replace(hour=12,minute=0,second=0,microsecond=0)
                payload={'query':QUERY,'variables':{'from':{'lat':52.140,'lon':11.640},
                    'to':{'lat':52.105,'lon':11.620},'date':when.strftime('%Y-%m-%d'),
                    'time':when.strftime('%H:%M:%S'),'ignore':True}}
                request=Request(f'http://127.0.0.1:{config.otp_port}/otp/routers/default/index/graphql',
                    data=json.dumps(payload).encode(),headers={'Content-Type':'application/json'})
                with urlopen(request,timeout=10) as response:
                    raw=response.read(10_000_001)
                if len(raw)>10_000_000:raise ValueError('OTP readiness response too large')
                plan=plan_of(json.loads(raw))
                if any(any(leg.get('transitLeg') is True for leg in it.get('legs',[])) for it in plan['itineraries']):
                    return {'scheduled_ready':True,'readiness_probe':'MVB public corridor, noon, ignoreRealtimeUpdates=true'}
        except (OSError,ValueError):pass
        time.sleep(.5)
    raise RuntimeError('New scheduled transport generation failed route readiness; activation cancelled')


def activate(release, config=None):
    config=config or settings()
    with state_lock(config.state/'transit-operation.lock'):
        return _activate(release,config)


def _activate(release, config):
    config=config or settings();release=checked_release(release,config)
    verify_release(release,config)
    from .transit import calendar_coverage
    coverage=calendar_coverage(release)
    previous=json.loads(config.active_pointer.read_text(encoding='utf-8-sig'))
    old=active_release(config);verify_release(old,config)
    roles=('realtime','otp');ports=(config.realtime_port,config.otp_port)
    rows=owned(listeners(ports),roles,old,config)
    for port in ports:
        if occupied(port) and not any(r['port']==port for r in rows):raise ValueError('Unknown transport process; no changes made')
    ready=json.loads((release/'ready.json').read_text(encoding='utf-8-sig'))
    candidate={'release':release.name,'previous_release':old.name,'source':ready['source'],
               'activated_at':datetime.now(timezone.utc).isoformat(),'calendar_coverage':coverage,
               'readiness_policy':'scheduled-route-required-rt-independent-v1'}
    if os.environ.get('COMPANION_SERVICE_MANAGER','processes')=='systemd':
        try:
            systemd('stop',roles)
            atomic_json(config.active_pointer,{**candidate,'activation_status':'checking'})
            systemd('start',roles)
            wait_transit(config=config)
            atomic_json(config.active_pointer,candidate)
        except Exception:
            systemd('stop',roles)
            atomic_json(config.active_pointer,previous)
            systemd('start',roles)
            raise
        return candidate
    candidate_attempted=False
    try:
        stop_rows(rows)
        candidate_attempted=True
        start(roles,release=release,config=config)
        wait_transit(config=config)
        atomic_json(config.active_pointer,candidate)
    except Exception:
        # Stop only verified candidate owners. Restore exactly the previous pointer.
        if candidate_attempted:
            candidate_rows=owned(listeners(ports),roles,release,config)
            stop_rows(candidate_rows)
        atomic_json(config.active_pointer,previous)
        start(roles,release=old,config=config)
        raise
    return candidate
