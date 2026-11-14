package de.ovgu.imiq.companion

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable private fun MenuEntry(title:String,description:String,icon:String,onClick:()->Unit) {
 Surface(onClick=onClick,color=Ink,shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth()) {
  Row(Modifier.heightIn(min=72.dp).padding(horizontal=8.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
   PixelIcon(icon,Modifier.size(26.dp))
   Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
    Text(title,style=MaterialTheme.typography.titleSmall)
    Text(description,style=MaterialTheme.typography.bodySmall,color=MutedText)
   }
   Text("›",style=MaterialTheme.typography.titleLarge,color=Mint)
  }
 }
}

@Composable fun SettingsScreen(model:CompanionModel) {
 var section by remember {mutableStateOf("menu")}
 var confirm by remember {mutableStateOf<String?>(null)}
 val canManage=!model.busy&&!model.panelLoginBusy&&model.store.json(ACTIVE_TRIP)==null&&model.store.json(ACTIVE_PLAYBACK)==null
 BackHandler(section!="menu") {section=if(section in listOf("goals","emissions","availability"))"personalization" else "menu"}
 when(section) {
  "goals"->GoalPersonalizationScreen(model){section="personalization"}
  "history"->Column {
   TextButton({section="menu"}){Text(model.text("← Settings","← Einstellungen"))}
   HistoryScreen(model)
  }
  else->ScreenColumn {
   if(section=="menu") {
    Heading(model.text("Settings","Einstellungen"))
    MenuEntry(model.text("Personalization","Personalisierung"),model.text("Weekly goals, CO₂ emissions and available transport","Wochenziele, CO₂-Emissionen und verfügbare Verkehrsmittel"),"preferences"){section="personalization"}
    HorizontalDivider(color=Edge)
    MenuEntry(model.text("Journey history","Reiseverlauf"),model.text("Revisit your routes and recommendations","Routen und Empfehlungen erneut ansehen"),"time"){section="history"}
    MenuEntry(model.text("My data","Meine Daten"),model.text("Manage data saved on this phone","Auf diesem Telefon gespeicherte Daten verwalten"),"passport"){section="data"}
    MenuEntry(model.text("About Companion","Über Companion"),model.text("App information and credits","App-Informationen und Quellen"),"info"){section="about"}
    HorizontalDivider(color=Edge,modifier=Modifier.padding(vertical=8.dp))
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
     OutlinedButton({confirm="renew"},Modifier.weight(1f).heightIn(min=60.dp),enabled=canManage,shape=RoundedCornerShape(12.dp)) {
      Text(model.text("Create new Passport","Neuen Passport erstellen"))
     }
     OutlinedButton(model::signOutPanel,Modifier.weight(1f).heightIn(min=60.dp),enabled=canManage&&model.panelIdentity!=null,
      shape=RoundedCornerShape(12.dp),border=BorderStroke(1.dp,if(canManage)CompanionPalette.Burgundy else MutedText),
      colors=ButtonDefaults.outlinedButtonColors(contentColor=CompanionPalette.Burgundy,containerColor=CompanionPalette.Burgundy.copy(alpha=.06f))) {
      Text(model.text("Sign out","Abmelden"))
     }
    }
   } else {
    TextButton({section=if(section in listOf("emissions","availability"))"personalization" else "menu"}) {
     Text(if(section in listOf("emissions","availability"))model.text("← Personalization","← Personalisierung") else model.text("← Settings","← Einstellungen"))
    }
    when(section) {
     "personalization"->{
      Heading(model.text("Personalization","Personalisierung"))
      Copy(model.text("Set what matters to you and how you travel.","Lege fest, was dir wichtig ist und wie du unterwegs bist."),MutedText)
      MenuEntry(model.text("Weekly goals","Wochenziele"),model.text("Your priorities for this week's journeys","Deine Prioritäten für die Wege dieser Woche"),"env"){section="goals"}
      HorizontalDivider(color=Edge)
      MenuEntry(model.text("CO₂ emission profile","CO₂-Emissionsprofil"),model.text("Vehicle details for more specific emission estimates","Fahrzeugdaten für genauere Emissionsschätzungen"),"car"){section="emissions"}
      HorizontalDivider(color=Edge)
      MenuEntry(model.text("Available transport","Verfügbare Verkehrsmittel"),model.text("Choose the modes you can use for new journeys","Wähle Verkehrsmittel für neue Wege"),"travel"){section="availability"}
     }
     "emissions"->{
      Heading(model.text("CO₂ emission profile","CO₂-Emissionsprofil"))
      Copy(model.text("Personalize your vehicle details to refine estimated CO₂ emissions for car routes.","Passe deine Fahrzeugdaten an, um geschätzte CO₂-Emissionen für Autorouten zu verfeinern."),MutedText)
      VehicleProfileSettings(model)
     }
     "availability"->{
      Heading(model.text("Available transport","Verfügbare Verkehrsmittel"))
      Copy(model.text("Choose which modes are available for new route searches. Keep at least one enabled.","Wähle verfügbare Verkehrsmittel für neue Routensuchen. Mindestens eines muss aktiviert bleiben."),MutedText)
      val settings=model.settings()
      val available=settings.optJSONObject("journeyAvailability")?:model.profile()?.getJSONObject("profile")?.getJSONObject("availability")?:JSONObject()
      modes.forEach {mode->
       Row(Modifier.fillMaxWidth().heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        PixelIcon(mode)
        Text(modeName(mode,model.de),Modifier.weight(1f))
        Switch(available.optBoolean(mode),{enabled->
         val next=available.copyJson().put(mode,enabled)
         if(modes.none {next.optBoolean(it)})model.error=model.text("Keep at least one mode enabled","Mindestens einen Modus aktivieren")
         else model.saveSettings(model.settings().put("journeyAvailability",next))
        })
       }
      }
      PixelButton(model.text("Restore Passport availability","Passport-Verfügbarkeit wiederherstellen"),{
       val next=model.settings();next.remove("journeyAvailability");model.saveSettings(next)
      },Modifier.fillMaxWidth())
     }
     "data"->{
      Heading(model.text("My data","Meine Daten"))
      Copy(model.text("These actions only remove data saved on this phone. Data already sent to the server is kept.","Diese Aktionen entfernen nur Daten auf diesem Telefon. Bereits an den Server gesendete Daten bleiben erhalten."),MutedText)
      PixelButton(model.text("Clear journey history","Reiseverlauf löschen"),{confirm="history"},Modifier.fillMaxWidth(),enabled=canManage)
      PixelButton(model.text("Reset local data","Lokale Daten zurücksetzen"),{confirm="reset"},Modifier.fillMaxWidth(),enabled=canManage)
     }
     "about"->PixelCard {
      Heading("Companion ${BuildConfig.VERSION_NAME}")
      Copy(model.text("Your digital travel companion. Built with Kotlin and Jetpack Compose.","Dein digitaler Reisebegleiter. Mit Kotlin und Jetpack Compose."))
      Copy("HOTCO · Orion · OTP · Groq")
      Copy(model.text("Model assessments describe this simulation; they are not measured choice probabilities.","Modelleinschätzungen beschreiben diese Simulation; sie sind keine gemessenen Wahlwahrscheinlichkeiten."))
      Copy("MapLibre · OpenFreeMap · OpenMapTiles")
      Copy("© OpenStreetMap contributors · GTFS.de · MVB")
      Copy(model.text("Map designs adapted from OpenFreeMap Liberty / OSM Liberty. Map resources load from OpenFreeMap over the internet.","Kartendesigns angepasst von OpenFreeMap Liberty / OSM Liberty. Kartenressourcen werden über das Internet von OpenFreeMap geladen."))
     }
    }
   }
  }
 }
 confirm?.let {action->
  AlertDialog(onDismissRequest={confirm=null},title={Text(when(action){
   "renew"->model.text("Create new Passport?","Neuen Passport erstellen?")
   "history"->model.text("Clear local journey history?","Lokalen Reiseverlauf löschen?")
   else->model.text("Reset local data?","Lokale Daten zurücksetzen?")
  })},text={Copy(when(action){
   "renew"->model.text("Your confirmed Passport remains available while you create a new one.","Dein bestätigter Passport bleibt erhalten, während du einen neuen erstellst.")
   "history"->model.text("Remove saved searches and journeys from this phone. Your Passport and data already sent to the server are kept.","Gespeicherte Suchen und Reisen von diesem Telefon entfernen. Dein Passport und bereits an den Server gesendete Daten bleiben erhalten.")
   else->model.text("Remove your profile, settings and journey history from this phone. Data already sent to the server is kept.","Profil, Einstellungen und Reiseverlauf von diesem Telefon entfernen. Bereits an den Server gesendete Daten bleiben erhalten.")
  })},confirmButton={TextButton({
   when(action){"renew"->model.beginRenewal();"history"->model.clearJourneyHistory();else->model.resetLocalData()};confirm=null
  },enabled=canManage){Text(model.text("Confirm","Bestätigen"))}},dismissButton={TextButton({confirm=null}){Text(model.text("Cancel","Abbrechen"))}})
 }
}

