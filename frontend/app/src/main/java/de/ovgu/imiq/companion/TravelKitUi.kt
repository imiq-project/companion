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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val travelKitModes=listOf("walk","bike","pt","car")

/** Habitual use and route availability remain independent; no scene/mascot. */
@Composable fun TravelKitScreen(model:CompanionModel) {
 var index by rememberSaveable {mutableIntStateOf(0)}
 val mode=travelKitModes[index]
 val available=model.availability();val habits=model.mobilityHabits()
 val answered=travelKitModes.count {habits.opt(it) is Boolean}
 val hasOptions=travelKitModes.any {available.optBoolean(it,true)}
 val complete=answered==4&&hasOptions
 val scroll=rememberScrollState()
 fun change(delta:Int){index=(index+delta+travelKitModes.size)%travelKitModes.size}
 BackHandler{if(index>0)index--else model.goStep("identity")}
 LaunchedEffect(index){scroll.scrollTo(0)}
 Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter){
  Column(Modifier.widthIn(max=390.dp).fillMaxSize()){
   Column(Modifier.weight(1f).verticalScroll(scroll).padding(16.dp),verticalArrangement=Arrangement.spacedBy(20.dp)){
    Text(model.text("Your everyday mobility","Deine Mobilität im Alltag"),style=MaterialTheme.typography.headlineLarge,color=Pale)
    Text(model.text("Mode ${index+1} of 4","Verkehrsmittel ${index+1} von 4"),style=MaterialTheme.typography.bodySmall,color=MutedText)
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
     TextButton({change(-1)},Modifier.size(48.dp).semantics {contentDescription=model.text("Previous mode","Vorheriges Verkehrsmittel")}){Text("←")}
     Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
      PixelIcon(mode,Modifier.size(26.dp));Text(modeName(mode,model.de),style=MaterialTheme.typography.titleLarge,color=Pale)
     }
     TextButton({change(1)},Modifier.size(48.dp).semantics {contentDescription=model.text("Next mode","Nächstes Verkehrsmittel")}){Text("→")}
    }
    HorizontalDivider(color=Edge)
    Text(model.text("Do you usually use this mode for your everyday journeys?","Nutzt du dieses Verkehrsmittel normalerweise für deine Alltagswege?"),style=MaterialTheme.typography.bodyLarge,color=Pale)
    Text(model.text("Answer for each mode, including walking. This describes your usual use; route availability is set below.","Antworte für jedes Verkehrsmittel, auch fürs Gehen. Hier geht es um deine übliche Nutzung; verfügbare Optionen legst du unten fest."),style=MaterialTheme.typography.bodyMedium,color=MutedText)
    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
     listOf(true,false).forEach {value->PixelButton(if(value)model.text("Usually","Regelmäßig")else model.text("Not usually","Nicht regelmäßig"),
      {model.saveMobilityHabit(mode,value)},Modifier.weight(1f),primary=habits.opt(mode)==value)}
    }
    Text(model.text("$answered of 4 answered","$answered von 4 beantwortet"),style=MaterialTheme.typography.bodySmall,color=MutedText)
    if(mode=="car")Disclosure(model.text("Vehicle details","Fahrzeugdetails")){VehicleProfileSettings(model)}
    if(mode=="bike")Disclosure(model.text("Bicycle details","Fahrraddetails")){BicycleEnergySettings(model)}
    HorizontalDivider(color=Edge)
    Disclosure(model.text("Options for route suggestions","Optionen für Routenvorschläge")){
     Copy(model.text("Enable modes you can use for route suggestions, including modes you do not usually use. Availability does not express liking or importance.","Aktiviere nutzbare Verkehrsmittel für Routenvorschläge, auch wenn du sie nicht regelmäßig nutzt. Verfügbarkeit beschreibt weder Gefallen noch Wichtigkeit."))
     travelKitModes.forEach {key->Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
      PixelIcon(key);Text(modeName(key,model.de),Modifier.weight(1f))
      Switch(available.optBoolean(key,true),{enabled->model.saveAvailability(model.availability().copyJson().put(key,enabled))})
     }}
    }
    if(!hasOptions)Copy(model.text("Include at least one option for route suggestions to continue.","Aktiviere mindestens eine Option für Routenvorschläge, um fortzufahren."))
   }
   Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){
    PixelButton(model.text("← Back","← Zurück"),{if(index>0)index--else model.goStep("identity")},Modifier.weight(1f))
    PixelButton(if(complete)model.text("Next: goals →","Weiter: Ziele →")else model.text("Next mode →","Nächster Modus →"),{
     if(complete)model.confirmMobilityOptions()else if(answered<4){
      index=(1..4).map {(index+it)%4}.first {habits.opt(travelKitModes[it]) !is Boolean}
     }
    },Modifier.weight(2f),enabled=complete||answered<4&&habits.opt(mode) is Boolean,primary=true)
   }
  }
 }
}
