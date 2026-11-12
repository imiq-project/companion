package de.ovgu.imiq.companion

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

fun tripStatus(model:CompanionModel,status:String)=when(status){
 "recording"->model.text("Journey in progress","Reise läuft")
 "paused"->model.text("Journey paused · GPS off","Reise pausiert · GPS aus")
 "awaiting_confirmation"->model.text("Experience awaiting confirmation","Erfahrung bestätigen")
 "confirmed"->model.text("Experience confirmed","Erfahrung bestätigt")
 else->model.text("Tracking cancelled","Aufzeichnung abgebrochen")
}
@Composable fun TripBanner(model:CompanionModel) {
 val store=model.store
 val playback=remember(model.revision){store.json(ACTIVE_PLAYBACK)}
 if(playback!=null){
  Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically){Text(model.text("Route playback · No GPS","Routenwiedergabe · Ohne GPS"),Modifier.weight(1f),color=Mint);TextButton({model.openPlayback()}){Text(model.text("Open","Öffnen"))}}
  return
 }
 val banner by produceState<JourneyBanner?>(null,store,model.revision) {
  try {
   value=withContext(Dispatchers.IO) {
    val pair=model.trips.active()?:store.firstPendingJourney()
    pair?.let {JourneyBanner(it.first.getString("search_id"),it.second.getString("journey_id"),it.second.getString("status"))}
   }
  }catch(e:CancellationException){throw e}catch(e:Exception){model.error=e.message}
 }
 val item=banner?:return
 Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp)) {
 Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
  Text(tripStatus(model,item.status),Modifier.weight(1f),color=Mint)
  TextButton({model.openTrip(item.searchId,item.journeyId)}){Text(if(item.status=="paused")model.text("Manage","Verwalten")else model.text("Open","Öffnen"))}
 }
 if(item.status=="paused")Text(model.text("A previous journey is paused. Resume or cancel it to continue.","Eine frühere Reise ist pausiert. Fortsetzen oder abbrechen, um weiterzumachen."),style=MaterialTheme.typography.bodySmall,color=MutedText)
 }
}
private data class JourneyBanner(val searchId:String,val journeyId:String,val status:String)
@Composable fun TripScreen(model:CompanionModel,onTrackStart:(String,Int)->Unit) {
 val revision=model.revision
 val store=model.store
 val context=LocalContext.current
 val searchId=model.tripSearchId?:model.historyId
 val journeyId=model.tripId
 var recordSnapshot by remember(store,searchId,journeyId){mutableStateOf<JSONObject?>(null)}
 var recordLoading by remember(store,searchId,journeyId){mutableStateOf(searchId!=null)}
 LaunchedEffect(store,searchId,journeyId,revision) {
   try {recordSnapshot=model.takePreparedTripRecord(searchId)?:withContext(Dispatchers.IO){searchId?.let {store.recordForJourney(it,journeyId=journeyId)}};recordLoading=false}
  catch(e:CancellationException){throw e}catch(e:Exception){model.error=e.message;recordLoading=false}
 }
 if(recordLoading&&recordSnapshot==null){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator(color=Mint)};return}
 val record=recordSnapshot
 val trips=record?.objects("journeys").orEmpty()
 val trip=model.tripId?.let {id->trips.find {it.getString("journey_id")==id}}?:trips.lastOrNull {it.optInt("choice_index")== (record?.objects("choices")?.lastIndex?:-1)}
 LaunchedEffect(searchId,trip?.optString("journey_id"),model.tripStartingSearchId){
  if(model.tripStartingSearchId==searchId){
   if(trip!=null)model.tripStartingSearchId=null
   else {delay(15000);if(model.tripStartingSearchId==searchId){model.tripStartingSearchId=null;model.routeView="alternatives";model.error=model.text("The journey could not start. Please try again.","Die Reise konnte nicht starten. Bitte erneut versuchen.")}}
  }
 }
 if(model.tripStartingSearchId==searchId&&trip==null){
  Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)){CircularProgressIndicator(color=Mint);Text(model.text("Starting journey…","Reise wird gestartet…"),color=Pale)}}
  return
 }
 var cancel by remember {mutableStateOf(false)}
 var validate by remember {mutableStateOf(false)}
 fun act(action:String){runCatching {TrackingService.action(context,action)}.onFailure {model.error=it.message}}
 if(record!=null&&trip!=null&&trip.optString("status") in liveTripStates){TrackingJourneyScreen(model,record,trip);return}
 if(record!=null&&trip?.optString("status")=="awaiting_confirmation"){key(trip.getString("journey_id")){ExperienceForm(model,record,trip)};return}
 ScreenColumn {
  Heading(if(trip?.optString("status") in listOf("awaiting_confirmation","cancelled"))model.text("Experience","Erfahrung")else model.text("Your real journey","Deine tatsächliche Reise"))
  if(record==null||record.objects("choices").isEmpty()){
   Copy(model.text("Choose a route first. You can then start recording or confirm a trip manually.","Wähle zuerst einen Weg. Danach kannst du aufzeichnen oder die Reise manuell bestätigen."));return@ScreenColumn
  }
  val destination=record.optJSONObject("places")?.optJSONObject("destination")?.optString("label").orEmpty()
  Copy(destination,Gold)
  if(trip==null){
   val choice=record.objects("choices").last();val index=record.objects("choices").lastIndex
   val card=cards(record.optJSONObject("result")).find {it.id=="${choice.optInt("routing_rank")}-${choice.optString("mode_key")}"}
   PixelCard {
    Heading(modeName(choice.getString("mode_key"),model.de))
    Copy(model.text("Record time and approximate distance. GPS points stay on this phone and work without a backend connection.","Zeichne Zeit und ungefähre Strecke auf. GPS-Punkte bleiben auf diesem Telefon; die Aufzeichnung funktioniert ohne Backend."))
    Copy(model.text("Recording continues when the screen is off. You control when it starts and ends.","Die Aufzeichnung läuft bei ausgeschaltetem Bildschirm weiter. Du bestimmst Start und Ende."))
    PixelButton(model.text("Start journey with GPS","Reise mit GPS starten"),{onTrackStart(record.getString("search_id"),index)},Modifier.fillMaxWidth(),enabled=model.trips.active()==null&&card?.expired()!=true,primary=true)
    if(card?.expired()==true)Copy(model.text("This departure has passed. Search again before starting GPS tracking.","Diese Abfahrt ist vorbei. Suche vor dem GPS-Start erneut."))
    PixelButton(model.text("Confirm without GPS","Ohne GPS bestätigen"),{model.manualTrip(record.getString("search_id"),index)},Modifier.fillMaxWidth(),enabled=model.trips.active()==null)
    model.trips.active()?.let {active->Copy(if(active.second.optString("status")=="paused")model.text("A previous journey is paused; GPS is not recording. Manage it to resume, finish or cancel before starting another.","Eine frühere Reise ist pausiert; GPS zeichnet nicht auf. Fortsetzen, beenden oder abbrechen, bevor du eine neue startest.")else model.text("Another journey is recording GPS. Open it to pause or finish.","Eine andere Reise zeichnet GPS auf. Öffnen, um sie zu pausieren oder zu beenden."));PixelButton(model.text("Manage previous journey","Vorherige Reise verwalten"),{model.openTrip(active.first.getString("search_id"),active.second.getString("journey_id"))},Modifier.fillMaxWidth(),primary=true)}
   }
  }else{
   val status=trip.getString("status");val tracking=trip.getJSONObject("tracking")
   var clock by remember {mutableLongStateOf(SystemClock.elapsedRealtime())}
   LaunchedEffect(status){while(status in liveTripStates){clock=SystemClock.elapsedRealtime();delay(1000)}}
   val elapsed=tracking.number("elapsed_seconds")?:if(!tracking.isNull("started_monotonic_ms")&&tracking.optInt("boot_count",-1)==model.trips.boot())((clock-tracking.getLong("started_monotonic_ms"))/1000.0).coerceAtLeast(0.0)else null
   if(status=="awaiting_confirmation")PixelCard {
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
     PixelIcon(modelMode(trip.getJSONObject("selection").getString("mode_key")),Modifier.size(40.dp))
     Column(Modifier.weight(1f)) {
      Heading(modeName(trip.getJSONObject("selection").getString("mode_key"),model.de))
      Text(model.text("Ready for your experience","Bereit für deine Erfahrung"),style=MaterialTheme.typography.bodySmall,color=Mint)
     }
    }
    Text(model.text("Recorded time: ","Aufgezeichnete Zeit: ")+metric(elapsed?.div(60))+" min · "+metric(tracking.number("distance_m")?.div(1000))+" km",style=MaterialTheme.typography.bodySmall,color=MutedText)
   }else PixelCard {
    Heading(tripStatus(model,status));Copy(modeName(trip.getJSONObject("selection").getString("mode_key"),model.de),Mint)
    if(status=="paused")Copy(model.text("GPS is not recording. This journey remains saved until you resume, finish or cancel it. Cancel keeps the history and lets you start a new journey.","GPS zeichnet nicht auf. Diese Reise bleibt gespeichert, bis du fortsetzt, beendest oder abbrichst. Abbrechen erhält den Verlauf und erlaubt eine neue Reise."),Gold)
    Copy(model.text("Elapsed time: ","Vergangene Zeit: ")+metric(elapsed?.div(60))+" min")
    Copy(model.text("Observed distance: ","Beobachtete Strecke: ")+metric(tracking.number("distance_m")?.div(1000))+" km")
    Copy("${tracking.getJSONArray("points").length()} "+model.text("valid fixes","gültige Punkte")+" · ${tracking.getInt("gap_count")} "+model.text("gaps / pauses","Lücken / Pausen"),MutedText)
    if(status=="paused")tracking.objects("events").lastOrNull()?.optString("detail")?.takeIf {it.isNotEmpty()}?.let {Copy(it,Gold)}
    Copy(model.text("Distance is approximate. Gaps are not joined; GPS cannot confirm comfort or transport mode.","Die Strecke ist ungefähr. Lücken werden nicht verbunden; GPS bestätigt weder Komfort noch Verkehrsmittel."),MutedText)
    if(status=="recording"&&tracking.getJSONArray("points").length()==0)Copy(model.text("Waiting for an accurate location (50 m or better). Enable precise location; the distance stays unknown until sufficient fixes arrive.","Warte auf einen genauen Standort (50 m oder besser). Aktiviere den genauen Standort; die Strecke bleibt bis zu ausreichenden Punkten unbekannt."))
    if(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED&&status in liveTripStates)Copy(model.text("Only approximate location is allowed. You can enable precise location in Android settings.","Nur ungefährer Standort erlaubt. Du kannst den genauen Standort in den Android-Einstellungen aktivieren."))
    if(status in liveTripStates){
     PixelButton(if(status=="recording")model.text("Pause recording","Aufzeichnung pausieren")else model.text("Resume recording","Aufzeichnung fortsetzen"),{act(if(status=="recording")TrackingService.PAUSE else TrackingService.RESUME)},Modifier.fillMaxWidth())
     PixelButton(model.text("Finish journey →","Reise beenden →"),{act(TrackingService.FINISH)},Modifier.fillMaxWidth(),primary=true)
     TextButton({cancel=true}){Text(model.text("Cancel recording","Aufzeichnung abbrechen"))}
    }
   }
   if(status=="cancelled")PixelCard {
    Heading(model.text("Journey cancelled","Reise abgebrochen"))
    Copy(model.text("Your recorded journey remains in history. No completed experience has been confirmed.","Deine aufgezeichnete Reise bleibt im Verlauf. Es wurde keine abgeschlossene Erfahrung bestätigt."))
    PixelButton(model.text("Plan another journey","Neue Reise planen"),{model.tripSearchId=null;model.tripId=null;model.routeView="search"},Modifier.fillMaxWidth(),primary=true)
   }
   if(status=="confirmed"){
    val experience=trip.getJSONObject("experience")
    PixelCard {Heading(model.text("Thanks — your experience is saved","Danke — deine Erfahrung ist gespeichert"));Copy(when(experience.getString("completion")){"as_planned"->model.text("Travelled as planned","Wie geplant gereist");"changed"->model.text("Travelled with changes","Mit Änderungen gereist");else->model.text("Trip not taken","Reise nicht durchgeführt")});experience.optJSONObject("comfort_observation")?.let {o->Copy(model.text("Physical comfort","Körperlicher Komfort")+" · "+modeName(o.getString("mode"),model.de)+": ${o.getInt("rating")}/7 ("+model.text("help–hinder","hilft–erschwert")+")")};experience.optJSONObject("need_satisfaction_raw_1_to_7")?.let {answers->answers.keys().forEach {key->Copy("${goalCopy(goalKeys[needKeys.indexOf(key)],model.de).getString("title")}: ${answers.getInt(key)}/7")}};experience.optJSONObject("mode_valences_raw_minus3_to_3")?.let {answers->answers.keys().forEach {key->Copy("${modeName(key,model.de)}: ${answers.getInt(key)}")}};experience.getJSONObject("ratings").keys().forEach {key->Copy("${ratingLabel(model,key)}: ${experience.getJSONObject("ratings").getInt(key)}/7")};if(experience.getString("changes").isNotEmpty())Copy(experience.getString("changes"));Copy(model.text("Your original declarations are preserved. This is your reported experience, separate from the route simulation.","Deine ursprünglichen Angaben bleiben erhalten. Dies ist deine berichtete Erfahrung, getrennt von der Routensimulation."),MutedText)}
   }
   if(status in listOf("confirmed","cancelled"))JourneyClimate(model,trip)
   Disclosure(model.text("Data and validation","Daten und Validierung")) {
    val local=runCatching {model.trips.contract.validate(record,trip)}
    Copy(if(local.isSuccess)model.text("✓ Local contract valid","✓ Lokaler Vertrag gültig")else model.text("Contract validation failed","Vertragsprüfung fehlgeschlagen"))
    Copy("${trip.getString("journey_id")}\n${trip.getString("capture_mode")} · ${trip.optString("started_at")} → ${trip.optString("ended_at")}",MutedText)
    Copy(model.text("Route predictions use the conditions saved at selection. Environmental readings collected during recording do not by themselves measure your comfort or emotion.","Routenvorhersagen verwenden die bei der Auswahl gespeicherten Bedingungen. Umweltmesswerte während der Aufzeichnung messen allein weder deinen Komfort noch dein Gefühl."),MutedText)
    val receipt=model.store.json("imiq.experimental.journey-validation.v1.${trip.getString("journey_id")}")
    if(receipt?.optString("fingerprint")==canonical(trip))Copy(if(receipt.optJSONObject("receipt")?.optBoolean("stored_on_server")==true)model.text("✓ Journey saved on backend","✓ Reise im Backend gespeichert")else model.text("✓ Backend contract valid; not saved on backend","✓ Backend-Vertrag gültig; nicht im Backend gespeichert"),Mint)
    PixelButton(model.text("Validate with backend","Mit Backend validieren"),{validate=true},Modifier.fillMaxWidth(),enabled=!model.busy&&status !in liveTripStates)
    Disclosure(model.text("Recording events","Aufzeichnungsereignisse")){tracking.objects("events").forEach {Copy("${it.getString("at")} · ${it.getString("kind")}")};Copy(model.text("Rejected fixes: ","Verworfene Punkte: ")+tracking.getInt("rejected_fixes"))}
   }
  }
  if(trips.size>1)Disclosure(model.text("Other saved experiences","Weitere Erfahrungen")){trips.forEach {other->PixelButton(tripStatus(model,other.getString("status"))+" · ${other.getString("created_at")}",{model.openTrip(record.getString("search_id"),other.getString("journey_id"))})}}
  PixelButton(model.text("Back to routes","Zurück zu den Wegen"),{model.openSavedAlternatives(record.getString("search_id"))},Modifier.fillMaxWidth())
 }
 if(cancel)AlertDialog(onDismissRequest={cancel=false},title={Heading(model.text("Cancel tracking?","Aufzeichnung abbrechen?"))},text={Copy(model.text("Recorded points remain in history. This will not count as a confirmed experience.","Aufgezeichnete Punkte bleiben im Verlauf. Dies zählt nicht als bestätigte Erfahrung."))},confirmButton={TextButton({act(TrackingService.CANCEL);cancel=false}){Text(model.text("Cancel tracking","Abbrechen"))}},dismissButton={TextButton({cancel=false}){Text(model.text("Keep recording","Weiter aufzeichnen"))}})
 if(validate&&record!=null&&trip!=null)AlertDialog(onDismissRequest={validate=false},title={Heading(model.text("Send journey to backend?","Reise an Backend senden?"))},text={Copy(model.text("This sends this journey, including GPS points, and the saved search to your configured backend for validation and storage when enabled. Your profile is not updated. The result confirms whether the journey was stored.","Diese Reise einschließlich GPS-Punkten und gespeicherter Suche wird zur Prüfung und bei aktivierter Speicherung an dein konfiguriertes Backend gesendet. Dein Profil wird nicht verändert. Das Ergebnis bestätigt, ob die Reise gespeichert wurde."))},confirmButton={TextButton({validate=false;model.validateTrip(record,trip)}){Text(model.text("Send","Senden"))}},dismissButton={TextButton({validate=false}){Text(model.text("Cancel","Abbrechen"))}})
}
fun ratingLabel(model:CompanionModel,key:String)=when(key){"comfort"->model.text("Comfort","Komfort");"speed"->model.text("Time satisfaction","Zufriedenheit mit der Dauer");"physical"->model.text("Physical activity suitability","Passende körperliche Aktivität");else->model.text("Reliability","Zuverlässigkeit")}
