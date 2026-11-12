"""MVB-only GTFS extraction. All retained GTFS IDs are unchanged."""
import csv, io, json, re, zipfile, datetime
from pathlib import Path

def rows(z,name):
    with z.open(name) as b:
        yield from csv.DictReader(io.TextIOWrapper(b,encoding='utf-8-sig',newline=''))

def subset(source,dest):
    dest=Path(dest); dest.parent.mkdir(parents=True,exist_ok=True)
    counts={}; ids={}; stopids=set()
    tmp=dest.with_suffix('.partial')
    try:
        with zipfile.ZipFile(source) as zin,zipfile.ZipFile(tmp,'w',zipfile.ZIP_DEFLATED) as zout:
            required=['agency.txt','routes.txt','trips.txt','stop_times.txt','stops.txt']
            for n in required:
                if n not in zin.namelist():raise ValueError('Missing '+n)
            agencies=[r for r in rows(zin,'agency.txt') if re.search(r'magdeburger\s+verkehrsbetriebe',r['agency_name'],re.I)]
            if not agencies:raise ValueError('MVB not found in agency.txt; no output created')
            aids={r['agency_id'] for r in agencies}
            def write(name,rs):
                with zin.open(name) as b:
                    fields=next(csv.reader(io.TextIOWrapper(b,encoding='utf-8-sig',newline='')))
                count=0
                with zout.open(name,'w') as b:
                    with io.TextIOWrapper(b,encoding='utf-8',newline='') as text:
                        w=csv.DictWriter(text,fields);w.writeheader()
                        for r in rs:w.writerow(r);count+=1
                counts[name]=count
            write('agency.txt',agencies)
            routes=[r for r in rows(zin,'routes.txt') if r.get('agency_id') in aids]
            rids={r['route_id'] for r in routes};write('routes.txt',routes)
            trips=[r for r in rows(zin,'trips.txt') if r['route_id'] in rids]
            tids={r['trip_id'] for r in trips};services={r['service_id'] for r in trips}
            shapes={r.get('shape_id') for r in trips if r.get('shape_id')};write('trips.txt',trips)
            if not tids:raise ValueError('MVB has no trips')
            def selected_times():
                for r in rows(zin,'stop_times.txt'):
                    if r['trip_id'] in tids:
                        stopids.add(r['stop_id']);yield r
            print('Extracting MVB stop_times (streaming national file)...',flush=True)
            write('stop_times.txt',selected_times())
            stops={r['stop_id']:r for r in rows(zin,'stops.txt')}
            if stopids-set(stops):raise ValueError('Trip refers to missing stops')
            todo=list(stopids)
            while todo:
                p=stops[todo.pop()].get('parent_station')
                if p and p not in stopids:
                    if p not in stops:raise ValueError('Missing parent station '+p)
                    stopids.add(p);todo.append(p)
            write('stops.txt',(stops[k] for k in sorted(stopids)))
            calendars=[];exceptions=[]
            for name in ['calendar.txt','calendar_dates.txt']:
                if name in zin.namelist():
                    rs=[r for r in rows(zin,name) if r['service_id'] in services];write(name,rs)
                    if name=='calendar.txt':calendars=rs
                    else:exceptions=rs
            if not calendars and not exceptions:raise ValueError('No calendars for MVB')
            known={r['service_id'] for r in calendars+exceptions}
            if services-known:raise ValueError('Missing referenced service calendars')
            today=datetime.date.today();active=set()
            for day in [today+datetime.timedelta(days=i) for i in range(7)]:
                ds=day.strftime('%Y%m%d');weekday=['monday','tuesday','wednesday','thursday','friday','saturday','sunday'][day.weekday()]
                current={r['service_id'] for r in calendars if r['start_date']<=ds<=r['end_date'] and r[weekday]=='1'}
                for r in exceptions:
                    if r['date']==ds:
                        if r['exception_type']=='1':current.add(r['service_id'])
                        elif r['exception_type']=='2':current.discard(r['service_id'])
                active.update(current)
            if not active:raise ValueError('No active service in next 7 days. Download a fresh GTFS.')
            for name in ['feed_info.txt','attributions.txt']:
                if name in zin.namelist():write(name,rows(zin,name))
            if 'frequencies.txt' in zin.namelist():write('frequencies.txt',(r for r in rows(zin,'frequencies.txt') if r['trip_id'] in tids))
            if 'shapes.txt' in zin.namelist():write('shapes.txt',(r for r in rows(zin,'shapes.txt') if r['shape_id'] in shapes))
            if 'transfers.txt' in zin.namelist():
                write('transfers.txt',(r for r in rows(zin,'transfers.txt') if r['from_stop_id'] in stopids and r['to_stop_id'] in stopids and all(not r.get(k) or r[k] in vals for k,vals in [('from_trip_id',tids),('to_trip_id',tids),('from_route_id',rids),('to_route_id',rids)])))
            ids={'agency':sorted(aids),'route':sorted(rids),'trip':sorted(tids),'stop':sorted(stopids)}
        tmp.replace(dest)
    except Exception:
        tmp.unlink(missing_ok=True);raise
    manifest={'created_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'counts':counts,'ids_preserved':True,'shapes_present':'shapes.txt' in counts,'scope':'MVB only; no regional rail or other agencies','ids':ids}
    dest.with_suffix('.manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    print('MVB subset:',counts,flush=True)
    return manifest