@Composable fun HistoryScreen(model:CompanionModel) {
 var limit by remember {mutableIntStateOf(10)}
 val records by produceState<List<JSONObject>?>(null,model.revision,limit) {
  try {value=withContext(Dispatchers.IO){model.store.historyPreviews(limit+1)}}
  catch(e:CancellationException){throw e}catch(e:Exception){model.error=e.message;value=emptyList()}
 }
 val loaded=records
 if(loaded==null){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator(color=Mint)};return}
 LazyColumn(Modifier.fillMaxSize().padding(14.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
  item {
   Heading(model.text("Journey history","Reiseverlauf"))
   if(loaded.isEmpty())Copy(model.text("Your searched journeys will appear here.","Deine gesuchten Reisen erscheinen hier."))
  }
  items(loaded.take(limit),key={it.getString("search_id")}) {record->PixelCard {
   val places=record.optJSONObject("places")
   Heading(places?.optJSONObject("destination")?.optString("label")?:model.text("Journey","Reise"))
   Copy(model.text("From: ","Von: ")+places?.optJSONObject("origin")?.optString("label").orEmpty())
   Copy(record.optString("created_at"),MutedText)
   val recommendation=record.optJSONObject("recommendation")
   if(recommendation?.optString("state")=="clear"&&recommendation.optString("winner") in modes) {
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
     PixelIcon(recommendation.getString("winner"))
     Copy(model.text("Recommended: ","Empfohlen: ")+modeName(recommendation.getString("winner"),model.de))
    }
   } else Copy(if(record.optString("status")=="complete")model.text("No clear recommendation","Keine eindeutige Empfehlung") else model.text("Search not completed","Suche nicht abgeschlossen"),MutedText)
   PixelButton(model.text("Open journey","Reise öffnen"),{model.run {
    val full=withContext(Dispatchers.IO){model.store.recordForUi(record.getString("search_id"))}?:error("Journey not saved")
    model.openHistory(full)
   }},Modifier.fillMaxWidth(),enabled=record.optString("status")=="complete"&&!model.busy)
  }}
  if(loaded.size>limit)item {PixelButton(model.text("More journeys","Weitere Reisen"),{limit+=20},Modifier.fillMaxWidth())}
 }
}
