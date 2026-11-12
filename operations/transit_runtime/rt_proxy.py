"""Loopback-only cached GTFS-RT selector. Retained protobuf bytes are unchanged."""
import json, time, threading, urllib.request, datetime
from zoneinfo import ZoneInfo
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
URL='https://realtime.gtfs.de/realtime-free.pb'
def var(b,p):
    v=0
    for shift in range(0,70,7):
        if p>=len(b):raise ValueError('Truncated varint')
        x=b[p];p+=1;v|=(x&127)<<shift
        if x<128:return v,p
    raise ValueError('Invalid varint')
def fields(b):
    p=0
    while p<len(b):
        start=p;t,p=var(b,p);n,w=t>>3,t&7
        if not n:raise ValueError('Invalid protobuf tag')
        if w==0:v,p=var(b,p)
        elif w==2:
            l,p=var(b,p)
            if p+l>len(b):raise ValueError('Truncated field')
            v=b[p:p+l];p+=l
        elif w in [1,5]:
            l=8 if w==1 else 4
            if p+l>len(b):raise ValueError('Truncated fixed field')
            v=b[p:p+l];p+=l
        else:raise ValueError('Unsupported wire type')
        yield n,v,b[start:p]
def field(b,n):return next((v for k,v,_ in fields(b) if k==n),None)
def string(b,n):
    v=field(b,n);return v.decode('utf-8') if isinstance(v,bytes) else None

def trip_matches(update,metadata):
    descriptor=field(update,1)
    route=string(descriptor,5)
    if route is not None and route!=metadata['route_id']:return False
    day=string(descriptor,3)
    base=None
    if day:
        try:base=datetime.datetime.strptime(day,'%Y%m%d').replace(hour=12,tzinfo=ZoneInfo('Europe/Berlin')).timestamp()-43200
        except ValueError:return False
    checked=0
    for n,s,_ in fields(update):
        if n!=2:continue
        seq=field(s,1);sid=string(s,4)
        expected=metadata['stops'].get(str(seq)) if seq is not None else None
        if expected is None and seq is None and sid:
            matches=[v for v in metadata['stops'].values() if v[0]==sid]
            expected=matches[0] if len(matches)==1 else None
        if expected is None or sid is not None and sid!=expected[0]:return False
        checked+=1
        for num,index in ((2,1),(3,2)):
            event=field(s,num)
            if event is None:continue
            stamp,delay=field(event,2),field(event,1)
            if base is not None and stamp is not None and delay is not None and expected[index]:
                if delay>=2**31:delay-=2**64 if delay>=2**32 else 2**32
                h,m,second=map(int,expected[index].split(':'))
                if abs(stamp-delay-(base+h*3600+m*60+second))>60:return False
    return True

def filter_feed(b,ids,now=None,trip_index=None):
    now=time.time() if now is None else now
    head=field(b,1)
    if head is None:raise ValueError('Missing feed header')
    stamp=field(head,3)
    if not isinstance(stamp,int) or now-stamp>180 or stamp-now>60:raise ValueError('Missing or stale realtime timestamp')
    if field(head,2) not in [None,0]:raise ValueError('Differential feeds not supported')
    kept=[];updates=0;alerts=0;candidate_updates=0;invalid=0;stale=0
    valid_until=stamp+180
    ids={k:set(v) for k,v in ids.items()}
    for n,entity,raw in fields(b):
        if n!=2:
            kept.append(raw);continue
        tu=field(entity,3);al=field(entity,5);include=False
        if tu is not None:
            td=field(tu,1)
            include=td is not None and string(td,1) in ids['trip']
            if include:
                candidate_updates+=1
                if trip_index is not None and not trip_matches(tu,trip_index[string(td,1)]):
                    invalid+=1;include=False
                update_stamp=field(tu,4)
                if update_stamp is not None and (not isinstance(update_stamp,int) or now-update_stamp>90 or update_stamp-now>60):
                    stale+=1;include=False
            if include:
                updates+=1
                if update_stamp is not None:valid_until=min(valid_until,update_stamp+90)
        if al is not None:
            for k,sel,_ in fields(al):
                if k!=5:continue
                criteria=[]
                for num,key in [(1,'agency'),(2,'route'),(5,'stop')]:
                    val=string(sel,num)
                    if val is not None:criteria.append(val in ids[key])
                td=field(sel,4)
                if td is not None:
                    tid=string(td,1);rid=string(td,5)
                    if tid is not None:criteria.append(tid in ids['trip'])
                    if rid is not None:criteria.append(rid in ids['route'])
                if criteria and all(criteria):include=True
            if include:alerts+=1
        if include:kept.append(raw)
    if trip_index is not None:
        if candidate_updates==0:raise ValueError('No realtime coverage for static trips; scheduled routes only')
        if invalid/candidate_updates>.1:raise ValueError(f'Static/RT identity mismatch: {invalid}/{candidate_updates} candidate trips invalid')
        if updates==0:raise ValueError('No fresh verified realtime trip updates; scheduled routes only')
    return b''.join(kept),{'feed_timestamp':stamp,'valid_until':valid_until,'trip_updates':updates,'alerts':alerts,'captured_at':now,
                         'candidate_trip_updates':candidate_updates,'rejected_identity_updates':invalid,'rejected_stale_updates':stale}

def download_feed():
    req=urllib.request.Request(URL,headers={'User-Agent':'Companion-OTP-Probe/1.1'})
    with urllib.request.urlopen(req,timeout=35) as response:
        raw=response.read(100_000_001)
    if len(raw)>100_000_000:raise ValueError('Feed too large')
    return raw

