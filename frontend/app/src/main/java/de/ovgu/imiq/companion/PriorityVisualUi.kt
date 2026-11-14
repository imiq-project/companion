package de.ovgu.imiq.companion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable private fun PriorityArrow(direction:Int,modifier:Modifier) {
 val tone=if(direction>0)Mint else if(direction<0)CompanionPalette.Burgundy else MutedText
 Canvas(modifier) {
  val x=size.width*.5f;val y=size.height*.5f;val stroke=1.8.dp.toPx()
  if(direction==0){
   drawLine(tone,Offset(size.width*.1f,y),Offset(size.width*.9f,y),stroke)
   drawLine(tone,Offset(size.width*.6f,y-size.height*.22f),Offset(size.width*.9f,y),stroke)
   drawLine(tone,Offset(size.width*.6f,y+size.height*.22f),Offset(size.width*.9f,y),stroke)
  }else {
   val tip=if(direction>0)size.height*.1f else size.height*.9f
   val tail=if(direction>0)size.height*.9f else size.height*.1f
   val shoulder=if(direction>0)size.height*.4f else size.height*.6f
   drawLine(tone,Offset(x,tail),Offset(x,tip),stroke)
   drawLine(tone,Offset(size.width*.15f,shoulder),Offset(x,tip),stroke)
   drawLine(tone,Offset(size.width*.85f,shoulder),Offset(x,tip),stroke)
  }
 }
}

@Composable internal fun PriorityGoalGlyph(delta:PriorityDelta,modifier:Modifier=Modifier) {
 Box(modifier,contentAlignment=Alignment.CenterStart) {
  GoalIcon(goalForNeed(delta.need)!!,Modifier.size(25.dp))
  PriorityArrow(delta.direction,Modifier.align(Alignment.CenterEnd).width(11.dp).height(20.dp))
 }
}

@Composable internal fun PriorityIconStrip(model:CompanionModel,saved:JSONObject,current:JSONObject,modifier:Modifier=Modifier) {
 val changes=priorityDeltas(saved,current)
 var expanded by remember {mutableStateOf<PriorityDelta?>(null)}
 val labels=needKeys.associateWith {goalCopy(goalForNeed(it)!!,model.de).getString("title")}
 Row(modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(2.dp),verticalAlignment=Alignment.CenterVertically) {
  changes.forEach {delta->
   val direction=when(delta.direction){1->model.text("Increased","Erhöht");-1->model.text("Decreased","Verringert");else->model.text("Unchanged","Unverändert")}
   val label=labels.getValue(delta.need)+" · $direction · "+model.text("Weekly ${delta.saved}, journey ${delta.current}","Woche ${delta.saved}, Reise ${delta.current}")
   IconButton({expanded=delta},Modifier.size(48.dp).semantics {contentDescription=label}){PriorityGoalGlyph(delta,Modifier.width(39.dp).height(32.dp))}
  }
 }
 expanded?.let {delta->AlertDialog(onDismissRequest={expanded=null},icon={PriorityGoalGlyph(delta,Modifier.width(42.dp).height(32.dp))},
  title={Text(labels.getValue(delta.need))},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
   Text(model.text("Weekly importance: ${delta.saved} / 7","Wochenpriorität: ${delta.saved} / 7"))
   Text(model.text("Journey importance: ${delta.current} / 7","Reisepriorität: ${delta.current} / 7"))
   Text(model.text("↑ Increased · ↓ Decreased · → Unchanged, compared with the saved weekly importance. These arrows describe your settings.","↑ Erhöht · ↓ Verringert · → Unverändert gegenüber der gespeicherten Wochenpriorität. Die Pfeile zeigen deine Einstellungen."))
  }},confirmButton={TextButton({expanded=null}){Text(model.text("Close","Schließen"))}})}
}

@Composable private fun NetworkGlyph(modifier:Modifier=Modifier) {
 Canvas(modifier){
  val points=listOf(Offset(size.width*.2f,size.height*.25f),Offset(size.width*.8f,size.height*.25f),Offset(size.width*.5f,size.height*.8f))
  for(i in points.indices)for(j in i+1 until points.size)drawLine(Mint,points[i],points[j],1.5.dp.toPx())
  points.forEach {drawCircle(Mint,3.dp.toPx(),it)}
 }
}

/** Preference remains the signed HOTCO action readout. Coherence describes its whole network. */
@Composable internal fun ModelEvaluationStrip(model:CompanionModel,assessment:Recommendation,current:RouteCard?,time:Long,preview:Boolean) {
 val favourite=visibleFavourite(assessment,time)
 val basis=favourite?:current
 val score=signedModeEvaluation(favourite)
 val favouriteName=favourite?.let {modeName(it.mode,model.de)}
 var details by remember {mutableStateOf(false)}
 val label=if(favourite!=null)model.text("Preferred","Bevorzugt")else when(assessment.state){
  "similar"->model.text("Similar alternatives","Ähnliche Alternativen")
  "only"->model.text("One available mode","Ein verfügbarer Modus")
  "no_positive_support"->model.text("No positive support","Keine positive Unterstützung")
  else->model.text("No clear favourite","Kein klarer Favorit")
 }
 Surface(color=Panel,tonalElevation=2.dp) {
  Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
   Row(Modifier.weight(1f).heightIn(min=48.dp).clickable(role=Role.Button){
    if(favourite!=null&&!preview){model.selectedId=favourite.id;model.activeMode=favourite.mode}else details=true
   }.semantics {contentDescription=label+(favouriteName?.let {" · "+it}?:"")},verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
    PixelIcon(if(favourite!=null)favourite.mode else "preferences",Modifier.size(26.dp))
    Column {
     Text(label,style=MaterialTheme.typography.labelMedium,color=if(favourite!=null)Gold else MutedText)
     if(favourite!=null)Text(modeName(favourite.mode,model.de)+(score?.let {" · "+String.format(java.util.Locale.US,"%+.2f",it)}?:""),style=MaterialTheme.typography.titleSmall,color=Gold)
    }
   }
  }
 }
 if(details)AlertDialog(onDismissRequest={details=false},title={Text(model.text("Model evaluation","Modellbewertung"))},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
  Text(recommendationMessage(assessment,model.de))
  if(score!=null)Text(model.text("Preference: signed final action activation, −1 to +1. It is not a choice probability.","Präferenz: vorzeichenbehaftete finale Handlungsaktivierung, −1 bis +1. Keine Wahlwahrscheinlichkeit."))
  Text(model.text("Global coherence measures the balance of aligned and conflicting signed constraints across the full simulated network: −1 = all opposing mass, +1 = all aligned mass. Zero active mass is undefined. It is separate from preference and numerical stability.","Globale Kohärenz beschreibt das Verhältnis übereinstimmender und widersprüchlicher vorzeichenbehafteter Verbindungen im gesamten simulierten Netz: −1 = nur widersprüchliche, +1 = nur übereinstimmende Masse. Ohne aktive Masse undefiniert. Getrennt von Präferenz und numerischer Stabilität."))
  Text(model.text("Routes with the same simulated network have the same coherence. This value does not distinguish those routes or break a preference tie.","Wege mit demselben simulierten Netz haben dieselbe Kohärenz. Dieser Wert unterscheidet solche Wege nicht und löst keinen Präferenzgleichstand auf."))
  if(basis!=null)Text(model.text("Network shown: ","Angezeigtes Netz: ")+modeName(basis.mode,model.de))
 }},confirmButton={TextButton({details=false}){Text(model.text("Close","Schließen"))}})
}
