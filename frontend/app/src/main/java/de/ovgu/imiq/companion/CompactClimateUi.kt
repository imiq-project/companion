package de.ovgu.imiq.companion

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import kotlin.math.abs

@Composable private fun CarbonIcon(color:Color) {
 Canvas(Modifier.size(24.dp)) {
  val w=size.width;val h=size.height
  val cloud=Path().apply {
   moveTo(w*.23f,h*.78f)
   cubicTo(-w*.06f,h*.78f,-w*.02f,h*.36f,w*.23f,h*.39f)
   cubicTo(w*.22f,h*.02f,w*.77f,h*.02f,w*.78f,h*.4f)
   cubicTo(w*1.05f,h*.38f,w*1.07f,h*.78f,w*.78f,h*.78f)
   close()
  }
  drawPath(cloud,color,style=Stroke(1.7.dp.toPx()))
  drawCircle(color,1.5.dp.toPx(),Offset(w*.36f,h*.56f))
  drawCircle(color,1.5.dp.toPx(),Offset(w*.62f,h*.56f))
 }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun CompactClimateSummary(model:CompanionModel,route:JSONObject,onRefresh:(()->Unit)?) {
 val current=if(onRefresh!=null)remember(model.revision){model.store.json(VEHICLE_PROFILE)}else null
 val display=routeClimatePresentation(route,model.de,current,checkCurrentProfile=onRefresh!=null)
 val climate=route.optJSONObject("climate_estimate")
 val comparison=routeCarComparison(route,cards(model.result).map {it.route})
 val amount=climateVisibleGrams(climate)
 val partial=climate?.optString("status")=="partial"
 val amountLabel=climateAmount(amount)
 val difference=comparison?.let {
  (if(it.grams>=0)"↓ "else"↑ ")+climateAmount(abs(it.grams)).removeSuffix(" CO₂e")+model.text(" vs car"," vs Auto")
 }
 var details by remember(route){mutableStateOf(false)}
 val duration=if(model.settings().optString("motion")=="full")180 else 0
 Surface(onClick={details=true},color=Color.Transparent,modifier=Modifier.fillMaxWidth().semantics {
  contentDescription=display.amount+". "+(difference.orEmpty())+". "+display.note.orEmpty()+model.text(". Emissions details",". Emissionsdetails")
 }) {
  Row(Modifier.fillMaxWidth().padding(start=12.dp,end=4.dp).heightIn(min=48.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
   CarbonIcon(Mint)
   Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(1.dp)) {
    Crossfade(amountLabel,animationSpec=tween(duration),label="route-carbon") {label->Text(label,style=MaterialTheme.typography.titleSmall,color=Mint)}
    if(difference!=null)Crossfade(difference,animationSpec=tween(duration),label="route-carbon-difference") {label->
     Text(label,style=MaterialTheme.typography.labelSmall,color=if(comparison.grams>=0)Mint else Gold)
    }
   }
   if(partial||comparison?.partial==true)Text(model.text("Mapped","Kartiert"),style=MaterialTheme.typography.labelSmall,color=MutedText)
   if(display.needsRefresh&&onRefresh!=null)IconButton(onRefresh,enabled=!model.busy,modifier=Modifier.semantics {contentDescription=model.text("Vehicle profile changed or estimate missing. Update route","Autoprofil geändert oder Schätzung fehlt. Weg aktualisieren")}){Text("↻",style=MaterialTheme.typography.titleLarge,color=Gold)}
   else Text("ⓘ",Modifier.padding(8.dp),style=MaterialTheme.typography.bodyMedium,color=MutedText)
  }
 }
 if(details)ModalBottomSheet(onDismissRequest={details=false},containerColor=Panel,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
  Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
   Heading(model.text("Emissions estimate","Emissionsschätzung"))
   Copy(display.amount,Mint)
   display.vehicle?.let {Copy(it)}
   display.note?.let {Copy(it,Gold)}
   comparison?.let {c->
    Copy(climateAmount(abs(c.grams))+(if(c.grams>=0)model.text(" less than the car route"," weniger als die Autoroute")else model.text(" more than the car route"," mehr als die Autoroute")),Mint)
    Copy(model.text("Per person, using the car profile saved with this search.","Pro Person, mit dem für diese Suche gespeicherten Autoprofil."),MutedText)
    if(c.partial)Copy(model.text("Mapped portions only. Access is incomplete; this difference is not a whole-journey saving.","Nur kartierte Abschnitte. Zugang unvollständig; diese Differenz ist keine Einsparung für den gesamten Weg."),Gold)
   }
   ClimateDetails(model,climate)
   if(display.needsRefresh&&onRefresh!=null)TextButton({details=false;onRefresh()},enabled=!model.busy){Text(model.text("Update route","Weg aktualisieren"))}
   Spacer(Modifier.height(16.dp))
  }
 }
}
