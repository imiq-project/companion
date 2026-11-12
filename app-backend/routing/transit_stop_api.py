"""HTTP handlers for stop catalogues, exact-stop plans and reference geometry."""
import datetime
import time
from flask import jsonify, request
from routing.otp_transit import OtpTransitClient, OtpError
from .stops import stop_catalog
from .stop_planning import plan_from_stop
from .trip_stops import bounded_trip_stops, _trip_stops


def register_transit_stops(app):
    @app.post('/api/companion/transit/route-stops')
    def route_stops():
        try:
            legs=request.get_json(force=True)['legs']
            if not isinstance(legs,list) or len(legs)>30:raise OtpError('Invalid leg list')
            result=[];c=OtpTransitClient()
            generation=(c.status() or {}).get('static_dataset_sha256')
            for item in legs:
                trip=item['trip_id'];a=item['from_id'];b=item['to_id']
                if not all(isinstance(v,str) and 0<len(v)<=200 for v in (trip,a,b)):raise OtpError('Invalid trip identity')
                try:
                    if not item.get('static_dataset_sha256') or item['static_dataset_sha256']!=generation:raise OtpError('Saved trip belongs to a different static dataset')
                    stops=bounded_trip_stops(_trip_stops(c.base,trip,(generation,int(time.monotonic()//600))),a,b)
                except (OtpError,OSError):stops=[]
                result.append({'leg_index':item['leg_index'],'stops':stops})
            return jsonify(legs=result,source='GTFS trip stop sequence',display_only=True)
        except (ValueError,TypeError,KeyError):return jsonify(message='Trip stops unavailable'),422

    @app.post('/api/companion/transit/tram-alignment')
    def tram_geometry():
        try:
            payload=request.get_json(force=True)
            legs=payload['legs']
            if not isinstance(legs,list) or len(legs)>100:raise OtpError('Invalid geometry request')
            from routing.tram_alignment import match_tram
            from .validation import _coordinate
            aligned=[]
            for item in legs:
                a,b=item['from'],item['to'];_coordinate(a);_coordinate(b)
                points=item['geometry']['coordinates']
                if not isinstance(points,list) or not 2<=len(points)<=5000:raise OtpError('Invalid geometry anchors')
                for p in points:
                    if not isinstance(p,list) or len(p)!=2:raise OtpError('Invalid anchor')
                    _coordinate({'lon':p[0],'lat':p[1]})
                match=match_tram(item['line'],[a['lon'],a['lat']],[b['lon'],b['lat']],points)
                if match:aligned.append({'route_id':item['route_id'],'leg_index':item['leg_index'],'geometry':match[0],'reference_alignment':match[1]})
            return jsonify(alignments=aligned)
        except (ValueError,TypeError,KeyError):return jsonify(message='Reference tram alignment unavailable.'),422

    @app.get('/api/companion/transit/stops')
    def stops():
        try:return jsonify(stops=stop_catalog(),source='GTFS · MVB',captured_at=datetime.datetime.now(datetime.timezone.utc).isoformat())
        except (OSError,ValueError,KeyError) as e:return jsonify(message='Stop information is temporarily unavailable.'),503

    @app.post('/api/companion/transit/stop-itineraries')
    def itineraries():
        try:
            payload=request.get_json(force=True)
            if not isinstance(payload,dict):raise OtpError('Invalid planning request')
            if payload.get('boarding_platform_id'):
                selected=[s for s in stop_catalog() if s.get('waiting_platform',{}).get('id')==payload['boarding_platform_id']]
                if not selected:raise OtpError('No unambiguous platform association')
                routes=[];failed=0;captured_at=datetime.datetime.now(datetime.timezone.utc).isoformat()
                for s in selected:
                    try:
                        planned,audit,_=plan_from_stop({**payload,'boarding_stop_id':s['gtfsId']})
                        routes.extend(planned);captured_at=audit['captured_at']
                    except (OSError,ValueError,KeyError,TypeError):failed+=1
                if failed==len(selected):raise OtpError('No platform services could be checked')
                routes=list({r['provider_itinerary_id']:r for r in routes}.values())
                routes.sort(key=lambda r:r['transit']['end_time_ms'])
                return jsonify(routes=routes,status='AVAILABLE' if routes else 'NO_MATCHING_ITINERARIES',captured_at=captured_at,incomplete=failed>0)
            routes,audit,stop=plan_from_stop(payload)
            return jsonify(routes=routes,stop=stop,status=audit['status'],captured_at=audit['captured_at'])
        except (OSError,ValueError,KeyError,TypeError):return jsonify(message='No verified itinerary could be retrieved. Check the destination, walking limit and connection.'),422