class Cache:
    """Network and parsing never hold the snapshot lock or run in HTTP handlers."""
    def __init__(self,ids,fetch=download_feed,clock=time.time,manifest=None,root=None):
        self.ids=ids;self.fetch=fetch;self.clock=clock
        self.lock=threading.Lock();self.body=None;self.metadata={}
        self.error=None;self.refreshing=False
        self.stop_event=threading.Event();self.worker=None
        self.manifest=manifest;self.root=Path(root) if root else None
        self.source_checked_at=0;self.source_compatible=False
        self.dataset_compatible=False if manifest else None
    def check_dataset(self):
        if self.manifest is None:return
        if self.manifest.get('schema_version')!='mvb-gtfs-identity-v2':raise ValueError('Static identity manifest requires synchronized update')
        state=json.loads((self.root/'build-state.json').read_text(encoding='utf-8-sig'))
        if state.get('mvb.gtfs.zip','').lower()!=self.manifest['static_dataset_sha256']:raise ValueError('OTP graph and GTFS manifest differ')
        now=self.clock()
        if now-self.source_checked_at>=600 or not self.source_compatible:
            source=self.manifest['source']
            with urllib.request.urlopen(urllib.request.Request(source['url'],method='HEAD'),timeout=15) as r:
                match=r.headers.get('ETag')==source['etag'] and r.headers.get('Last-Modified')==source['last_modified']
            self.source_checked_at=now;self.source_compatible=match
        if not self.source_compatible:raise ValueError('New static GTFS published; waiting for coherent graph update')
    def refresh(self):
        with self.lock:self.refreshing=True
        try:
            self.check_dataset()
            raw=self.fetch()
            body,metadata=filter_feed(raw,self.ids,now=self.clock(),trip_index=self.manifest['trip_index'] if self.manifest else None)
            if self.manifest:
                metadata.update(compatibility_version='mvb-gtfs-identity-v2',dataset_compatible=True,
                    static_dataset_sha256=self.manifest['static_dataset_sha256'],graph_dataset_sha256=self.manifest['static_dataset_sha256'],
                    static_source_last_modified=self.manifest['source']['last_modified'],source_checked_at=self.source_checked_at)
            with self.lock:
                self.body=body;self.metadata=metadata;self.error=None;self.dataset_compatible=True if self.manifest else None
            print('RT ready:',metadata,flush=True)
        except Exception as exc:
            # Keep a previous snapshot; snapshot() enforces its expiry.
            with self.lock:
                self.error=str(exc)
                # An identity/version failure invalidates even a fresh previous snapshot.
                if isinstance(exc,ValueError):self.dataset_compatible=False
            print('RT refresh failed:',str(exc),flush=True)
        finally:
            with self.lock:self.refreshing=False
    def start(self):
        if self.worker is not None:return
        def loop():
            while not self.stop_event.is_set():
                self.refresh()
                if self.stop_event.wait(30):break
        self.worker=threading.Thread(target=loop,name='gtfs-refresh',daemon=True)
        self.worker.start()
    def close(self):self.stop_event.set()
    def snapshot(self):
        with self.lock:
            body=self.body;metadata=dict(self.metadata)
            error=self.error;refreshing=self.refreshing
            compatible=self.dataset_compatible
        stamp=metadata.get('feed_timestamp');now=self.clock()
        if compatible is False or body is None or stamp is None or now-stamp>180 or stamp-now>60 or now>metadata.get('valid_until',stamp+180):
            raise ValueError(error or 'Realtime not ready or stale; waiting for background refresh')
        metadata.update(ready=True,refresh_in_progress=refreshing,refresh_error=error)
        return body,metadata
    def status(self):
        try:return self.snapshot()[1]
        except ValueError as e:
            with self.lock:metadata=dict(self.metadata)
            if self.manifest:metadata.update(compatibility_version='mvb-gtfs-identity-v2',
                static_dataset_sha256=self.manifest['static_dataset_sha256'],
                graph_dataset_sha256=self.manifest['static_dataset_sha256'])
            metadata.update(ready=False,dataset_compatible=False,refresh_error=str(e),refresh_in_progress=self.refreshing)
            return metadata
    def get(self):return self.snapshot()[0]

def make_handler(cache):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path not in ['/feed.pb','/status']:
                self.send_error(404);return
            try:
                if self.path=='/status':body=json.dumps(cache.status()).encode()
                else:body,metadata=cache.snapshot()
                self.send_response(200)
                self.send_header('Content-Type','application/json' if self.path=='/status' else 'application/x-protobuf')
                self.send_header('Content-Length',str(len(body)))
                self.end_headers();self.wfile.write(body)
            except ValueError as exc:self.send_error(503,str(exc))
    return Handler

def serve(root,port=8092):
    manifest=json.loads((Path(root)/'graph/mvb.gtfs.manifest.json').read_text(encoding='utf-8'))
    cache=Cache(manifest['ids'],manifest=manifest,root=root)
    server=ThreadingHTTPServer(('127.0.0.1',port),make_handler(cache))
    cache.start()
    print(f'MVB realtime proxy: http://127.0.0.1:{port}/status; background refresh enabled',flush=True)
    try:server.serve_forever()
    except KeyboardInterrupt:pass
    finally:cache.close();server.server_close()
if __name__=='__main__':
    import argparse
    p=argparse.ArgumentParser();p.add_argument('--root',required=True);p.add_argument('--port',type=int,default=8092)
    args=p.parse_args()
    if not 1<=args.port<=65535:p.error('Port must be between 1 and 65535')
    serve(args.root,args.port)
