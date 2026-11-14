package de.ovgu.imiq.companion

import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import org.json.JSONObject

/** The chosen journey owns its map; a later search cannot replace its route. */
@Composable fun TrackingJourneyScreen(model:CompanionModel,record:JSONObject,trip:JSONObject) {
 val context=LocalContext.current;val status=trip.getString("status");val paused=status=="paused"
 val tracking=trip.getJSONObject("tracking")
 var pendingCommand by remember(trip.getString("journey_id")){mutableStateOf<String?>(null)}
 LaunchedEffect(status){pendingCommand=null}
 LaunchedEffect(pendingCommand){
  if(pendingCommand!=null){delay(15000);if(pendingCommand!=null){pendingCommand=null;model.error=model.text("The journey control is taking longer than expected. Check its status before trying again.","Die Reisesteuerung dauert länger als erwartet. Prüfe den Status, bevor du es erneut versuchst.")}}
 }
 var tick by remember {mutableLongStateOf(SystemClock.elapsedRealtime())}
 LaunchedEffect(status){while(true){tick=SystemClock.elapsedRealtime();delay(1000)}}
 val elapsed=tracking.number("elapsed_seconds")?:tracking.number("started_monotonic_ms")?.takeIf {tracking.optInt("boot_count",-1)==model.trips.boot()}?.let {((tick-it)/1000).coerceAtLeast(0.0)}
 val selection=trip.getJSONObject("selection")
 val card=remember(trip.getString("journey_id")){cards(record.optJSONObject("result")).find {it.id=="${selection.optInt("routing_rank")}-${selection.optString("mode_key")}"}}
 val route=remember(trip.getString("journey_id")){mapRoutes(record.optJSONObject("result")).find {it.id==card?.id}}
 val live by TripUpdates.position.collectAsState()
 val latest=live?.takeIf {it.journeyId==trip.getString("journey_id")&&!paused}?.point?:tracking.objects("points").lastOrNull()
 val guidance=navigationGuidance(route,latest,tick,paused,hasLocationAccess(context)&&locationServicesEnabled(context),tracking.optInt("boot_count",-1)==model.trips.boot())
 fun action(value:String){
  if(pendingCommand!=null)return
  pendingCommand=value
  runCatching {TrackingService.action(context,value)}.onFailure {pendingCommand=null;model.error=it.message}
 }
 NavigationJourneyLayout(model,record,card,navigationElapsed(elapsed),tracking.number("distance_m"),guidance,
  pending=pendingCommand!=null,keepAwake=!paused,
  onPauseResume={action(if(paused)TrackingService.RESUME else TrackingService.PAUSE)},
  onFinish={action(TrackingService.FINISH)},onCancel={action(TrackingService.CANCEL)},onMinimize={model.tab="home"}) {viewport->
   NativeJourneyMap(model,record,trip,compact=true,navigation=viewport)
 }
}
