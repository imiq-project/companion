package de.ovgu.imiq.companion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable internal fun PlanningMapLayersSheet(model:CompanionModel,style:JourneyMapStyle,preferences:MapPoiPreferences,onApply:(JourneyMapStyle,MapPoiPreferences)->Unit,onDismiss:()->Unit) {
 var draft by remember {mutableStateOf(preferences)}
 var mapStyle by remember {mutableStateOf(style)}
 ModalBottomSheet(onDismissRequest=onDismiss,containerColor=Panel,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
  Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
   Text(model.text("Map layers","Kartenebenen"),style=MaterialTheme.typography.titleLarge)
   FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {JourneyMapStyle.entries.forEach {option->
    FilterChip(mapStyle==option,{mapStyle=option},label={Text(option.title(model))},modifier=Modifier.heightIn(min=48.dp))
   }}
   @Composable fun toggle(label:String,value:Boolean,change:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f));Switch(value,change)}
   }
   toggle(model.text("Public transport stops","Haltestellen"),draft.stops){draft=draft.copy(stops=it)}
   toggle(model.text("Public parking","Öffentliche Parkplätze"),draft.parking){draft=draft.copy(parking=it)}
   toggle(model.text("Highlight walking and cycling paths","Fuß- und Radwege hervorheben"),mapStyle==JourneyMapStyle.Mobility){mapStyle=if(it)JourneyMapStyle.Mobility else JourneyMapStyle.Clear}
   Text(mapStyle.description(model),style=MaterialTheme.typography.bodySmall,color=MutedText)
   Disclosure(model.text("More map options","Weitere Kartenoptionen")) {
    toggle(model.text("Include my GPS position","GPS-Position einbeziehen"),draft.gps){draft=draft.copy(gps=it)}
    if(draft.parking)toggle(model.text("Include restricted parking","Eingeschränkte Parkplätze zeigen"),draft.restricted){draft=draft.copy(restricted=it)}
    Text(model.text("Search radius: ${draft.radius} m","Suchradius: ${draft.radius} m"))
    Slider(draft.radius.toFloat(),{draft=draft.copy(radius=((it/50).roundToInt()*50).coerceIn(50,2000))},valueRange=50f..2000f,steps=38)
    Text(model.text("Around origin, destination and an available GPS position. Linked trip platforms remain visible.","Um Start, Ziel und eine verfügbare GPS-Position. Zugeordnete Bahnsteige bleiben sichtbar."),style=MaterialTheme.typography.bodySmall,color=MutedText)
   }
   Button({onApply(mapStyle,draft)},Modifier.fillMaxWidth().heightIn(min=50.dp)){Text(model.text("Apply","Übernehmen"))}
  }
 }
}
