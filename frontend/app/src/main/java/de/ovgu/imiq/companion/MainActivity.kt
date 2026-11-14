package de.ovgu.imiq.companion

import android.Manifest
import android.os.Bundle
import android.os.Build
import android.content.Intent
import android.content.pm.PackageManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject

class MainActivity:ComponentActivity() {
 private lateinit var model:CompanionModel
 private var exportPayload:JSONObject?=null
 private var migration:WebView?=null
 private var pendingTracking:Pair<String,Int>?
  get()=if(::model.isInitialized)model.pendingGpsStart else null
  set(value){if(::model.isInitialized)model.pendingGpsStart=value}
 private val exportFile=registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri->
  if(uri!=null)runCatching { contentResolver.openOutputStream(uri)?.use { it.write((exportPayload?:model.store.export()).toString(2).toByteArray()) } }.onSuccess { model.notice=model.text("File saved","Datei gespeichert") }.onFailure { model.error=it.message }
  exportPayload=null
 }
 private val importFile=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri->
  if(uri!=null)runCatching { val text=contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }?:error("Cannot read backup");JSONObject(text) }.onSuccess { model.pendingImport=it }.onFailure { model.error=it.message }
 }
 private val locationPermission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted->
  if(granted.values.any { it })locate() else model.error=model.text("Location permission denied. You can enter an address.","Standortberechtigung verweigert. Du kannst eine Adresse eingeben.")
 }
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState);enableEdgeToEdge();model=ViewModelProvider(this)[CompanionModel::class.java]
  applyFullscreen()
  setContent {
   val tracking=rememberAndroidLocationAccess(model,allowWithoutLocation=true,onReady={
    val pending=pendingTracking;pendingTracking=null
    if(pending!=null)runCatching {model.openingTracking(pending.first);TrackingService.action(this,TrackingService.START,pending.first,pending.second)}.onFailure {model.tripStartingSearchId=null;model.routeView="alternatives";model.error=it.message}
   },onUnavailable={val pending=pendingTracking;pendingTracking=null;if(pending!=null)model.startWithoutGps(pending.first,pending.second)})
   val notifications=androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){tracking.request()}
   CompanionApp(model,onExport={value,name->exportPayload=value;exportFile.launch(name)},onImport={importFile.launch(arrayOf("application/json","text/plain","*/*"))},onLocate={locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))},onRetryMigration={migrateLegacy()},onTrackStart={search,index->if(pendingTracking==null){pendingTracking=search to index;if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)notifications.launch(Manifest.permission.POST_NOTIFICATIONS)else tracking.request()}})
  }
  if(getPreferences(MODE_PRIVATE).getBoolean("native_migrated",false))model.refresh()else migrateLegacy()
 }
 private fun migrateLegacy() {
  migration?.destroy();model.migrationError=null
  migration=WebView(this).apply {
   settings.javaScriptEnabled=true;settings.domStorageEnabled=true
   addJavascriptInterface(object {
    @JavascriptInterface fun complete(text:String) { runOnUiThread {
     runCatching {
      val value=JSONObject(text);if(value.has("error"))error(value.getString("error"))
      val backup=model.store.export();if(backup.getJSONObject("storage").length()>0||backup.getJSONArray("simulations").length()>0)filesDir.resolve("before-native-migration.json").writeText(backup.toString())
      model.store.importBackup(value,false)
      getPreferences(MODE_PRIVATE).edit().putBoolean("native_migrated",true).apply();model.refresh()
     }.onFailure { model.migrationError=it.message }
     migration?.destroy();migration=null
    } }
   },"CompanionMigration")
   webViewClient=WebViewClient()
   loadDataWithBaseURL("https://localhost/", """
    <html><body><script>
    (async()=>{try{
      const storage={};for(let i=0;i<localStorage.length;i++){const key=localStorage.key(i);if(key.startsWith('imiq.experimental.')||['companion.settings.v1','companion.recent-places.v1','companion.chosen-journey.v1','companion.passport-renewal.v1','companion.connection.v1'].includes(key))storage[key]=localStorage.getItem(key)}
      const simulations=await new Promise((resolve,reject)=>{const r=indexedDB.open('companion-simulation-history-v1',1);r.onupgradeneeded=()=>r.result.createObjectStore('searches',{keyPath:'search_id'});r.onerror=()=>reject(Error('Cannot open old history'));r.onsuccess=()=>{const db=r.result;const q=db.transaction('searches','readonly').objectStore('searches').getAll();q.onsuccess=()=>{resolve(q.result);db.close()};q.onerror=()=>reject(Error('Cannot read old history'))}});
      CompanionMigration.complete(JSON.stringify({schema:'companion-backup-v1',storage,simulations}));
    }catch(e){CompanionMigration.complete(JSON.stringify({error:String(e)}))}})();
    </script></body></html>
   """.trimIndent(),"text/html","UTF-8",null)
  }
 }
 private fun locate(){requestLocation(this,model)}
 private fun applyFullscreen(){
  WindowInsetsControllerCompat(window,window.decorView).apply {
   isAppearanceLightStatusBars=true;isAppearanceLightNavigationBars=true
   systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
   hide(WindowInsetsCompat.Type.systemBars())
  }
 }
 override fun onWindowFocusChanged(hasFocus:Boolean){super.onWindowFocusChanged(hasFocus);if(hasFocus)applyFullscreen()}
 override fun onResume(){super.onResume();applyFullscreen();if(::model.isInitialized&&model.ready){runCatching {model.trips.recover()}.onFailure {model.error=it.message };openActiveIntent(intent)}}
 override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);openActiveIntent(intent)}
 private fun openActiveIntent(intent:Intent?){
  if(intent?.action=="companion.OPEN_ACTIVE_TRIP"){if(model.store.json(ACTIVE_PLAYBACK)!=null)model.openPlayback()else model.trips.active()?.let {model.openTrip(it.first.getString("search_id"),it.second.getString("journey_id"))};intent.action=null}
  else if(intent?.action=="companion.FINISH_PLAYBACK"){model.finishPlayback();intent.action=null}
 }
 override fun onDestroy(){migration?.destroy();migration=null;super.onDestroy()}
}
