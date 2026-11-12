package de.ovgu.imiq.companion

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import java.time.Instant
import kotlinx.coroutines.*
import org.json.JSONObject

/** Only started by an explicit user action. Coordinates stay in the local history. */
class TrackingService:Service(),LocationListener {
 companion object {
  const val PLAYBACK="companion.tracking.PLAYBACK"
  const val START="companion.tracking.START"
  const val PAUSE="companion.tracking.PAUSE"
  const val RESUME="companion.tracking.RESUME"
  const val FINISH="companion.tracking.FINISH"
  const val CANCEL="companion.tracking.CANCEL"
  @Volatile var running=false
  fun action(context:Context,action:String,searchId:String?=null,index:Int=0) {
   val intent=Intent(context,TrackingService::class.java).setAction(action).putExtra("search_id",searchId).putExtra("choice_index",index)
   if(action==START||action==RESUME||action==PLAYBACK)context.startForegroundService(intent)else context.startService(intent)
  }
 }
 private lateinit var repository:TripRepository
 private lateinit var store:Store
 private lateinit var manager:LocationManager
 private var listening=false
 private var locationQuality=TrackingLocationQuality()
 private var lastObservation:JSONObject?=null
 private val ambientScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
 @Volatile private var shuttingDown=false
 @Volatile private var pauseRequested=false
 // Commands, GPS fixes, validation and SQLite share one ordered worker queue.
 // The main thread only promotes the service promptly and dispatches commands.
 private val worker=HandlerThread("CompanionTracking").apply {start()}
 private val handler=Handler(worker.looper)
 private var startingSearchId:String?=null
 private val monitor=object:Runnable {override fun run(){
  try {
   val playback=store.json(ACTIVE_PLAYBACK)
   if(playback!=null){
    if(JourneyPlaybackClock.progress(playback,SystemClock.elapsedRealtime(),repository.boot())>=1&&playback.optBoolean("running")){JourneyPlaybackClock.setRunning(playback,false,SystemClock.elapsedRealtime(),repository.boot());store.put(ACTIVE_PLAYBACK,playback);TripUpdates.changed()}
    updateNotification();if(running)handler.postDelayed(this,10000);return
   }
   val pair=repository.active()
   if(pair==null){stopSelf();return}
   if(pair.second.optString("status")=="recording"){
    if(!hasPermission()){repository.changeActive {TrackingEngine.breakSegment(it,"permission_lost",now());TrackingEngine.pause(it,now())};removeUpdates();updateNotification("Location permission removed")}
    else if(listOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER).none {runCatching {manager.isProviderEnabled(it)}.getOrDefault(false)}){
     repository.changeActive {TrackingEngine.breakSegment(it,"provider_unavailable",now());TrackingEngine.pause(it,now())};removeUpdates();updateNotification("Location is disabled; resume when ready")
    }
   }
   updateNotification()
  }catch(e:Exception){fail(e)}
  if(running)handler.postDelayed(this,10000)
 }}
 override fun onCreate(){super.onCreate();store=Store(this);repository=TripRepository(this,store);manager=getSystemService(LOCATION_SERVICE) as LocationManager;createChannel()}
 override fun onBind(intent:Intent?):IBinder?=null
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
  try {
   if(intent?.action in listOf(PAUSE,FINISH,CANCEL)) {
    // Stop receiving fixes immediately; durable state changes remain ordered
    // with SQLite writes. A large saved search must not delay GPS shutdown.
    pauseRequested=true
    removeUpdates()
   }
   if(intent?.action==START)require(store.json(ACTIVE_PLAYBACK)==null){"Finish the route playback first"}
   val playback=intent?.action==PLAYBACK||(store.json(ACTIVE_PLAYBACK)!=null&&intent?.action!=START)
   if(intent?.action==START||intent?.action==RESUME||playback){
    pauseRequested=false
    if(!playback)require(hasPermission()) {"Location permission required"}
    val notification=notification()
    if(Build.VERSION.SDK_INT>=34&&playback)startForeground(71,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    else if(Build.VERSION.SDK_INT>=29&&!playback)startForeground(71,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    else startForeground(71,notification)
    running=true
   }
   handler.post {handleCommand(intent,startId)}
  }catch(e:Exception){handler.post {startingSearchId=intent?.getStringExtra("search_id");fail(e)}}
  return if(store.json(ACTIVE_PLAYBACK)!=null)START_STICKY else START_NOT_STICKY
 }
 private fun handleCommand(intent:Intent?,startId:Int) {
  try {
   val playback=store.json(ACTIVE_PLAYBACK)
   val action=intent?.action?:if(playback!=null)PLAYBACK else error("Tracking must be resumed from the app")
   if(action==PLAYBACK||(playback!=null&&action!=START)) {
    if(playback==null){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId);return}
    when(action){
     PLAYBACK->if(playback.optInt("boot_count",-1)!=(repository.boot()?:-2))JourneyPlaybackClock.setRunning(playback,false,SystemClock.elapsedRealtime(),repository.boot())
     PAUSE->JourneyPlaybackClock.setRunning(playback,false,SystemClock.elapsedRealtime(),repository.boot())
     RESUME->JourneyPlaybackClock.setRunning(playback,true,SystemClock.elapsedRealtime(),repository.boot())
     FINISH,CANCEL->{store.remove(ACTIVE_PLAYBACK);TripUpdates.changed();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId);return}
     else->error("Unsupported playback action")
    }
    store.put(ACTIVE_PLAYBACK,playback);TripUpdates.changed();updateNotification();handler.removeCallbacks(monitor);handler.postDelayed(monitor,10000);return
   }
   // Repeated taps/permission callbacks must not interrupt an existing journey.
   if(action==START&&repository.active()!=null){updateNotification();return}
   if(action==RESUME&&repository.active()==null){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId);return}
   when(action){
    START->{startingSearchId=intent!!.getStringExtra("search_id")?:error("Missing search");repository.start(startingSearchId!!,intent.getIntExtra("choice_index",0),true);if(!pauseRequested)subscribe();startingSearchId=null}
    RESUME->{repository.changeActive {if(it.optString("status")=="paused")TrackingEngine.resume(it,now())};if(!pauseRequested)subscribe()}
    PAUSE->{repository.changeActive {if(it.optString("status")=="recording"){retainLastObservation(it);TrackingEngine.pause(it,now())}};TripUpdates.position.value=null;removeUpdates()}
    FINISH,CANCEL->{repository.changeActive {retainLastObservation(it);TrackingEngine.finish(it,now(),SystemClock.elapsedRealtime(),repository.boot(),action==CANCEL)};TripUpdates.position.value=null;removeUpdates();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId);return}
    else->error("Unsupported tracking action")
   }
   updateNotification();handler.removeCallbacks(monitor);handler.postDelayed(monitor,10000)
  }catch(e:Exception){fail(e)}
 }
 private fun hasPermission()=checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
 @SuppressLint("MissingPermission") private fun subscribe(){
  removeUpdates();require(hasPermission());locationQuality=TrackingLocationQuality();lastObservation=null;TripUpdates.position.value=null
  val providers=listOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER).filter {runCatching {manager.isProviderEnabled(it)}.getOrDefault(false)}
  require(providers.isNotEmpty()) { "Enable location services, then resume" }
  providers.forEach {manager.requestLocationUpdates(it,2000L,0f,this,worker.looper)};listening=true
 }
 private fun removeUpdates(){if(::manager.isInitialized)runCatching {manager.removeUpdates(this)};listening=false}
 private fun retainLastObservation(trip:JSONObject){
  val point=lastObservation?:return
  if(trip.optString("status")!="recording")return
  val age=SystemClock.elapsedRealtime()-point.getLong("monotonic_ms")
  val tracking=trip.getJSONObject("tracking")
  if(point.getInt("segment_index")!=tracking.getInt("segment_index"))return
  val previous=tracking.objects("points").lastOrNull()
  if(age in 0..30000&&(previous==null||point.getLong("monotonic_ms")>previous.getLong("monotonic_ms")))
   TrackingEngine.fix(trip,point.copyJson(),forceRetain=true)
 }
 override fun onLocationChanged(location:Location){
  if(pauseRequested)return
  try {
   val active=repository.active()?:return
   if(active.second.optString("status")!="recording")return
   val point=obj("lat" to location.latitude,"lon" to location.longitude,"accuracy_m" to if(location.hasAccuracy())location.accuracy.toDouble()else Double.MAX_VALUE,"recorded_at" to Instant.ofEpochMilli(location.time).toString(),"received_at" to now(),"monotonic_ms" to location.elapsedRealtimeNanos/1000000,"provider" to (location.provider?:"unknown"),"mock" to location.isFromMockProvider,"segment_index" to 0)
   var sample:JSONObject?=null
   var observed:JSONObject?=null
   repository.changeActive {trip->
    val tracking=trip.getJSONObject("tracking")
    val rejected=tracking.getInt("rejected_fixes")
    if(locationQuality.suppressCoarseEcho(point.getString("provider"),point.getDouble("accuracy_m"),point.getLong("monotonic_ms")))
     tracking.put("rejected_fixes",rejected+1)
    else {
     val retained=TrackingEngine.fix(trip,point)
     if(retained||tracking.getInt("rejected_fixes")==rejected)
      observed=point.copyJson().put("segment_index",tracking.getInt("segment_index"))
     // A valid stationary observation can refresh provider quality even when
     // the distance floor omits it from the saved polyline. Invalid fixes cannot.
     if(point.getString("provider")=="gps"&&(retained||tracking.getInt("rejected_fixes")==rejected))
      locationQuality.observeValidGps(point.getDouble("accuracy_m"),point.getLong("monotonic_ms"))
     if(retained)sample=JourneyContextCapture.begin(trip,point)
    }
   }
   observed?.let {lastObservation=it;TripUpdates.position.value=LiveTrackingPosition(active.second.getString("journey_id"),it)}
   sample?.let {capture->sampleAmbient(active.second.getString("journey_id"),point,capture)}
   updateNotification()
  }catch(e:Exception){fail(e)}
 }
 override fun onProviderDisabled(provider:String){if(repository.active()?.second?.optString("status")=="recording")runCatching {repository.changeActive {TrackingEngine.breakSegment(it,"provider_unavailable",now(),provider)}};updateNotification()}
 override fun onProviderEnabled(provider:String)=Unit
 private fun sampleAmbient(tripId:String,point:JSONObject,capture:JSONObject) {
  // Network requests run outside the ordered tracking/SQLite worker: controls
  // and accepted fixes continue even when Orion is slow or unavailable.
  ambientScope.launch {
   val result=runCatching {Api(store).call("/api/companion/longitudinal/context/sample",obj("lat" to point.getDouble("lat"),"lon" to point.getDouble("lon"),"weather_model_version" to "longitudinal-weather-intensity-v2"),20000)}
   if(!isActive||shuttingDown)return@launch
   val at=now()
   handler.post {
    if(shuttingDown)return@post
    runCatching {repository.changeActive {trip->if(trip.optString("journey_id")==tripId)JourneyContextCapture.complete(trip,capture.getString("sample_id"),result.getOrNull(),at,if(result.isFailure)"provider_or_network_unavailable"else null)}}
     .onFailure {android.util.Log.w("CompanionContext","Could not persist ambient sample")}
   }
  }
 }
 @Deprecated("Deprecated Android callback") override fun onStatusChanged(provider:String?,status:Int,extras:Bundle?)=Unit
 private fun fail(error:Exception){
  android.util.Log.e("CompanionTracking","Tracking action failed",error)
  TripUpdates.errors.tryEmit(TrackingFailure(startingSearchId,error.message?:"Tracking interrupted"))
  startingSearchId=null
  runCatching {repository.changeActive {if(it.optString("status")=="recording"){TrackingEngine.pause(it,now(),true);it.getJSONObject("tracking").objects("events").last().put("detail",(error.message?:"Tracking interrupted").take(300))}}}
  runCatching {updateNotification(error.message?:"Tracking interrupted")}
  removeUpdates();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
 }
 private fun de()=store.get("imiq.experimental.language")=="de"
 private fun createChannel(){val nm=getSystemService(NOTIFICATION_SERVICE) as NotificationManager;nm.createNotificationChannel(NotificationChannel("companion-journey","Companion journey",NotificationManager.IMPORTANCE_LOW))}
 private fun notification(message:String?=null):Notification {
  // Status/notification metrics are updated atomically with the journey.
  // Rendering a notification must not parse the whole route evidence again.
  val playback=if(::store.isInitialized)store.json(ACTIVE_PLAYBACK)else null
  val pointer=if(::store.isInitialized)store.json(ACTIVE_TRIP)else null
  val paused=if(playback!=null)!playback.optBoolean("running")else pointer?.optString("status")=="paused"
  val content=Intent(this,MainActivity::class.java).setAction("companion.OPEN_ACTIVE_TRIP").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
  val open=PendingIntent.getActivity(this,71,content,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  fun control(action:String,request:Int)=PendingIntent.getService(this,request,Intent(this,TrackingService::class.java).setAction(action),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  val title=if(playback!=null){if(de())"Routenwiedergabe · Ohne GPS"else"Route playback · No GPS"}else if(paused){if(de())"Reise pausiert"else"Journey paused"}else if(de())"Reise wird aufgezeichnet"else"Recording your journey"
  val distance=if(playback!=null){if(paused){if(de())"Pausiert"else"Paused"}else if(de())"Geplante Route · läuft im Hintergrund"else"Planned route · continues in background"}else pointer?.number("distance_m")?.let {"${metric(it/1000)} km"}?:if(de())"Warte auf GPS"else"Waiting for GPS"
  val elapsed=playback?.let {JourneyPlaybackClock.elapsed(it,SystemClock.elapsedRealtime(),repository.boot())}
  val finish=if(playback!=null)PendingIntent.getActivity(this,74,Intent(this,MainActivity::class.java).setAction("companion.FINISH_PLAYBACK").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)else control(FINISH,73)
  return Notification.Builder(this,"companion-journey").setSmallIcon(R.drawable.companion_product_icon).setContentTitle(title).setContentText(message?:distance).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_NAVIGATION).setVisibility(Notification.VISIBILITY_PRIVATE).setWhen(if(elapsed!=null)System.currentTimeMillis()-(elapsed*1000).toLong()else System.currentTimeMillis()).setUsesChronometer(elapsed!=null&&!paused)
   .addAction(Notification.Action.Builder(null,if(paused){if(de())"Fortsetzen"else"Resume"}else if(de())"Pause"else"Pause",control(if(paused)RESUME else PAUSE,72)).build())
   .addAction(Notification.Action.Builder(null,if(de())"Beenden"else"Finish",finish).build()).build()
 }
 private fun updateNotification(message:String?=null){if(running)(getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(71,notification(message))}
 override fun onDestroy(){
  shuttingDown=true;ambientScope.cancel()
  TripUpdates.position.value=null
  running=false;handler.removeCallbacksAndMessages(null)
  handler.post {
   removeUpdates()
   runCatching {repository.changeActive {JourneyContextCapture.interruptPending(it);if(it.optString("status")=="recording")TrackingEngine.pause(it,now(),true)}}
   store.close();worker.quitSafely()
  }
  super.onDestroy()
 }
}
