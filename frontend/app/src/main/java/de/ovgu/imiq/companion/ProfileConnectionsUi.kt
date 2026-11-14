package de.ovgu.imiq.companion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** The original seven ordinal anchors; null remains distinct from explicit 4. */
@Composable internal fun ConnectionSlider(value:Int?,labels:List<String>,de:Boolean,enabled:Boolean=true,onChange:(Int)->Unit) {
 var pending by remember(value){mutableIntStateOf(value?:4)}
 val status=if(value==null)if(de)"Unbeantwortet"else"Not answered" else "$value · ${labels[value-1]}"
 Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
  HorizontalDivider(color=Edge)
  Text(status,style=MaterialTheme.typography.titleMedium,color=Pale,modifier=Modifier.padding(top=12.dp))
  Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
   TextButton({onChange(((value?:4)-1).coerceAtLeast(1))},Modifier.size(48.dp),enabled=enabled&&value!=1,
    contentPadding=PaddingValues(0.dp)){Text("−",Modifier.semantics {contentDescription=if(de)"Eine Stufe Richtung erschwert"else"One step towards hinders"})}
   Column(Modifier.weight(1f)){
    Slider(value=(value?:4).toFloat(),onValueChange={pending=it.roundToInt().coerceIn(1,7);onChange(pending)},
     onValueChangeFinished={if(value==null)onChange(pending)},valueRange=1f..7f,steps=5,enabled=enabled,
     modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).semantics {
      contentDescription=if(de)"Verbindung zwischen diesem Verkehrsmittel und diesem Ziel"else"How this transport mode relates to this goal"
      stateDescription=status
     },colors=SliderDefaults.colors(thumbColor=if(value==null)MutedText else Mint,
      activeTrackColor=if(value==null)MutedText else Mint,inactiveTrackColor=Edge))
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){(1..7).forEach {Text("$it",style=MaterialTheme.typography.bodySmall,color=MutedText)}}
   }
   TextButton({onChange(((value?:4)+1).coerceAtMost(7))},Modifier.size(48.dp),enabled=enabled&&value!=7,
    contentPadding=PaddingValues(0.dp)){Text("+",Modifier.semantics {contentDescription=if(de)"Eine Stufe Richtung erleichtert"else"One step towards helps"})}
  }
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp)){
   Text("1 · ${labels.first()}",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=Pale)
   Text("7 · ${labels.last()}",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=Pale)
  }
  Text(if(de)"Bewege den Regler, um zu antworten. Die Mitte ist nur neutral, wenn du sie auswählst."
   else"Move the slider to choose your answer. The centre is neutral only when you select it.",style=MaterialTheme.typography.bodyMedium,color=MutedText)
  TextButton({onChange(4)},Modifier.heightIn(min=48.dp),enabled=enabled){
   Text(if(de)"Neutral wählen · 4"else"Choose neutral · 4")
  }
 }
}

@Composable internal fun ProfileConnectionsScreen(model:CompanionModel) {
 LaunchedEffect(Unit){if(model.beliefStart==null)model.prepareBeliefs()}
 val questions=model.beliefStart?.objects("questions").orEmpty()
 val question=questions.getOrNull(model.beliefIndex)
 val last=model.beliefIndex==questions.lastIndex
 val allAnswered=questions.size==20&&questions.all {val cell=it.getString("cell_id");model.beliefAnswers.opt(cell) is Int&&model.beliefAnswers.getInt(cell) in 1..7}
 Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter){
  Column(Modifier.widthIn(max=390.dp).fillMaxSize()){
   Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(20.dp)){
    TextButton({if(model.beliefIndex>0){model.beliefIndex--;model.persistBeliefs()}else model.goStep("valences")},enabled=!model.busy){Text(model.text("← Back","← Zurück"))}
    if(question!=null){
     Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
      Text(model.text("Connections","Verbindungen"),style=MaterialTheme.typography.bodySmall,color=MutedText)
      Text(model.text("Question ${model.beliefIndex+1} of ${questions.size}","Frage ${model.beliefIndex+1} von ${questions.size}"),style=MaterialTheme.typography.bodySmall,color=MutedText)
     }
     LinearProgressIndicator(progress={(model.beliefIndex+1).toFloat()/questions.size},Modifier.fillMaxWidth(),color=Mint,trackColor=Edge)
     val cell=question.getString("cell_id");val mode=question.getString("hotco_mode");val need=question.getString("model_need")
     val value=if(model.beliefAnswers.isNull(cell))null else model.beliefAnswers.getInt(cell)
     Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
      Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
       PixelIcon(mode,Modifier.size(28.dp));Text(modeName(mode,model.de),style=MaterialTheme.typography.displayMedium,color=Pale)
      }
      Text(needName(need,model.de),style=MaterialTheme.typography.titleMedium,color=Mint)
     }
     Text(model.text("In your view, on your usual journeys, how much does this mode help or hinder meeting this goal? Rate the connection, separately from goal importance or your feeling about the mode.","Wie sehr hilft oder erschwert dieses Verkehrsmittel deiner Ansicht nach auf deinen üblichen Wegen, dieses Ziel zu erfüllen? Bewerte die Verbindung getrennt von der Zielwichtigkeit und deinem Gefühl zum Verkehrsmittel."),style=MaterialTheme.typography.bodyLarge,color=Pale)
     val anchors=catalog().getJSONObject("beliefs").getJSONObject(model.locale).getJSONArray("labels")
     ConnectionSlider(value,List(7){anchors.getString(it)},model.de,enabled=!model.busy){model.rateBelief(cell,it)}
    }
    if(model.busy){CircularProgressIndicator();Copy(if(last)model.text("Creating your Passport…","Dein Passport wird erstellt…")else model.text("Preparing your connections…","Verbindungen werden vorbereitet…"))}
    else if(question==null)PixelButton(model.text("Retry","Erneut versuchen"),{model.prepareBeliefs(true)})
   }
   if(question!=null)Box(Modifier.fillMaxWidth().padding(16.dp)){
    PixelButton(if(!last)model.text("Next connection →","Nächste Verbindung →")else model.text("Confirm my Passport →","Meinen Passport bestätigen →"),
     {model.beliefNext()},Modifier.fillMaxWidth(),enabled=!model.busy&&!model.beliefAnswers.isNull(question.getString("cell_id"))&&(!last||allAnswered),primary=true)
   }
  }
 }
}
