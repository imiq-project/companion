package de.ovgu.imiq.companion

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable fun GoalsScreen(model:CompanionModel) {
 val points=model.plan().getJSONObject("points")
 WeeklyPrioritiesEditor(goalKeys.associateWith {points.optInt(it)},model.de,
  onRate=model::rateGoal,onSave=model::saveGoals,onBack={model.goStep("travel-kit")},saving=model.busy)
}

/** One independent weekly importance response per view; zero is unanswered. */
@Composable internal fun WeeklyPrioritiesEditor(points:Map<String,Int>,de:Boolean,onRate:(String,Int)->Unit,onSave:()->Unit,onBack:()->Unit,saving:Boolean=false) {
 fun text(en:String,german:String)=if(de)german else en
 var index by rememberSaveable {mutableIntStateOf(0)}
 var explanation by rememberSaveable {mutableStateOf(false)}
 val goalKey=goalKeys[index.coerceIn(goalKeys.indices)]
 val title=priorityTitle(goalKey,de)
 val value=points[goalKey]?.takeIf {it in 1..7}
 val rated=goalKeys.count {(points[it]?:0) in 1..7}
 val last=index==goalKeys.lastIndex
 val scroll=rememberScrollState()
 fun previous(){explanation=false;if(index>0)index--else onBack()}
 BackHandler(enabled=!saving){previous()}
 LaunchedEffect(index){scroll.scrollTo(0);explanation=false}
 Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter){
  Column(Modifier.widthIn(max=390.dp).fillMaxSize()){
   Column(Modifier.weight(1f).verticalScroll(scroll).padding(16.dp),verticalArrangement=Arrangement.spacedBy(20.dp)){
    TextButton({previous()},enabled=!saving,modifier=Modifier.heightIn(min=48.dp)){
     Text(if(index==0)text("← Back","← Zurück")else text("← Previous priority","← Vorherige Priorität"))
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
     Text(text("Your priorities","Deine Prioritäten"),Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=MutedText)
     Text(text("Priority ${index+1} of 5","Priorität ${index+1} von 5"),style=MaterialTheme.typography.bodySmall,color=MutedText)
    }
    Text(text("What matters this week?","Was zählt diese Woche?"),style=MaterialTheme.typography.displayMedium,color=Pale)
    Text(text("For this week’s journeys, how important is each goal to you? Rate each separately: 1 = not important; 7 = very important.",
     "Wie wichtig ist dir jedes Ziel für deine Wege diese Woche? Bewerte jedes einzeln: 1 = nicht wichtig; 7 = sehr wichtig."),style=MaterialTheme.typography.bodyLarge,color=Pale)
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
     Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
      Text(text("$rated of 5 rated","$rated von 5 bewertet"),Modifier.weight(1f).semantics {liveRegion=LiveRegionMode.Polite},style=MaterialTheme.typography.bodySmall,color=MutedText)
      Text(text("Weekly importance","Wichtigkeit diese Woche"),style=MaterialTheme.typography.bodySmall,color=MutedText)
     }
     LinearProgressIndicator(progress={rated/5f},modifier=Modifier.fillMaxWidth(),color=Mint,trackColor=Edge)
    }
    HorizontalDivider(color=Edge)
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
     GoalIcon(goalKey,Modifier.size(30.dp));Text(title,style=MaterialTheme.typography.headlineMedium,color=Pale)
    }
    key(goalKey){PriorityImportanceSlider(value,title,de,!saving){onRate(goalKey,it)}}
    Text(text("Move or tap the slider to choose a value. Equal ratings are allowed.","Bewege oder berühre den Regler, um einen Wert zu wählen. Gleiche Bewertungen sind erlaubt."),style=MaterialTheme.typography.bodyMedium,color=MutedText)
    TextButton({explanation=!explanation},Modifier.heightIn(min=48.dp).semantics {stateDescription=if(explanation)text("Expanded","Ausgeklappt")else text("Collapsed","Eingeklappt")}){
     Text(text("About this goal","Über dieses Ziel"))
    }
    if(explanation)Text(priorityDefinition(goalKey,de),style=MaterialTheme.typography.bodyMedium,color=MutedText)
   }
   Column(Modifier.fillMaxWidth().padding(horizontal=16.dp).padding(top=12.dp,bottom=18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
    Text(if(value==null)text("Rate this priority to continue.","Bewerte diese Priorität, um fortzufahren.")else text("Several goals can be equally important.","Mehrere Ziele können gleich wichtig sein."),style=MaterialTheme.typography.bodySmall,color=MutedText)
    PixelButton(if(last)text("Save priorities","Prioritäten speichern")else text("Next priority →","Nächste Priorität →"),
     {if(last){if(rated==5)onSave()}else index++},Modifier.fillMaxWidth(),enabled=!saving&&value!=null&&(!last||rated==5),primary=true)
   }
  }
 }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PriorityImportanceSlider(value:Int?,title:String,de:Boolean,enabled:Boolean,onRate:(Int)->Unit){
 val status=if(value==null)if(de)"Noch nicht bewertet"else"Not rated" else "$value / 7"
 var pending by remember(value){mutableIntStateOf(value?:4)}
 Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
  Text(status,style=MaterialTheme.typography.titleMedium,color=if(value==null)MutedText else Pale,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
  Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
   TextButton({onRate(((value?:4)-1).coerceAtLeast(1))},Modifier.size(48.dp).semantics {contentDescription=if(de)"Wichtigkeit für $title senken"else"Decrease importance of $title"},enabled=enabled&&value!=1,contentPadding=PaddingValues(0.dp)){Text("−")}
   Column(Modifier.weight(1f)){
    Slider(value=(value?:4).toFloat(),onValueChange={pending=it.roundToInt().coerceIn(1,7);onRate(pending)},
     onValueChangeFinished={if(value==null)onRate(pending)},valueRange=1f..7f,steps=5,enabled=enabled,
     modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).semantics {
      contentDescription=if(de)"Wichtigkeit von $title"else"Importance of $title"
      stateDescription=status
      // Explicit accessibility selection is valid even at the untouched midpoint.
      if(enabled)setProgress {requested->if(requested.isFinite()){onRate(requested.roundToInt().coerceIn(1,7));true}else false}
     },thumb={Canvas(Modifier.size(24.dp)){
      drawCircle(if(value==null)Ink else Mint)
      drawCircle(if(value==null)MutedText else Mint,style=Stroke(3.dp.toPx()))
     }},colors=SliderDefaults.colors(activeTrackColor=if(value==null)MutedText else Mint,inactiveTrackColor=if(value==null)MutedText else Edge))
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){(1..7).forEach {Text("$it",style=MaterialTheme.typography.bodySmall,color=MutedText)}}
   }
   TextButton({onRate(((value?:4)+1).coerceAtMost(7))},Modifier.size(48.dp).semantics {contentDescription=if(de)"Wichtigkeit für $title erhöhen"else"Increase importance of $title"},enabled=enabled&&value!=7,contentPadding=PaddingValues(0.dp)){Text("+")}
  }
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp)){
   Text(if(de)"1 · Nicht wichtig"else"1 · Not important",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=MutedText)
   Text(if(de)"7 · Sehr wichtig"else"7 · Very important",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=MutedText,textAlign=TextAlign.End)
  }
 }
}

private fun priorityTitle(key:String,de:Boolean):String {
 val titles=when(key){
  "env"->"Lower emissions" to "Weniger Emissionen"
  "health_activity"->"Move more" to "Mehr Bewegung"
  "cost"->"Save money" to "Geld sparen"
  "time"->"Save time" to "Zeit sparen"
  "comfort_physical"->"Physical comfort" to "Körperlicher Komfort"
  else->key to key
 }
 return if(de)titles.second else titles.first
}

private fun priorityDefinition(key:String,de:Boolean):String {
 val definitions=when(key){
  "env"->"Travel-related emissions." to "Reisebedingte Emissionen."
  "health_activity"->"Physical movement during travel, such as walking or cycling." to "Körperliche Bewegung unterwegs, etwa Gehen oder Radfahren."
  "cost"->"Money you spend on travel." to "Deine Ausgaben für Mobilität."
  "time"->"Time spent travelling." to "Die unterwegs verbrachte Zeit."
  else->"How comfortable your body feels while travelling, separately from your emotional feeling." to "Wie angenehm sich dein Körper unterwegs fühlt, getrennt von deinem emotionalen Gefühl."
 }
 return if(de)definitions.second else definitions.first
}
