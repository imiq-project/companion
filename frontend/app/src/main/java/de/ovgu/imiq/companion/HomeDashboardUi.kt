package de.ovgu.imiq.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import kotlin.math.sin
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@Composable fun HomeDashboardScreen(model:CompanionModel) {
 val animated=model.settings().optString("motion")=="full"
 var weather by remember {mutableStateOf<JSONObject?>(null)}
 var loading by remember {mutableStateOf(true)}
 var unavailable by remember {mutableStateOf(false)}
 var refresh by remember {mutableIntStateOf(0)}
 var placeLabel by remember {mutableStateOf<String?>(null)}
 var locationReady by remember {mutableStateOf(false)}
 val context=LocalContext.current
 val lifecycle=LocalLifecycleOwner.current.lifecycle
 fun permitted()=listOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION).any {context.checkSelfPermission(it)==PackageManager.PERMISSION_GRANTED}
 fun enabled()=(context.getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager).isLocationEnabled
 val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){refresh++}
 val settings=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){refresh++}
 fun locate(){when { !permitted()->permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION));!enabled()->settings.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));else->refresh++ }}
 LaunchedEffect(lifecycle,refresh,model.locale) {
  lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
   loading=true;weather=null;unavailable=false;placeLabel=null;locationReady=false
   try {
    if(permitted()&&enabled()) {
     val fix=currentMapLocation(context)
     locationReady=true
     placeLabel=homeWeatherPlaceLabel(null,null,null,fix.latitude,fix.longitude)
     coroutineScope {
      launch {
       try {placeLabel=homeWeatherCity(context,fix.latitude,fix.longitude,model.locale)}
       catch(e:CancellationException){throw e}catch(_:Exception){/* Coordinates remain the fallback label. */}
      }
      val response=model.api.call("/api/companion/weather?lat=${fix.latitude}&lon=${fix.longitude}",timeout=25000)
      require(response.optContractString("schema_version")=="companion-weather-v1")
      weather=response;unavailable=response.optString("status")!="AVAILABLE"
     }
    }
   }catch(e:CancellationException){throw e}catch(_:Exception){unavailable=true}finally{loading=false}
  }
 }
 Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
  PixelCard(spacing=10.dp,padding=12.dp) {
   Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
    CompanionSprite(model.companion().optString("appearance","robot"),Modifier.size(36.dp),animated=animated,portrait=true)
    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
     Text(model.text("Hello!","Hallo!"),style=MaterialTheme.typography.bodyMedium,color=MutedText)
     Text(model.text("Where to today?","Wohin geht’s heute?"),style=MaterialTheme.typography.titleMedium,color=Gold)
    }
   }
   HomePlanButton(model,Modifier.fillMaxWidth())
  }
  PixelCard(spacing=6.dp,padding=12.dp) {
   Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
    HomeLocationIcon(Modifier.size(20.dp))
    Text(placeLabel?:model.text("Your current location","Dein aktueller Standort"),style=MaterialTheme.typography.titleMedium,color=Pale,modifier=Modifier.weight(1f))
    if(loading)Box(Modifier.size(48.dp),contentAlignment=Alignment.Center){CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)}
    else IconButton({locate()},modifier=Modifier.size(48.dp).semantics {contentDescription=model.text("Refresh local weather","Lokales Wetter aktualisieren")}){Text("↻",style=MaterialTheme.typography.titleLarge,color=Mint)}
   }
   Text(model.text("Current weather","Aktuelles Wetter"),style=MaterialTheme.typography.labelLarge,color=MutedText)
   if(loading)Text(model.text("I'm checking the conditions for you…","Ich schaue nach den Bedingungen für dich…"),style=MaterialTheme.typography.bodySmall,color=Mint)
   else if(!locationReady) {
    Text(model.text("Use your location to see the weather around you.","Nutze deinen Standort für das Wetter in deiner Nähe."),style=MaterialTheme.typography.bodySmall,color=MutedText)
    OutlinedButton({locate()},modifier=Modifier.heightIn(min=48.dp)){Text(model.text("Use my location","Meinen Standort verwenden"))}
   }
   else if(unavailable)Text(model.text("I couldn't get current conditions. Tap Refresh to try again.","Ich konnte die aktuellen Bedingungen nicht abrufen. Tippe auf Aktualisieren, um es erneut zu versuchen."),style=MaterialTheme.typography.bodySmall,color=MutedText)
   else JourneyWeatherStrip(model,emptyList(),snapshot=weather,grid=true,compactHome=true,homeHero=true)
  }
 }
}

@Composable private fun HomeLocationIcon(modifier:Modifier=Modifier) {
 androidx.compose.foundation.Canvas(modifier) {
  val center=androidx.compose.ui.geometry.Offset(size.width*.5f,size.height*.38f)
  drawCircle(Mint,radius=size.width*.28f,center=center,style=androidx.compose.ui.graphics.drawscope.Stroke(size.width*.09f))
  drawCircle(Mint,radius=size.width*.08f,center=center)
  val path=androidx.compose.ui.graphics.Path().apply {moveTo(size.width*.27f,size.height*.53f);lineTo(size.width*.5f,size.height*.9f);lineTo(size.width*.73f,size.height*.53f)}
  drawPath(path,Mint,style=androidx.compose.ui.graphics.drawscope.Stroke(size.width*.09f))
 }
}

@Composable private fun HomePlanButton(model:CompanionModel,modifier:Modifier=Modifier){
 Button(onClick={model.tab="journey";model.routeView="search"},modifier=modifier.heightIn(min=48.dp),contentPadding=PaddingValues(horizontal=12.dp,vertical=8.dp)) {
  PixelIcon("travel",Modifier.size(20.dp));Spacer(Modifier.width(6.dp))
  Text(model.text("Plan journey","Reise planen"),style=MaterialTheme.typography.labelLarge)
 }
}
