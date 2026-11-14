package de.ovgu.imiq.companion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.json.JSONObject

@Composable private fun GoalRatingRow(model:CompanionModel,key:String,saved:Int,current:Int,onChange:(Int)->Unit) {
 val name=goalCopy(goalForNeed(key)!!,model.de).getString("title")
 Surface(color=PanelRaised,shape=MaterialTheme.shapes.medium) {
  Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
   Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
    PriorityGoalGlyph(PriorityDelta(key,saved,current),Modifier.width(39.dp).height(32.dp));Text(name,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
    Text(if(saved in 1..7)model.text("Saved: $saved","Gespeichert: $saved")else model.text("Not rated","Nicht bewertet"),style=MaterialTheme.typography.bodySmall,color=MutedText)
   }
   RatingControl(current.takeIf {it in 1..7},onChange,model.text("1 · Not important","1 · Nicht wichtig"),model.text("7 · Very important","7 · Sehr wichtig"))
   Text(model.text("Current: $current / 7","Aktuell: $current / 7"),color=if(current==saved)Pale else Gold)
  }
 }
}

@Composable internal fun GoalPersonalizationScreen(model:CompanionModel,onBack:()->Unit) {
 val item=model.passport()?:return
 val reference=runCatching {weeklyGoalReference(item)}.getOrNull()
 val referenceKey=reference?.let(::canonical)?:item.getJSONObject("response").getJSONObject("cognitive_passport").getJSONObject("lineage").getString("passport_id")
 val initial=remember(referenceKey){reference?.getJSONObject("ratings")?.copyJson()?:JSONObject()}
 var values by remember(referenceKey){mutableStateOf(initial.copyJson())}
 ScreenColumn {
  TextButton(onBack){Text(model.text("← Personalization","← Personalisierung"))}
  Heading(model.text("Weekly goals","Wochenziele"))
  model.weeklyPriorityUploadStatus()?.let {status->Copy(when(status){
   "stored"->model.text("Weekly reference saved on the server.","Wochenreferenz auf dem Server gespeichert.")
   "blocked","failed"->model.text("Weekly reference saved on this phone. Server transfer needs review.","Wochenreferenz auf diesem Telefon gespeichert. Serverübertragung muss geprüft werden.")
   else->model.text("Weekly reference saved locally. Server transfer pending…","Wochenreferenz lokal gespeichert. Serverübertragung ausstehend…")})}
  Copy(model.text("Weekly priorities · ","Wöchentliche Prioritäten · ")+week())
  Copy(model.text("How important is each goal for your journeys this week? Rate each separately. These values are the starting point for new searches.","Wie wichtig ist dir jedes Ziel für deine Wege diese Woche? Bewerte jedes einzeln. Diese Werte gelten als Ausgangspunkt für neue Suchen."))
  if(reference?.optString("source")=="inherited_explicit_ratings")Copy(model.text("Proposed from your previous declarations. Not yet confirmed for this week.","Aus deinen bisherigen Angaben vorgeschlagen. Für diese Woche noch nicht bestätigt."),Gold)
  if(reference==null)Copy(model.text("Please review all goals. No conversion from old allocation points has been made.","Bitte bewerte alle Ziele. Alte Verteilungspunkte wurden nicht umgerechnet."),Gold)
  reference?.optString("confirmed_at")?.takeUnless {it.isBlank()||it=="null"}?.let {Copy(model.text("Last saved: ","Zuletzt gespeichert: ")+it,MutedText)}
  needKeys.forEach {key->GoalRatingRow(model,key,initial.optInt(key,0),values.optInt(key,0)){rating->values=values.copyJson().put(key,rating)}}
  Button({model.saveWeeklyPriorities(values)},Modifier.fillMaxWidth().heightIn(min=48.dp),enabled=!model.busy&&needKeys.all {values.optInt(it) in 1..7}) {Text(model.text("Save weekly priorities","Wochenprioritäten speichern"))}
 }
}

@Composable internal fun JourneyPrioritySummary(model:CompanionModel,result:JSONObject) {
 val ref=result.optJSONObject("contextual_deliberation")?.optJSONObject("goal_priorities")?:return
 val weekly=ref.optJSONObject("weekly_reference")?.optJSONObject("ratings")?:return
 val effective=ref.optJSONObject("effective_ratings")?:return
 PriorityIconStrip(model,weekly,effective,Modifier.fillMaxWidth().padding(horizontal=8.dp))
}
