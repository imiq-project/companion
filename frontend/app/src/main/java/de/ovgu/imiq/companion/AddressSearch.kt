package de.ovgu.imiq.companion

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume

/** Keep cancellation and exact house-number checks across both providers. */
internal suspend fun addressSearch(
 query:String,
 backend:suspend ()->List<Place>,
 device:suspend ()->List<Place>,
):List<Place> {
 val house=Regex("\\s(\\d{1,4}[a-z]?(?:[-/]\\d{1,4}[a-z]?)?)(?=\\s|$)",RegexOption.IGNORE_CASE)
  .findAll(query.substringBefore(',')).lastOrNull()?.groupValues?.get(1)?.lowercase(Locale.ROOT)
 fun matching(places:List<Place>)=places.filter {p->
  p.lat.isFinite()&&p.lon.isFinite()&&kotlin.math.abs(p.lat)<=90&&kotlin.math.abs(p.lon)<=180&&
   p.label.isNotBlank()&&(house==null||p.house.trim().lowercase(Locale.ROOT)==house)
 }.distinctBy {Triple(it.lat,it.lon,it.label)}.take(10)
 var backendFailed=false
 try {matching(backend()).takeIf {it.isNotEmpty()}?.let {return it}}
 catch(e:CancellationException){throw e}
 catch(_:Exception){backendFailed=true}
 try {return matching(device())}
 catch(e:CancellationException){throw e}
 catch(_:Exception){if(backendFailed)throw IOException("Address search is temporarily unavailable. Try again.")}
 return emptyList()
}

/** Android's configured geocoder is independent of the Flask/Photon provider. */
internal suspend fun deviceAddressSearch(context:Context,query:String,language:String,bias:Place?):List<Place> {
 if(!Geocoder.isPresent())throw IOException("No Android geocoder available")
 val geocoder=Geocoder(context.applicationContext,Locale.forLanguageTag(language))
 val lat=bias?.lat?:52.13;val lon=bias?.lon?:11.62
 val south=(lat-.35).coerceAtLeast(-90.0);val north=(lat+.35).coerceAtMost(90.0)
 val west=(lon-.55).coerceAtLeast(-180.0);val east=(lon+.55).coerceAtMost(180.0)
 val addresses=withTimeoutOrNull(6000) {
  if(Build.VERSION.SDK_INT>=33)suspendCancellableCoroutine<List<Address>?> {continuation->
   geocoder.getFromLocationName(query,10,south,west,north,east,object:Geocoder.GeocodeListener {
    override fun onGeocode(addresses:MutableList<Address>){if(continuation.isActive)continuation.resume(addresses.toList())}
    override fun onError(errorMessage:String?){if(continuation.isActive)continuation.resume(null)}
   })
  }else withContext(Dispatchers.IO){
   @Suppress("DEPRECATION")
   geocoder.getFromLocationName(query,10,south,west,north,east)
  }
 }?:throw IOException("Android address search unavailable")
 return addresses.mapNotNull {a->
  if(!a.hasLatitude()||!a.hasLongitude())return@mapNotNull null
  val street=a.thoroughfare.orEmpty();val house=a.subThoroughfare.orEmpty()
  val city=a.locality?:a.subAdminArea.orEmpty()
  val label=a.getAddressLine(0)?.takeIf {it.isNotBlank()}?:listOf(a.featureName,
   listOf(street,house).filter {it.isNotBlank()}.joinToString(" "),city).filterNotNull().filter {it.isNotBlank()}.distinct().joinToString(", ")
  Place(a.latitude,a.longitude,label,"android-geocoder:${a.latitude}:${a.longitude}",house,street,
   listOf(a.postalCode.orEmpty(),city).filter {it.isNotBlank()}.joinToString(" "))
 }
}
