"""Bounded GTFS trip-stop lookup and retained stop sequences."""
import copy
from functools import lru_cache
from routing.otp_transit import OtpTransitClient, OtpError


def bounded_trip_stops(stops,from_id,to_id):
    pairs=[(a,b) for a,s in enumerate(stops) if s.get('gtfsId')==from_id
           for b,t in enumerate(stops) if b>a and t.get('gtfsId')==to_id]
    if len(pairs)!=1:return []
    a,b=pairs[0]
    return copy.deepcopy(stops[a:b+1])

@lru_cache(maxsize=128)
def _trip_stops(base,trip,bucket):
    c=OtpTransitClient()
    raw=c.request(base+'/otp/routers/default/index/graphql',{
        'query':'query($id:String!){ trip(id:$id) { gtfsId stops { gtfsId name lat lon } } }',
        'variables':{'id':trip}})
    data=(raw.get('data') or {}).get('trip') or {}
    if raw.get('errors') or data.get('gtfsId')!=trip:raise OtpError('Trip stops unavailable')
    return data.get('stops',[])

