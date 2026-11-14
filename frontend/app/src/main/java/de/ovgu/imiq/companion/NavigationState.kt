package de.ovgu.imiq.companion

import org.json.JSONObject
import kotlin.math.*

internal enum class NavigationSignal { LIVE, WAITING, STALE, UNCERTAIN, PAUSED, PLAYBACK }
internal data class NavigationGuidance(val signal:NavigationSignal,val offsetMeters:Double?=null,val offRoute:Boolean=false)

/** Presentation only: never snaps a fix, changes the selected route, or writes evidence. */
internal fun navigationGuidance(route:MapRoute?,point:JSONObject?,nowMonotonic:Long,paused:Boolean,
 locationAvailable:Boolean=true,sameBoot:Boolean=true):NavigationGuidance {
 if(paused)return NavigationGuidance(NavigationSignal.PAUSED)
 if(!locationAvailable||point==null||!sameBoot)return NavigationGuidance(NavigationSignal.WAITING)
 val at=point.number("monotonic_ms")?.takeIf {it.isFinite()&&it>=0}?:return NavigationGuidance(NavigationSignal.WAITING)
 val age=nowMonotonic-at
 if(age<0||age>30_000)return NavigationGuidance(NavigationSignal.STALE)
 val accuracy=point.number("accuracy_m")?.takeIf {it.isFinite()&&it>=0}?:return NavigationGuidance(NavigationSignal.UNCERTAIN)
 val lat=point.number("lat");val lon=point.number("lon")
 if(lat==null||lon==null||!lat.isFinite()||!lon.isFinite()||abs(lat)>90||abs(lon)>180)return NavigationGuidance(NavigationSignal.WAITING)
 if(accuracy>30)return NavigationGuidance(NavigationSignal.UNCERTAIN)
 // Reference/approximate geometry cannot establish an off-route observation.
 val trusted=route?.takeUnless {it.supplemental||it.segments.isEmpty()||it.segments.any {s->!s.drawable||s.approximate||s.referenceNetwork}}
 val offset=trusted?.let {navigationRouteOffset(GeoPoint(lat,lon),it)}
 return NavigationGuidance(NavigationSignal.LIVE,offset,offset!=null&&offset>maxOf(45.0,accuracy*2))
}

/** Distance to independent drawable segments; gaps between legs are never bridged. */
internal fun navigationRouteOffset(point:GeoPoint,route:MapRoute):Double? {
 if(!point.lat.isFinite()||!point.lon.isFinite()||abs(point.lat)>90||abs(point.lon)>180)return null
 val scale=cos(Math.toRadians(point.lat));val metres=111_195.0
 return route.segments.filter {it.drawable}.flatMap {segment->segment.points.zipWithNext().mapNotNull {(a,b)->
  if(listOf(a.lat,a.lon,b.lat,b.lon).any {!it.isFinite()}||abs(a.lat)>90||abs(b.lat)>90||abs(a.lon)>180||abs(b.lon)>180)return@mapNotNull null
  // Local tangent plane is sufficient for an urban display warning; no map matching.
  fun east(lon:Double)=((lon-point.lon+540)%360-180)*scale*metres
  val ax=east(a.lon);val ay=(a.lat-point.lat)*metres;val bx=east(b.lon);val by=(b.lat-point.lat)*metres
  val dx=bx-ax;val dy=by-ay;val length=dx*dx+dy*dy
  val t=if(length==0.0)0.0 else (-(ax*dx+ay*dy)/length).coerceIn(0.0,1.0)
  hypot(ax+t*dx,ay+t*dy)
 }}.minOrNull()
}

internal fun navigationElapsed(seconds:Double?):String {
 val value=seconds?.takeIf {it.isFinite()&&it>=0}?.toLong()?:return "—"
 return if(value>=3600)"%d:%02d:%02d".format(value/3600,value/60%60,value%60)else "%02d:%02d".format(value/60,value%60)
}
