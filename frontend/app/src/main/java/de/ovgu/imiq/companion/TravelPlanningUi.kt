package de.ovgu.imiq.companion



import android.Manifest

import android.content.Intent

import android.content.pm.PackageManager

import android.location.Geocoder

import android.location.Address

import android.content.Context

import android.os.Build

import android.location.LocationManager

import android.provider.Settings

import androidx.activity.compose.rememberLauncherForActivityResult

import androidx.activity.result.IntentSenderRequest

import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.layout.*

import androidx.compose.material3.*

import androidx.compose.runtime.*

import androidx.compose.runtime.saveable.rememberSaveable

import androidx.compose.ui.Alignment

import androidx.compose.ui.Modifier

import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.activity.compose.BackHandler

import androidx.lifecycle.Lifecycle

import androidx.lifecycle.compose.LocalLifecycleOwner

import androidx.lifecycle.repeatOnLifecycle

import com.google.android.gms.common.api.ResolvableApiException

import com.google.android.gms.location.*

import kotlinx.coroutines.*

import java.util.Locale

import kotlin.coroutines.resume

import org.json.JSONObject



internal fun planningMapResult(planning:Boolean,current:JSONObject?,saved:JSONObject?=null):JSONObject?=if(planning)null else saved?:current



private suspend fun nearbyMapAddress(context:Context,point:GeoPoint,language:String):String?=withTimeout(7000){

 if(!Geocoder.isPresent())return@withTimeout null

 val coder=Geocoder(context,Locale.forLanguageTag(language))

 if(Build.VERSION.SDK_INT>=33)suspendCancellableCoroutine {continuation->

  coder.getFromLocation(point.lat,point.lon,1,object:Geocoder.GeocodeListener{

   override fun onGeocode(addresses:MutableList<Address>){if(continuation.isActive)continuation.resume(addresses.firstOrNull()?.getAddressLine(0))}

   override fun onError(errorMessage:String?){if(continuation.isActive)continuation.resume(null)}

  })

 }else withContext(Dispatchers.IO){

  @Suppress("DEPRECATION") val addresses=coder.getFromLocation(point.lat,point.lon,1)

  addresses?.firstOrNull()?.getAddressLine(0)

 }

}



/** Coordinates are the user's point; reverse-geocoded labels never relocate it. */

internal fun selectedMapPlace(point:GeoPoint,nearbyAddress:String?,de:Boolean):Place {

 require(point.lat.isFinite()&&point.lon.isFinite()&&point.lat in -90.0..90.0&&point.lon in -180.0..180.0)

 val coordinates=String.format(Locale.ROOT,"%.5f, %.5f",point.lat,point.lon)

 val label=nearbyAddress?.takeIf {it.isNotBlank()}?.let {if(de)"Kartenpunkt · nahe $it"else "Map point · near $it"}?:coordinates

 return Place(point.lat,point.lon,label,"map-${point.lat}-${point.lon}",source="map_selection")

}



