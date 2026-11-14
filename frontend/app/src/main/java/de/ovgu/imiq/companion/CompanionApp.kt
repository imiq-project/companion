package de.ovgu.imiq.companion

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun CompanionApp(model:CompanionModel,onExport:(JSONObject,String)->Unit,onImport:()->Unit,onLocate:()->Unit,onRetryMigration:()->Unit,onTrackStart:(String,Int)->Unit) {
 val revision=model.revision
 val settings=model.settings()
 if(!model.ready){
  CompanionTheme(settings.optString("textSize")=="large"){
   Surface(color=Ink,modifier=Modifier.fillMaxSize()){
    Column(Modifier.fillMaxSize()){
    AppBrandHeader(model)
    Box(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom+WindowInsetsSides.Horizontal)).padding(24.dp),contentAlignment=Alignment.Center){
     Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)){
      if(model.migrationError==null)Copy(model.text("Loading…","Wird geladen…"))
      else{Copy(model.migrationError!!,MaterialTheme.colorScheme.error);PixelButton(model.text("Retry import","Import wiederholen"),onRetryMigration)}
     }
    }
    }
   }
  }
  return
 }
 LaunchedEffect(model){model.restorePanelLogin()}
 if(model.panelIdentity==null){CompanionTheme(settings.optString("textSize")=="large"){Surface(color=Ink,modifier=Modifier.fillMaxSize()){
  Column(Modifier.fillMaxSize()){
   AppBrandHeader(model)
   Box(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom+WindowInsetsSides.Horizontal))){PanelLoginScreen(model)}
  }
 }};return}
 val snackbar=remember {SnackbarHostState()}
 LaunchedEffect(model) {
  snapshotFlow {model.experienceSaved}.collect {saved->if(saved){
   model.experienceSaved=false
   snackbar.showSnackbar(model.text("Experience saved ✓","Erfahrung gespeichert ✓"),duration=SnackbarDuration.Short)
  }}
 }
 val immersiveTracking=model.ready&&model.tab=="journey"&&model.routeView=="trip"&&model.store.json(ACTIVE_TRIP)?.optString("search_id")== (model.tripSearchId?:model.historyId)&&model.tripSearchId!=null
 val immersiveNavigation=immersiveTracking||(model.ready&&model.tab=="journey"&&model.routeView=="without_gps"&&model.noGpsJourney!=null)
 val immersiveJourney=model.routeSearching||immersiveNavigation||(model.ready&&model.tab=="journey"&&model.result!=null&&model.routeView in listOf("alternatives","map","preview"))
 val travelVisible=model.tab=="journey"&&model.passport()!=null&&model.store.json("companion.passport-renewal.v1")==null
 CompanionTheme(settings.optString("textSize")=="large") {
  Scaffold(containerColor=Ink,contentWindowInsets=if(travelVisible)WindowInsets.safeDrawing else WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom+WindowInsetsSides.Horizontal),snackbarHost={SnackbarHost(snackbar)},topBar={if(!travelVisible)AppBrandHeader(model)},bottomBar={
   if(!immersiveJourney&&model.ready&&model.passport()!=null&&model.store.json("companion.passport-renewal.v1")==null)NavigationBar(containerColor=Panel,windowInsets=if(model.tab=="journey"&&model.routeView=="search")WindowInsets(0,0,0,0)else NavigationBarDefaults.windowInsets,modifier=if(model.tab=="journey"&&model.routeView=="search")Modifier.navigationBarsPadding().height(64.dp)else Modifier){
    listOf("home","journey","passport","settings").forEach { key->NavigationBarItem(selected=model.tab==key,onClick={if(key=="journey"&&model.tab!="journey"){if(model.store.json(ACTIVE_PLAYBACK)!=null)model.openPlayback()else if(model.routeView!="trip")model.routeView="search"};model.tab=key;model.error=null},icon={if(key=="passport")CompanionFaceIcon(model.companion().optString("appearance","robot"))else NavigationSprite(when(key){"journey"->"travel";"settings"->"menu";else->"home"},model.tab==key,settings.optString("motion")=="full")},label={Text(when(key){"home"->model.text("Home","Start");"journey"->model.text("Travel","Reise");"passport"->"Companion";else->model.text("Settings","Einstellungen")},style=MaterialTheme.typography.bodySmall)},alwaysShowLabel=true) }
   }
  }) { padding->
   Column(Modifier.fillMaxSize().padding(padding)) {
    if(!model.routeSearching&&model.ready&&!(model.tab=="journey"&&model.routeView in listOf("trip","without_gps")))TripBanner(model)
    if(model.error!=null)PixelCard(Modifier.padding(horizontal=12.dp)){Copy(model.error!!,MaterialTheme.colorScheme.error);TextButton({model.error=null}){Text(model.text("Dismiss","Schließen"))}}
    if(!model.routeSearching&&model.notice!=null)Row(Modifier.fillMaxWidth().background(Panel).padding(10.dp),verticalAlignment=Alignment.CenterVertically){Text(model.notice!!,Modifier.weight(1f));TextButton({model.notice=null}){Text("×")}}
    when {
     model.routeSearching->JourneyLoadingScreen(model)
     model.passport()==null||model.store.json("companion.passport-renewal.v1")!=null->Onboarding(model)
     model.tab=="home"->HomeScreen(model)
     model.tab=="journey"->JourneyScreen(model,onLocate,onTrackStart)
     model.tab=="passport"->CompanionScreen(model)
     else->SettingsScreen(model)
    }
   }
  }
  model.pendingTripImportance?.let {session->
   SituatedImportanceDialog(session.id,model.tripImportanceDraft,session.language=="de",
    animated=settings.optString("motion")=="full",onChange=model::updateTripImportance,
    onDismiss=model::dismissTripImportance,onConfirm={model.confirmTripImportance(session.id)})
  }
  model.pendingImport?.let { backup->AlertDialog(onDismissRequest={model.pendingImport=null},title={Heading(model.text("Import backup?","Sicherung importieren?"))},text={Copy(model.text("Your current Passport will be archived. Journeys are merged; conflicting IDs are rejected.","Dein aktueller Passport wird archiviert. Reisen werden zusammengeführt; widersprüchliche IDs werden abgelehnt."))},confirmButton={TextButton({runCatching {model.store.importBackup(backup);model.refresh();model.tab="home";model.notice=model.text("Backup imported","Sicherung importiert")}.onFailure { model.error=it.message };model.pendingImport=null}){Text(model.text("Import","Importieren"))}},dismissButton={TextButton({model.pendingImport=null}){Text(model.text("Cancel","Abbrechen"))}}) }
 }
 val onboardingBackHandled=model.ready&&model.step in listOf("goals","travel-kit","valences")&&(model.passport()==null||model.store.json("companion.passport-renewal.v1")!=null)
 BackHandler(enabled=!onboardingBackHandled&&(model.routeSearching||model.ready&&(model.tab!="home"||model.routeView!="search"&&model.tab=="journey"))){if(model.pendingTripImportance!=null)model.dismissTripImportance() else if(model.routeSearching)model.cancel() else if(model.tab=="journey"&&model.routeView!="search")model.routeView=if(model.routeView=="map"||model.routeView=="reflection"||model.routeView=="preview")"alternatives"else"search" else model.tab="home"}
}

