"""Stop timetable board: scheduled times are always retained independently of RT."""
import datetime,math
from routing.otp_transit import OtpTransitClient,OtpError,berlin,same_realtime_dataset
from routing.graphhopper_geometry import distance_m

QUERY='''query CompanionDepartures($id:String!,$date:String!) {
 stop(id:$id) { gtfsId name lat lon
 stoptimesForServiceDate(date:$date,omitCanceled:false,omitNonPickups:true) { stoptimes {
 serviceDay scheduledDeparture realtimeDeparture departureDelay realtime realtimeState headsign pickupType
 trip { gtfsId tripHeadsign route { gtfsId shortName longName mode } }
 } } } }'''

def resolve_stops(payload,catalog):
    platform=payload.get('boarding_platform_id')
    selected=[s for s in catalog if platform and s.get('waiting_platform',{}).get('id')==platform]
    if not selected:
        ids=payload.get('boarding_stop_ids',[])
        if not isinstance(ids,list) or not 1<=len(ids)<=8 or any(not isinstance(s,str) or len(s)>200 for s in ids):raise OtpError('Unknown timetable platform')
        expected=payload.get('location')
        if not isinstance(expected,dict) or any(type(expected.get(k)) not in (int,float) or not math.isfinite(expected[k]) for k in ('lat','lon')):raise OtpError('Expected selected stop position')
        selected=[s for s in catalog if s['gtfsId'] in ids and distance_m([expected['lon'],expected['lat']],[s['lon'],s['lat']])<=30]
    if not selected or len(selected)>12:raise OtpError('No verified timetable link for this platform')
    return selected

def departures(payload,client=None,catalog=None):
    from routing.stops import stop_catalog
    if not isinstance(payload,dict):raise OtpError('Invalid timetable request')
    c=client or OtpTransitClient();selected=resolve_stops(payload,catalog if catalog is not None else stop_catalog(client))
    when=berlin(payload.get('datetime') or datetime.datetime.now(datetime.timezone.utc).isoformat())
    start=when.timestamp();end=start+7200
    status_before=c.status();rows=[]
    dates={when.strftime('%Y%m%d'),datetime.datetime.fromtimestamp(end,when.tzinfo).strftime('%Y%m%d')}
    for stop in selected:
        for date in sorted(dates):
            result=c.request(c.base+'/otp/routers/default/index/graphql',{'query':QUERY,'variables':{'id':stop['gtfsId'],'date':date}})
            data=(result.get('data') or {}).get('stop')
            if result.get('errors') or not data or data.get('gtfsId')!=stop['gtfsId']:raise OtpError('Timetable stop unavailable')
            entries=[entry for pattern in data.get('stoptimesForServiceDate') or [] for entry in pattern.get('stoptimes') or []]
            for entry in entries:
                day=entry.get('serviceDay');seconds=entry.get('scheduledDeparture')
                if type(day) is not int or type(seconds) is not int or seconds<0:continue
                scheduled=day+seconds;predicted=entry.get('realtimeDeparture')
                actual=day+predicted if type(predicted) is int and predicted>=0 else None
                if not (start<=scheduled<=end or actual is not None and scheduled<start<=actual<=end):continue
                trip=entry.get('trip') or {};route=trip.get('route') or {}
                if not trip.get('gtfsId'):continue
                rows.append({'trip_id':trip['gtfsId'],'route_id':route.get('gtfsId'),'line':route.get('shortName'),
                    'line_name':route.get('longName'),'vehicle':route.get('mode'),'headsign':entry.get('headsign') or trip.get('tripHeadsign'),
                    'stop_id':stop['gtfsId'],'stop_name':stop['name'],'scheduled_departure_ms':scheduled*1000,
                    '_predicted':actual*1000 if actual is not None else None,'_realtime':entry.get('realtime') is True,
                    '_state':entry.get('realtimeState'),'_delay':entry.get('departureDelay')})
    status_after=c.status();usable=same_realtime_dataset(status_before,status_after)
    normalized=[]
    for r in rows:
        realtime=r.pop('_realtime')
        live=usable and realtime and r['_predicted'] is not None
        state=r.pop('_state');predicted=r.pop('_predicted');delay=r.pop('_delay')
        cancelled=usable and state=='CANCELED'
        r.update(realtime=bool(live),predicted_departure_ms=predicted if live else None,
            departure_delay_seconds=delay if live and type(delay) is int else None,cancelled=cancelled,
            time_basis='REALTIME' if live else 'SCHEDULED')
        if r['scheduled_departure_ms']<start*1000 and not live:continue
        normalized.append(r)
    normalized=list({(r['trip_id'],r['stop_id'],r['scheduled_departure_ms']):r for r in normalized}.values())
    normalized.sort(key=lambda r:(r['scheduled_departure_ms'],r['line'] or ''))
    return {'schema_version':'mvb-stop-departures-v1','stops':selected,'departures':normalized[:40],
        'captured_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'window_start':when.isoformat(),
        'window_seconds':7200,'realtime_available':usable,'realtime_status':status_after,
        'status':'AVAILABLE' if normalized else 'NO_SCHEDULED_DEPARTURES','scheduled_times_always_preserved':True,
        'source':'GTFS · MVB · GTFS.de','writes_choice_or_experience':False}

def register_departures(app):
    from flask import jsonify,request
    @app.post('/api/companion/transit/stop-departures')
    def board():
        try:return jsonify(departures(request.get_json(force=True)))
        except (OSError,ValueError,KeyError,TypeError):return jsonify(message='The timetable is unavailable for this exact stop. Refresh the map or try again.'),503
