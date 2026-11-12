"""Exact-stop access and itinerary planning, without HTTP registration."""
import copy
import datetime
from routing.otp_transit import OtpTransitClient, OtpError
from routing.graphhopper_geometry import GraphHopperGeometryClient, distance_m, GeometryError
from .stops import stop_catalog


def first_boarding(route):
    return next((l['transit'] for l in route['legs'] if l['mode']=='pt'),None)

def plan_from_stop(payload,client=None,geometry=None):
    c=client or OtpTransitClient()
    catalog=stop_catalog(client)
    selected=next((s for s in catalog if s['gtfsId']==payload['boarding_stop_id']),None)
    if selected is None:raise OtpError('Unknown GTFS stop')
    start,stop=payload['start'],payload['stop']
    from routing.journey_availability import validate_journey_availability
    available=validate_journey_availability(payload['availability'])
    max_walk=payload.get('max_walk_m',1500)
    if type(max_walk) is not int or not 0<=max_walk<=20000:raise OtpError('Invalid walking limit')
    from .validation import _coordinate, _datetime
    _coordinate(start);_coordinate(stop)
    departure=_datetime(payload.get('boarding_plan_datetime') or payload['datetime'])
    when=datetime.datetime.fromisoformat(departure.replace('Z','+00:00'))
    access=None
    if distance_m([start['lon'],start['lat']],[selected['lon'],selected['lat']])>5:
        if not available['walk']:raise OtpError('Walking access is disabled')
        access=(geometry or GraphHopperGeometryClient()).path('foot',start,selected)
        end=access['geometry']['coordinates'][-1]
        if distance_m(end,[selected['lon'],selected['lat']])>25:raise GeometryError('Walking path cannot reach the selected boarding stop')
    seconds=access['duration_seconds'] if access else 0
    metres=access['distance_meters'] if access else 0
    if metres>max_walk:raise OtpError('Walking access exceeds your walking limit')
    reached=when+datetime.timedelta(seconds=seconds)
    routes,audit=c.routes(start={'lat':selected['lat'],'lon':selected['lon']},stop=stop,departure=reached.isoformat(),
                          availability=available,max_walk=max_walk,boarding_stop_id=selected['gtfsId'])
    combined=[]
    for source in routes:
        route=copy.deepcopy(source)
        board=first_boarding(route)
        if not board or ((board.get('from') or {}).get('stop') or {}).get('gtfsId')!=selected['gtfsId']:continue
        if payload.get('boarding_trip_id') and board.get('gtfs_trip_id')!=payload['boarding_trip_id']:continue
        if payload.get('boarding_departure_ms') and board.get('scheduled_start_time_ms')!=payload['boarding_departure_ms']:continue
        if access and distance_m(access['geometry']['coordinates'][-1],route['legs'][0]['geometry']['coordinates'][0])>25:continue
        walking=route['summary']['walk_distance_meters']+metres
        if walking>max_walk:continue
        start_ms=route['transit']['start_time_ms']-seconds*1000
        if start_ms<when.timestamp()*1000-2000:continue
        if access and seconds>0:
            route['legs'].insert(0,{'mode':'walk','duration_seconds':seconds,'distance_meters':metres,
             'geometry':access['geometry'],'transit':{'source_mode':'WALK','from':{'name':'Origin',**start},
             'to':{'name':selected['name'],'lat':selected['lat'],'lon':selected['lon'],'stop':{'gtfsId':selected['gtfsId']}},
             'start_time_ms':start_ms,'end_time_ms':start_ms+seconds*1000,'geometry_quality':'OSM_ROUTED'}})
            origin_gap=distance_m(access['geometry']['coordinates'][0],[start['lon'],start['lat']])
            if origin_gap>25:route['summary'].update(origin_access_offset_m=origin_gap,origin_access_time_unknown=True)
        route['summary'].update(duration_seconds=(route['transit']['end_time_ms']-start_ms)/1000,
                                distance_meters=route['summary']['distance_meters']+metres,walk_distance_meters=walking)
        route['transit']['start_time_ms']=start_ms
        route['transit']['unallocated_duration_seconds']=max(0,route['summary']['duration_seconds']-sum(l['duration_seconds'] for l in route['legs']))
        route['transit']['selected_boarding_stop']=copy.deepcopy(selected)
        route['_geometry_evidence']['selected_boarding_stop']=copy.deepcopy(selected)
        combined.append(route)
    return combined,{**audit,'selected_boarding_stop':selected,'status':'AVAILABLE' if combined else 'NO_MATCHING_ITINERARIES'},selected

