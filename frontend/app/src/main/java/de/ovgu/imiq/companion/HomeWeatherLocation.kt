package de.ovgu.imiq.companion

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** Labels never change the coordinates used for weather. No route/SQLite writes. */
internal fun homeWeatherPlaceLabel(locality:String?,district:String?,region:String?,lat:Double,lon:Double):String =
 listOf(locality,district,region).firstOrNull {!it.isNullOrBlank()}?.trim()
  ?:String.format(Locale.ROOT,"%.3f, %.3f",lat,lon)

internal suspend fun homeWeatherCity(context:Context,lat:Double,lon:Double,language:String):String {
 val fallback=homeWeatherPlaceLabel(null,null,null,lat,lon)
 return withTimeoutOrNull(7000) {
  if(!Geocoder.isPresent())return@withTimeoutOrNull fallback
  val geocoder=Geocoder(context,Locale.forLanguageTag(language))
  val address=if(Build.VERSION.SDK_INT>=33)suspendCancellableCoroutine<Address?> {continuation->
   geocoder.getFromLocation(lat,lon,1,object:Geocoder.GeocodeListener {
    override fun onGeocode(addresses:MutableList<Address>){if(continuation.isActive)continuation.resume(addresses.firstOrNull())}
    override fun onError(errorMessage:String?){if(continuation.isActive)continuation.resume(null)}
   })
  }else withContext(Dispatchers.IO) {
   @Suppress("DEPRECATION")
   val addresses=geocoder.getFromLocation(lat,lon,1)
   addresses?.firstOrNull()
  }
  homeWeatherPlaceLabel(address?.locality,address?.subAdminArea,address?.adminArea,lat,lon)
 }?:fallback
}
