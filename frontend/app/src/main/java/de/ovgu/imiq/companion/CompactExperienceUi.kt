package de.ovgu.imiq.companion

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Seven explicit choices; missing never looks like an already selected neutral. */
@Composable internal fun ExperienceScale(label:String,value:Int?,range:IntRange,low:String,high:String,onChange:(Int)->Unit) {
 Column(verticalArrangement=Arrangement.spacedBy(5.dp)) {
  Text(label,style=MaterialTheme.typography.bodyLarge)
  BoxWithConstraints(Modifier.fillMaxWidth()) {
   val values=range.toList();val columns=if(maxWidth<348.dp||LocalDensity.current.fontScale>1.3f)4 else 7
   Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
    values.chunked(columns).forEach {row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(2.dp)) {
     row.forEach {score->
      val chosen=value==score
      OutlinedButton(onClick={onChange(score)},modifier=Modifier.weight(1f).heightIn(min=48.dp).semantics {
       contentDescription="$label: ${if(score>0&&range.first<0)"+$score"else score}"
       selected=chosen
      },contentPadding=PaddingValues(0.dp),border=BorderStroke(if(chosen)2.dp else 1.dp,if(chosen)Mint else MutedText),
       colors=ButtonDefaults.outlinedButtonColors(containerColor=if(chosen)Mint.copy(alpha=.16f)else Panel,contentColor=Pale)) {
       Text(if(score>0&&range.first<0)"+$score"else score.toString(),style=MaterialTheme.typography.bodyLarge)
      }
     }
     repeat(columns-row.size){Spacer(Modifier.weight(1f))}
    }}
   }
  }
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
   Text(low,style=MaterialTheme.typography.bodySmall,color=MutedText,modifier=Modifier.weight(1f))
   Text(if(range.first<0)"0 · Neutral"else"4 · Neutral",style=MaterialTheme.typography.bodySmall,color=MutedText,modifier=Modifier.padding(horizontal=4.dp))
   Text(high,style=MaterialTheme.typography.bodySmall,color=MutedText,textAlign=androidx.compose.ui.text.style.TextAlign.End,modifier=Modifier.weight(1f))
  }
 }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ExperienceForm(model:CompanionModel,record:JSONObject,trip:JSONObject,draftWriter:ExperienceDraftWriter=model.experienceDrafts) {
 Surface(Modifier.fillMaxSize(),color=Ink,contentColor=Pale){ExperienceFormContent(model,record,trip,draftWriter)}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ExperienceFormContent(model:CompanionModel,record:JSONObject,trip:JSONObject,draftWriter:ExperienceDraftWriter) {
 val journeyId=trip.getString("journey_id")
 val searchId=record.getString("search_id")
 val planned=remember(journeyId){plannedExperienceModes(record,trip.getJSONObject("selection"))}
 val scope=rememberCoroutineScope()
 var draft by remember(journeyId){mutableStateOf(obj("schema_version" to "experience-draft-v1","search_id" to searchId,
  "journey_id" to journeyId,"ended_at" to trip.optString("ended_at"),"actual" to JSONArray(planned),
  "feelings" to JSONObject(),"satisfaction" to JSONObject(),"note" to "","changes" to ""))}
 var loaded by remember(journeyId){mutableStateOf(false)}
 var saving by remember(journeyId){mutableStateOf(false)}
 var failure by remember(journeyId){mutableStateOf<String?>(null)}
 var details by remember(journeyId){mutableStateOf(false)}
 var help by remember(journeyId){mutableStateOf(false)}
 var goalMenu by remember {mutableStateOf(false)}
 var goal by remember {mutableStateOf("speed")}
 val draftFailure by draftWriter.failure.collectAsState()
 suspend fun loadDraft() {
  try {
   val saved=draftWriter.load(journeyId)
   if(saved!=null&&saved.optContractString("schema_version")=="experience-draft-v1"&&saved.optString("search_id")==searchId&&saved.optString("journey_id")==journeyId&&saved.optString("ended_at")==trip.optString("ended_at"))draft=saved
   loaded=true;failure=null
  }catch(e:CancellationException){throw e}catch(e:Exception){failure=e.message}
 }
 LaunchedEffect(journeyId){loadDraft()}
 fun edit(change:(JSONObject)->Unit) {
  if(saving||!loaded)return
  val next=draft.copyJson();change(next);next.put("updated_at",now());draft=next
  draftWriter.save(journeyId,next)
 }
 val completion=draft.optString("completion").takeIf {it in listOf("as_planned","changed","not_taken")}
 val actual=when(completion){"as_planned"->planned;"changed"->draft.optJSONArray("actual")?.let {a->(0 until a.length()).map {a.getString(it)}.filter {it in modes}.distinct()}.orEmpty();else->emptyList()}
 val feelings=draft.optJSONObject("feelings")?:JSONObject()
 val comfort=draft.optJSONObject("comfort")?.takeIf {it.optString("mode") in actual&&(it.optString("mode_selection")=="single_actual_mode")==(actual.size==1)}
 val missing=when {
  completion==null->model.text("Confirm how you travelled.","Bestätige, wie du gereist bist.")
  completion=="not_taken"->null
  actual.isEmpty()->model.text("Choose the modes you used.","Wähle die genutzten Verkehrsmittel.")
  actual.any {!feelings.has(it)}->model.text("Rate your feeling for each used mode.","Bewerte dein Gefühl für jedes genutzte Verkehrsmittel.")
  comfort==null->model.text("Rate physical comfort.","Bewerte den körperlichen Komfort.")
  else->null
 }
 fun later(){if(!loaded){model.openSavedAlternatives(searchId);return};if(!saving){saving=true;scope.launch {
  try {draftWriter.save(journeyId,draft).await();model.openSavedAlternatives(searchId)}
  catch(e:CancellationException){throw e}catch(e:Exception){failure=e.message}
  finally{saving=false}
 }}}
 BackHandler {later()}
 Column(Modifier.fillMaxSize().imePadding()) {
  Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
   Text(model.text("How was your journey?","Wie war deine Reise?"),style=MaterialTheme.typography.headlineSmall,color=Pale)
   val places=record.optJSONObject("places")
   val origin=places?.optJSONObject("origin")?.optString("label").orEmpty()
   val destination=places?.optJSONObject("destination")?.optString("label").orEmpty()
   Text(listOf(origin,destination).filter {it.isNotBlank()}.joinToString(" → "),style=MaterialTheme.typography.bodySmall,color=MutedText,maxLines=2)
   val date=remember(journeyId){runCatching {java.time.Instant.parse(trip.getString("ended_at")).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy · HH:mm"))}.getOrDefault("")}
   Text(date + " · "+planned.map {modeName(it,model.de)}.joinToString(" · "),style=MaterialTheme.typography.bodySmall,color=MutedText)
   if(!loaded){CircularProgressIndicator(color=Mint);failure?.let {Text(it,color=MaterialTheme.colorScheme.error);TextButton({scope.launch {loadDraft()}}){Text(model.text("Retry","Erneut versuchen"))}}}
   else {
    Text(model.text("Did you travel as planned?","Bist du wie geplant gereist?"),style=MaterialTheme.typography.bodyLarge)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
     val options=if(trip.optString("capture_mode")=="manual")listOf("as_planned","changed","not_taken")else listOf("as_planned","changed")
     options.forEach {option->OutlinedButton({edit {it.put("completion",option)}},Modifier.weight(1f).heightIn(min=48.dp),enabled=!saving,
      contentPadding=PaddingValues(horizontal=6.dp,vertical=8.dp),border=BorderStroke(if(completion==option)2.dp else 1.dp,if(completion==option)Mint else MutedText),
      colors=ButtonDefaults.outlinedButtonColors(contentColor=Pale,containerColor=if(completion==option)Mint.copy(alpha=.16f)else Panel)) {
      Text(when(option){"as_planned"->model.text("Yes","Ja");"changed"->model.text("With changes","Mit Änderungen");else->model.text("Not taken","Nicht gereist")},style=MaterialTheme.typography.bodyMedium)
     }}
    }
    if(completion=="changed") {
     Text(model.text("Which modes did you use, including walking?","Welche Verkehrsmittel hast du genutzt, einschließlich Fußwegen?"),style=MaterialTheme.typography.bodyMedium)
     FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {modes.forEach {mode->FilterChip(selected=mode in actual,onClick={edit {d->d.put("actual",JSONArray(if(mode in actual)actual-mode else actual+mode))}},enabled=!saving,label={Text(modeName(mode,model.de))})}}
    }
    if(actual.isNotEmpty()) {
     HorizontalDivider(color=MutedText.copy(alpha=.25f))
     actual.forEach {mode->
      ExperienceScale(model.text("Feeling · ","Gefühl · ")+modeName(mode,model.de),feelings.number(mode)?.toInt(),-3..3,
       model.text("−3 · Very unpleasant","−3 · Sehr unangenehm"),model.text("+3 · Very pleasant","+3 · Sehr angenehm")) {score->if(!saving)edit {d->d.put("feelings",feelings.copyJson().put(mode,score))}}
     }
     HorizontalDivider(color=MutedText.copy(alpha=.25f))
     Text(model.text("Physical comfort","Körperlicher Komfort"),style=MaterialTheme.typography.titleMedium)
     var comfortMode by remember(actual){mutableStateOf(if(actual.size==1)actual.single()else comfort?.optString("mode"))}
     if(actual.size>1){
      Text(model.text("Choose one mode to rate its comfort.","Wähle ein Verkehrsmittel für die Komfortbewertung."),style=MaterialTheme.typography.bodySmall,color=MutedText)
      FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {actual.forEach {mode->FilterChip(comfortMode==mode,{comfortMode=mode;edit {it.remove("comfort")}},enabled=!saving,label={Text(modeName(mode,model.de))})}}
     }
     comfortMode?.takeIf {it in actual}?.let {mode->
      ExperienceScale(model.text("During this journey, how much did ${modeName(mode,false).lowercase()} help or hinder meeting your need for physical comfort?",
       "Wie sehr hat ${modeName(mode,true)} auf dieser Reise geholfen oder erschwert, dein Bedürfnis nach körperlichem Komfort zu erfüllen?"),
       comfort?.takeIf {it.optString("mode")==mode}?.getInt("rating"),1..7,
       model.text("1 · Hindered a lot","1 · Stark erschwert"),model.text("7 · Helped a lot","7 · Stark geholfen")) {score->if(!saving)edit {it.put("comfort",comfortObservation(mode,score,if(model.de)"de"else"en",now(),actual.size>1))}}
     }
     TextButton({help=!help},contentPadding=PaddingValues(0.dp)){Text(model.text("About these questions","Zu diesen Fragen"),style=MaterialTheme.typography.bodySmall)}
     if(help)Text(model.text("Rate how using each mode felt during its part of this journey. 0 = neutral feeling. Physical comfort concerns your body; 4 = neither helped nor hindered. Neither neutral value means unanswered.",
      "Bewerte, wie sich jedes Verkehrsmittel während seines Reiseabschnitts angefühlt hat. 0 = neutrales Gefühl. Körperlicher Komfort betrifft deinen Körper; 4 = weder geholfen noch erschwert. Neutral bedeutet nicht unbeantwortet."),style=MaterialTheme.typography.bodySmall,color=MutedText)
    }
    TextButton({details=!details},contentPadding=PaddingValues(0.dp)){Text((if(details)"− "else"+ ")+model.text("Add details · optional","Details ergänzen · freiwillig"))}
    if(details) {
     if(actual.isNotEmpty()) {
      Text(model.text("Other goals","Weitere Ziele"),style=MaterialTheme.typography.titleMedium)
      Box {TextButton({goalMenu=true}){Text(goalCopy(goalKeys[needKeys.indexOf(goal)],model.de).getString("title")+" ▾")}
       DropdownMenu(goalMenu,{goalMenu=false}){needKeys.filter {it!="comfort"}.forEach {key->DropdownMenuItem(text={Text(goalCopy(goalKeys[needKeys.indexOf(key)],model.de).getString("title"))},onClick={goal=key;goalMenu=false})}}
      }
      val satisfaction=draft.optJSONObject("satisfaction")?:JSONObject()
      ExperienceScale(model.text("How well did this journey meet this goal overall?","Wie gut erfüllte diese Reise dieses Ziel insgesamt?"),satisfaction.number(goal)?.toInt(),1..7,model.text("1 · Very poor","1 · Sehr schlecht"),model.text("7 · Very good","7 · Sehr gut")) {score->if(!saving)edit {it.put("satisfaction",satisfaction.copyJson().put(goal,score))}}
      TextButton({edit {it.put("satisfaction",satisfaction.copyJson().apply {remove(goal)})}}){Text(model.text("Clear rating","Bewertung löschen"))}
     }
     if(completion=="changed")OutlinedTextField(draft.optString("changes"),{value->edit {it.put("changes",value.take(1000))}},Modifier.fillMaxWidth(),enabled=!saving,label={Text(model.text("What changed?","Was hat sich geändert?"))})
     OutlinedTextField(draft.optString("note"),{value->edit {it.put("note",value.take(1000))}},Modifier.fillMaxWidth(),enabled=!saving,label={Text(model.text("Anything else?","Möchtest du etwas ergänzen?"))})
    }
    (failure?:draftFailure)?.let {Text(model.text("Could not save: ","Speichern fehlgeschlagen: ")+it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
   }
  }
  Surface(color=Panel,shadowElevation=4.dp) {
   Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
    if(loaded&&missing!=null)Text(missing,style=MaterialTheme.typography.bodySmall,color=MutedText)
    Button(onClick={if(!saving){saving=true;failure=null;scope.launch {
     try {
      draftWriter.flush()
      val emotions=obj(*actual.map {it to feelings.getInt(it)}.toTypedArray())
      withContext(Dispatchers.IO){model.trips.confirmExperience(searchId,journeyId,completion!!,actual,JSONObject(),if(completion=="changed")draft.optString("changes")else "",draft.optString("note"),now(),if(completion=="not_taken")JSONObject()else draft.optJSONObject("satisfaction")?:JSONObject(),emotions,comfort,EXPERIENCE_MODE_REPORTING)}
      model.returnAfterExperience()
     }catch(e:CancellationException){throw e}catch(e:Exception){failure=e.message}
     finally{saving=false}
    }}},enabled=loaded&&!saving&&missing==null,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),colors=ButtonDefaults.buttonColors(containerColor=Mint,contentColor=Ink)) {
     Text(if(saving)model.text("Saving…","Wird gespeichert…")else model.text("Save experience","Erfahrung speichern"),style=MaterialTheme.typography.titleMedium)
    }
    TextButton({later()},enabled=!saving,modifier=Modifier.fillMaxWidth(),contentPadding=PaddingValues(0.dp)){Text(model.text("Later · keep draft","Später · Entwurf behalten"),style=MaterialTheme.typography.bodySmall)}
   }
  }
 }
}