@Composable internal fun TravelPlanningMap(model:CompanionModel,originDraft:MutableState<String>,targetOrigin:Boolean,onTargetChange:(Boolean)->Unit,locationRequest:Int,modifier:Modifier=Modifier,showSelector:Boolean=true,allowAutomaticOrigin:Boolean=true,searchInset:Dp=120.dp,selectionContext:String="",onSelectionPending:(Boolean)->Unit={},onPlaceConfirmed:(Boolean,Place)->Unit={_,_->}){

 val automaticOriginAllowed by rememberUpdatedState(allowAutomaticOrigin)

 val context=LocalContext.current;val lifecycle=LocalLifecycleOwner.current.lifecycle

 val scope=rememberCoroutineScope()

 var locating by remember {mutableStateOf(false)}

 var requesting by remember {mutableStateOf(false)}

 var message by remember {mutableStateOf<String?>(null)}

 var locationJob by remember {mutableStateOf<Job?>(null)}

 var proposal by remember {mutableStateOf<PlanningPointProposal?>(null)}
 var addressLabel by remember {mutableStateOf<PlanningAddressLabel?>(null)}
 var sheetHeight by remember {mutableStateOf(0.dp)}
 var hintHeight by remember {mutableStateOf(36.dp)}
 val density=LocalDensity.current
 val pendingChanged by rememberUpdatedState(onSelectionPending)

 var fallbackSettings by remember {mutableStateOf(false)}

 var active by remember {mutableStateOf(true)}

 var generation by remember {mutableIntStateOf(0)}

 fun enabled()=(context.getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager).isLocationEnabled

 fun permitted()=listOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION).any {context.checkSelfPermission(it)==PackageManager.PERMISSION_GRANTED}

 fun freshPosition(){
  if(!active||locationJob?.isActive==true||!permitted()||!enabled())return
  val requestGeneration=generation
  locating=true;message=null
  locationJob=scope.launch {
   try {lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED){
    locating=true
    coroutineScope {
     val timeout=launch {
      delay(25000)
      if(locating){
       locating=false
       message=model.text("Still waiting for a fresh position. You can choose a map point while I keep looking.","Ich warte noch auf einen aktuellen Standort. Du kannst einen Kartenpunkt wählen, während ich weiter suche.")
      }
     }
     try {mapLocations(context).collect {fix->
      if(!active||generation!=requestGeneration)return@collect
      model.planningPosition=obj("lat" to fix.latitude,"lon" to fix.longitude,"accuracy_m" to fix.accuracy.toDouble(),"monotonic_ms" to fix.elapsedRealtimeNanos/1000000)
      if(automaticOriginAllowed&&model.origin==null&&originDraft.value.isBlank()){
       model.setPlace(true,Place(fix.latitude,fix.longitude,model.text("My current location","Mein aktueller Standort"),"geo-${fix.time}",source="geolocation",accuracy=fix.accuracy))
       onTargetChange(false)
      }
      locating=false;timeout.cancel()
      message=model.text("Location accuracy: about ${fix.accuracy.toInt()} m","Standortgenauigkeit: etwa ${fix.accuracy.toInt()} m")
     }}finally {timeout.cancel();locating=false}
    }
   }}catch(e:CancellationException){throw e}catch(e:Exception){
    message=model.text("Location unavailable. You can still choose addresses or map points.","Standort nicht verfügbar. Adressen oder Kartenpunkte bleiben nutzbar.")
   }finally{if(generation==requestGeneration)locating=false}
  }
 }

 val resolution=rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {requesting=false;if(active){if(enabled())freshPosition()else message=model.text("Location stays off. Choose addresses or map points.","Standort bleibt ausgeschaltet. Adressen oder Kartenpunkte wählen.")}}

 val settings=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){requesting=false;if(active&&enabled())freshPosition()}

 fun checkSettings(){

  requesting=false

  if(enabled()){freshPosition();return}

  requesting=true

  val request=LocationSettingsRequest.Builder().addLocationRequest(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY,10000).build()).setAlwaysShow(true).build()

  LocationServices.getSettingsClient(context).checkLocationSettings(request)

   .addOnSuccessListener {requesting=false;if(active)freshPosition()}

   .addOnFailureListener {error->if(active){if(error is ResolvableApiException)resolution.launch(IntentSenderRequest.Builder(error.resolution).build())else {requesting=false;fallbackSettings=true}}}

 }

 val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){grants->requesting=false;if(active){if(grants.values.any {it})checkSettings()else message=model.text("Location permission declined. Choose addresses or map points.","Standortberechtigung abgelehnt. Adressen oder Kartenpunkte wählen.")}}

 fun locate(){if(!active||requesting)return;if(permitted()&&enabled()){freshPosition();return};requesting=true;generation++;locationJob?.cancel();locating=false;model.planningPosition=null;if(permitted())checkSettings()else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))}

 LaunchedEffect(locationRequest){

  model.planningPosition=null

  if(locationRequest>0){locate();return@LaunchedEffect}

  if(!model.planningLocationAttempted){model.planningLocationAttempted=true;locate()}

  else if(permitted()&&enabled())freshPosition()

 }

 DisposableEffect(Unit){onDispose {active=false;locationJob?.cancel();}}

 LaunchedEffect(proposal,targetOrigin,model.origin,model.destination,selectionContext,model.busy) {
  val current=proposal
  if(current!=null&&!currentPlanningProposal(current,targetOrigin,model.origin,model.destination,selectionContext,model.busy))proposal=null
 }
 LaunchedEffect(proposal,model.locale) {
  addressLabel=null
  val chosen=proposal?:return@LaunchedEffect
  val language=model.locale
  val nearby=try {nearbyMapAddress(context,chosen.point,language)}catch(e:TimeoutCancellationException){null}catch(e:CancellationException){throw e}catch(_:Exception){null}
  if(proposal==chosen&&model.locale==language&&nearby!=null)addressLabel=PlanningAddressLabel(chosen,language,nearby)
 }
 val nearbyAddress=planningAddressFor(addressLabel,proposal,model.locale)
 LaunchedEffect(proposal) {pendingChanged(proposal!=null);if(proposal==null)sheetHeight=0.dp}
 DisposableEffect(Unit){onDispose {pendingChanged(false)}}
 BackHandler(proposal!=null){proposal=null}
 Box(modifier.fillMaxSize()) {
  NativeJourneyMap(model,compact=true,planning=true,planningLocating=locating||requesting,onPlanningLocate=::locate,
   planningTopInset=searchInset+hintHeight+16.dp,planningBottomInset=if(proposal!=null)sheetHeight else 0.dp,
   pendingPoint=proposal?.point,pendingOrigin=proposal?.start?:false,onMapPick={point->
    if(!model.busy){
     selectedMapPlace(point,null,model.de) // Validate before showing a proposal.
     proposal=PlanningPointProposal(targetOrigin,point,model.origin,model.destination,selectionContext)
     pendingChanged(true)
    }
   })
  Surface(Modifier.align(Alignment.TopCenter).padding(top=searchInset+8.dp,start=56.dp,end=56.dp)
   .onSizeChanged {hintHeight=with(density){it.height.toDp()}},color=Panel,shape=MaterialTheme.shapes.large,shadowElevation=2.dp) {
   Text(model.text("Tap map to set ${if(targetOrigin)"origin"else"destination"}","Antippen: ${if(targetOrigin)"Start"else"Ziel"} setzen"),
    style=MaterialTheme.typography.labelMedium,color=MutedText,modifier=Modifier.padding(horizontal=12.dp,vertical=8.dp))
  }
  proposal?.let {chosen->PlanningPointSheet(model,chosen,nearbyAddress,{sheetHeight=it},onConfirm={
   if(currentPlanningProposal(chosen,targetOrigin,model.origin,model.destination,selectionContext,model.busy)) {
    val place=selectedMapPlace(chosen.point,nearbyAddress,model.de)
    proposal=null;model.setPlace(chosen.start,place);onPlaceConfirmed(chosen.start,place);onTargetChange(false)
   }else proposal=null
  },onCancel={proposal=null},modifier=Modifier.align(Alignment.BottomCenter))}
 }

 if(fallbackSettings)AlertDialog(onDismissRequest={fallbackSettings=false},title={Text(model.text("Enable location","Standort aktivieren"))},text={Text(model.text("Open Android location settings, or continue choosing addresses and map points.","Android-Standorteinstellungen öffnen oder Adressen und Kartenpunkte wählen."))},confirmButton={TextButton({fallbackSettings=false;settings.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))}){Text(model.text("Open settings","Einstellungen öffnen"))}},dismissButton={TextButton({fallbackSettings=false}){Text(model.text("Continue without location","Ohne Standort fortfahren"))}})

}
