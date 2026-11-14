package de.ovgu.imiq.companion

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Wording v2: habitual everyday-journey affect, not a momentary journey report. */
internal fun everydayValenceQuestion(mode:String,de:Boolean):String {
 val action=when(mode){
  "walk"->"walking" to "das Zufußgehen"
  "bike"->"cycling" to "das Radfahren"
  "pt"->"using public transport" to "die Nutzung öffentlicher Verkehrsmittel"
  "car"->"travelling by car" to "das Autofahren"
  else->error("Unknown transport mode")
 }
 return if(de)"Wenn du an deine Alltagswege denkst: Wie angenehm oder unangenehm fühlt sich ${action.second} für dich im Allgemeinen an?"
 else "Thinking about your everyday journeys, how pleasant or unpleasant does ${action.first} generally feel to you?"
}

@Composable internal fun ProfileFeelingsScreen(model:CompanionModel){
 var index by rememberSaveable {mutableIntStateOf((model.store.json("imiq.experimental.valences-draft.v1")?.optInt("index")?:0).coerceIn(modes.indices))}
 val mode=modes[index];val answers=model.valences()
 val rating=if(answers.isNull(mode))null else answers.getInt(mode)
 val answered=modes.count {answers.opt(it) is Int&&answers.getInt(it) in -3..3}
 val scroll=rememberScrollState()
 fun previous(){if(index>0)index--else model.goStep("goals")}
 BackHandler(enabled=!model.busy){previous()}
 LaunchedEffect(index){scroll.scrollTo(0)}
 Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter){
  Column(Modifier.widthIn(max=390.dp).fillMaxSize()){
   Column(Modifier.weight(1f).verticalScroll(scroll).padding(16.dp),verticalArrangement=Arrangement.spacedBy(20.dp)){
    TextButton({previous()},enabled=!model.busy,modifier=Modifier.heightIn(min=48.dp)){
     Text(if(index==0)model.text("← Back","← Zurück")else model.text("← Previous mode","← Vorheriges Verkehrsmittel"))
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
     Text(model.text("Your everyday journeys","Deine Alltagswege"),Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=MutedText)
     Text(model.text("Mode ${index+1} of 4","Verkehrsmittel ${index+1} von 4"),style=MaterialTheme.typography.bodySmall,color=MutedText)
    }
    Text(model.text("How does it feel?","Wie fühlt es sich an?"),style=MaterialTheme.typography.displayMedium,color=Pale)
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
     PixelIcon(mode,Modifier.size(28.dp));Text(modeName(mode,model.de),style=MaterialTheme.typography.headlineMedium,color=Pale)
    }
    HorizontalDivider(color=Edge)
    ValenceEditor("imdillo",mode,rating,model.settings().optString("motion")=="full",model.de,
     prompt=everydayValenceQuestion(mode,model.de),showModeNavigation=false,showScene=false){model.rateFeeling(mode,it,index)}
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp)){
     Text(model.text("−3 · Very unpleasant","−3 · Sehr unangenehm"),Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=MutedText)
     Text(model.text("+3 · Very pleasant","+3 · Sehr angenehm"),Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=MutedText,textAlign=androidx.compose.ui.text.style.TextAlign.End)
    }
    Text(model.text("0 means neutral. It is a response, not an unanswered question.","0 bedeutet neutral. Das ist eine Antwort, keine unbeantwortete Frage."),style=MaterialTheme.typography.bodyMedium,color=MutedText)
   }
   Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
    Text(model.text("$answered of 4 rated","$answered von 4 bewertet"),style=MaterialTheme.typography.bodySmall,color=MutedText)
    PixelButton(if(index<modes.lastIndex)model.text("Next mode →","Nächstes Verkehrsmittel →")else model.text("Next: connections →","Weiter: Verbindungen →"),
     {if(index<modes.lastIndex)index++else model.saveFeelings()},Modifier.fillMaxWidth(),
     enabled=!model.busy&&rating!=null&&(index<modes.lastIndex||answered==4),primary=true)
   }
  }
 }
}