@Composable fun ScreenColumn(content:@Composable ColumnScope.()->Unit) {
 Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(14.dp),verticalArrangement=Arrangement.spacedBy(16.dp),content=content)
}
@Composable fun HomeScreen(model:CompanionModel)=HomeDashboardScreen(model)

@Composable fun Onboarding(model:CompanionModel) {
 LaunchedEffect(model,model.step){model.questionnaireVisible()}
 val renewing=model.store.json("companion.passport-renewal.v1")!=null
 Column {
  if(renewing)Row(Modifier.padding(horizontal=14.dp),verticalAlignment=Alignment.CenterVertically){Text(model.text("New Passport draft","Neuer Passport-Entwurf"),Modifier.weight(1f));TextButton({model.cancelRenewal()}){Text(model.text("Cancel","Abbrechen"))}}
  when(model.step){
   "identity"->IdentityScreen(model,false);"travel-kit"->TravelKitScreen(model);"goals"->GoalsScreen(model);"valences"->FeelingsScreen(model);"beliefs"->BeliefsScreen(model)
   else->{
    // Resume an old unconfirmed review on its saved last connection. Never
    // confirm a pre-update draft merely because the app was opened again.
    LaunchedEffect(Unit){model.goStep("beliefs")}
    Copy(model.text("Restoring your connections…","Deine Verbindungen werden wiederhergestellt…"))
   }
  }
 }
}
@Composable fun IdentityScreen(model:CompanionModel,editing:Boolean) {
 if(!editing){ProfileWelcomeScreen(model);return}
 ScreenColumn {
  ImdilloIllustration(Modifier.size(134.dp,182.dp))
  Heading("Imdillo")
  Copy(model.text("Your digital travel companion. Recommendations for transport modes and routes that match your mobility preferences.","Dein digitaler Reisebegleiter. Empfehlungen für Verkehrsmittel und Routen, die zu deinen Mobilitätspräferenzen passen."))
 }
}
@Composable fun FeelingsScreen(model:CompanionModel) {
 ProfileFeelingsScreen(model)
}
@Composable fun RatingControl(value:Int?,onChange:(Int)->Unit,low:String,high:String) {
 Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){PixelButton("−",{onChange(((value?:4)-1).coerceAtLeast(1))},enabled=value!=1);Slider((value?:4).toFloat(),{onChange(it.toInt())},Modifier.weight(1f),valueRange=1f..7f,steps=5);PixelButton("+",{onChange(((value?:4)+1).coerceAtMost(7))},enabled=value!=7)}
 Row {Text(low,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall);Text(high,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)}
}
@Composable fun BeliefsScreen(model:CompanionModel) {
 ProfileConnectionsScreen(model)
}

@Composable fun Disclosure(title:String,content:@Composable ColumnScope.()->Unit) {
 var expanded by remember { mutableStateOf(false) }
 Column(verticalArrangement=Arrangement.spacedBy(10.dp)){TextButton({expanded=!expanded},Modifier.fillMaxWidth()){Text((if(expanded)"▾ "else"▸ ")+title)};if(expanded)Column(verticalArrangement=Arrangement.spacedBy(10.dp),content=content)}
}
