package de.ovgu.imiq.companion

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable private fun ImportanceChevron(up:Boolean,modifier:Modifier=Modifier) {
 Canvas(modifier) {
  val middle=if(up).35f else .65f;val side=if(up).65f else .35f
  drawLine(Pale,Offset(size.width*.2f,size.height*side),Offset(size.width*.5f,size.height*middle),2.dp.toPx(),StrokeCap.Round)
  drawLine(Pale,Offset(size.width*.5f,size.height*middle),Offset(size.width*.8f,size.height*side),2.dp.toPx(),StrokeCap.Round)
 }
}

/** No saved reference or model result is a parameter of this measurement UI. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SituatedImportanceDialog(
 sessionId:String,state:TripImportanceDraft,de:Boolean,animated:Boolean=true,
 onChange:(TripImportanceDraft)->Unit,onDismiss:()->Unit,onConfirm:()->Unit,
) {
 fun text(en:String,german:String)=if(de)german else en
 var direct by remember(sessionId){mutableStateOf(false)}
 val scroll=rememberScrollState()
 val initialFocus=remember(sessionId){FocusRequester()}
 val maxHeight=(LocalConfiguration.current.screenHeightDp*.94f).dp
 ModalBottomSheet(onDismissRequest=onDismiss,containerColor=Panel,
  sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),
  modifier=Modifier.semantics {paneTitle=text("Priorities for this trip","Prioritäten für diese Reise")}) {
  Column(Modifier.fillMaxWidth().heightIn(max=maxHeight)) {
   Row(Modifier.fillMaxWidth().padding(start=22.dp,end=12.dp),verticalAlignment=Alignment.Top) {
    Column(Modifier.weight(1f).padding(top=4.dp,bottom=8.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
     Text(text("JUST FOR THIS TRIP","NUR FÜR DIESE REISE"),color=MutedText,style=MaterialTheme.typography.labelSmall)
     Text(text("What matters right now?","Was zählt gerade?"),style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold)
    }
    IconButton(onDismiss,Modifier.size(48.dp).semantics {contentDescription=text("Cancel and return to trip","Abbrechen und zur Reise zurück")}) {
     Canvas(Modifier.size(20.dp)) {drawLine(MutedText,Offset(size.width*.2f,size.height*.2f),Offset(size.width*.8f,size.height*.8f),2.dp.toPx(),StrokeCap.Round);drawLine(MutedText,Offset(size.width*.8f,size.height*.2f),Offset(size.width*.2f,size.height*.8f),2.dp.toPx(),StrokeCap.Round)}
    }
   }
   Text(text("For this trip, how important is each goal to you? Rate each separately.","Wie wichtig ist dir jedes Ziel für diese Reise? Bewerte jedes einzeln."),Modifier.padding(horizontal=22.dp).padding(bottom=12.dp),color=MutedText,style=MaterialTheme.typography.bodyMedium)
   Column(Modifier.weight(1f,fill=false).fillMaxWidth().verticalScroll(scroll).padding(horizontal=18.dp,vertical=4.dp)) {
    if(!state.reviewing) {
     Row(Modifier.fillMaxWidth().selectableGroup(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
      goalKeys.forEachIndexed {index,key->
       val name=tripImportanceName(index,de);val value=state.values[index]
       val selected=state.selected==index
       val status=if(value==null)text("not rated","noch nicht bewertet")else text("$value of 7","$value von 7")
       val focus=if(index==0)Modifier.focusRequester(initialFocus)else Modifier
       Surface(Modifier.weight(1f),shape=RoundedCornerShape(16.dp),color=if(selected)PanelRaised else Panel,
        border=BorderStroke(1.dp,if(selected)Mint else Panel)) {
        Column(focus.fillMaxWidth().heightIn(min=92.dp).selectable(selected=selected,role=Role.Tab,onClick={onChange(state.select(index))})
         .semantics(mergeDescendants=true){contentDescription="$name: $status"}.padding(horizontal=2.dp,vertical=10.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)) {
         Text(value?.let {"$it / 7"}?:"—",style=MaterialTheme.typography.labelSmall,color=if(value==null)MutedText else Pale,modifier=Modifier.clearAndSetSemantics {})
         GoalLineIcon(key,Modifier.size(25.dp))
         Text((if(de)listOf("Umwelt","Aktivität","Geld","Zeit","Komfort")else listOf("Planet","Activity","Money","Time","Comfort"))[index],style=MaterialTheme.typography.labelSmall,textAlign=TextAlign.Center,modifier=Modifier.clearAndSetSemantics {})
        }
       }
      }
     }
     HorizontalDivider(Modifier.padding(top=16.dp),color=Edge.copy(alpha=.35f))
     Row(Modifier.fillMaxWidth().padding(top=18.dp,bottom=4.dp),horizontalArrangement=Arrangement.spacedBy(8.dp,Alignment.CenterHorizontally),verticalAlignment=Alignment.CenterVertically) {
      GoalLineIcon(goalKeys[state.selected],Modifier.size(24.dp));Text(tripImportanceName(state.selected,de),style=MaterialTheme.typography.titleMedium,textAlign=TextAlign.Center)
     }
     Text((if(de)listOf("Die Umweltbelastung dieser Reise verringern","Auf dieser Reise körperlich aktiv sein","Die Kosten dieser Reise niedrig halten","Die Reisezeit kurz halten","Sich unterwegs körperlich wohlfühlen")else listOf("Reducing the environmental impact of this trip","Getting physical activity during this trip","Keeping your travel costs down","Keeping your travel time down","Feeling physically comfortable while travelling"))[state.selected],Modifier.fillMaxWidth().padding(bottom=8.dp),style=MaterialTheme.typography.bodySmall,color=MutedText,textAlign=TextAlign.Center)
     if(direct) {
      Column(Modifier.fillMaxWidth().selectableGroup(),verticalArrangement=Arrangement.spacedBy(5.dp)) {
       (1..7).forEach {rating->
        Surface(shape=RoundedCornerShape(12.dp),color=if(state.values[state.selected]==rating)PanelRaised else Panel,border=BorderStroke(1.dp,if(state.values[state.selected]==rating)Mint else Edge.copy(alpha=.45f))) {
         Row(Modifier.fillMaxWidth().heightIn(min=52.dp).selectable(selected=state.values[state.selected]==rating,role=Role.RadioButton,onClick={onChange(state.choose(rating,"direct"))})
          .padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
          Text("$rating",style=MaterialTheme.typography.titleMedium,modifier=Modifier.width(24.dp));Text(tripImportanceAnchor(rating,de),style=MaterialTheme.typography.bodyMedium)
         }
        }
       }
      }
     } else ImportanceWheel(state,de,animated,onChange)
     Row(Modifier.fillMaxWidth().padding(top=6.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
      TextButton({direct=!direct},Modifier.heightIn(min=48.dp)){Text(if(direct)text("Use the wheel","Rad verwenden")else text("Choose directly","Direkt auswählen"),style=MaterialTheme.typography.labelLarge)}
      OutlinedButton({onChange(state.setSelected())},Modifier.heightIn(min=48.dp),enabled=state.values[state.selected]!=null,shape=RoundedCornerShape(13.dp),contentPadding=PaddingValues(12.dp,8.dp)) {
       Text(if(state.ratedCount==4&&state.confirmed[state.selected]!=state.values[state.selected])text("Set & review","Setzen & prüfen")else text("Set & next","Setzen & weiter"),style=MaterialTheme.typography.labelLarge)
      }
     }
    }else {
     Text(text("Ready for this trip?","Bereit für diese Reise?"),style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(top=6.dp))
     Text(text("Check your five answers. You can change any of them.","Prüfe deine fünf Antworten. Du kannst jede ändern."),color=MutedText,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.padding(vertical=12.dp))
     goalKeys.forEachIndexed {i,key->
      val name=tripImportanceName(i,de);val rating=state.confirmed[i]!!
      Row(Modifier.fillMaxWidth().heightIn(min=70.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
       GoalLineIcon(key,Modifier.size(28.dp))
       Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){Text(name,style=MaterialTheme.typography.bodyMedium);Text(tripImportanceAnchor(rating,de),color=MutedText,style=MaterialTheme.typography.bodySmall)}
       Text("$rating",style=MaterialTheme.typography.headlineSmall)
       IconButton({onChange(state.select(i))},Modifier.size(48.dp).semantics {contentDescription=text("Edit $name","$name bearbeiten")}) {ImportanceChevron(true,Modifier.size(20.dp).graphicsLayer {rotationZ=90f})}
      }
      HorizontalDivider(color=Edge.copy(alpha=.25f))
     }
     TextButton({onChange(state.copy(reviewing=false))},Modifier.heightIn(min=48.dp)){Text(text("Back to editing","Zurück zur Bearbeitung"))}
    }
   }
   HorizontalDivider(color=Edge.copy(alpha=.35f))
   Column(Modifier.fillMaxWidth().padding(bottom=WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()).padding(horizontal=20.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
     TextButton(onDismiss,Modifier.heightIn(min=48.dp)){Text(text("Cancel","Abbrechen"))}
     Text(text("${state.ratedCount} of 5 rated","${state.ratedCount} von 5 bewertet"),color=MutedText,style=MaterialTheme.typography.bodySmall,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
    }
    Button({if(state.reviewing)onConfirm()else onChange(state.copy(reviewing=true))},Modifier.fillMaxWidth().heightIn(min=52.dp),enabled=state.complete,shape=RoundedCornerShape(15.dp)) {
     Text(if(state.reviewing)text("Confirm & find routes","Bestätigen & Wege suchen")else text("Review priorities","Prioritäten prüfen"))
    }
   }
  }
 }
 LaunchedEffect(sessionId){initialFocus.requestFocus()}
 // Each next goal must show its own label, including after a long direct-choice list.
 LaunchedEffect(state.reviewing,state.selected,direct){scroll.scrollTo(0)}
}

@Composable private fun ImportanceWheel(state:TripImportanceDraft,de:Boolean,animated:Boolean,onChange:(TripImportanceDraft)->Unit) {
 fun text(en:String,german:String)=if(de)german else en
 val value=state.values[state.selected];val name=tripImportanceName(state.selected,de)
 var drag by remember(state.selected){mutableFloatStateOf(0f)}
 val threshold=with(LocalDensity.current){28.dp.toPx()}
 Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
  Column(Modifier.width(64.dp)){Text("1",style=MaterialTheme.typography.labelMedium);Text(text("Not at all\nimportant","Überhaupt\nnicht wichtig"),style=MaterialTheme.typography.labelSmall,color=MutedText)}
  Column(horizontalAlignment=Alignment.CenterHorizontally) {
   FilledTonalIconButton({onChange(state.step(1))},enabled=value!=7,modifier=Modifier.size(48.dp).semantics {contentDescription=text("Increase importance for $name","Wichtigkeit erhöhen: $name")}){ImportanceChevron(true,Modifier.size(20.dp))}
   Box(Modifier.width(126.dp).height(142.dp).draggable(orientation=Orientation.Vertical,
    state=rememberDraggableState {delta->
     drag+=delta;var next=state
     while(abs(drag)>=threshold){val direction=if(drag<0)1 else -1;next=next.step(direction,"wheel");drag+=if(drag<0)threshold else -threshold}
     if(next!=state)onChange(next)
    },onDragStarted={drag=0f},onDragStopped={drag=0f})
    .onPreviewKeyEvent {event->
     if(event.type!=KeyEventType.KeyDown)false else when(event.key){
      Key.DirectionUp,Key.DirectionRight->{onChange(state.step(1,"keyboard"));true}
      Key.DirectionDown,Key.DirectionLeft->{onChange(state.step(-1,"keyboard"));true}
      Key.MoveHome->{onChange(state.choose(1,"keyboard"));true}
      Key.MoveEnd->{onChange(state.choose(7,"keyboard"));true}
      else->false
     }
    }.focusable().clearAndSetSemantics {
     contentDescription=text("$name importance for this trip","Wichtigkeit für diese Reise: $name")
     stateDescription=if(value==null)text("Not rated. Choose a value","Noch nicht bewertet. Wert wählen")else "$value / 7 · ${tripImportanceAnchor(value,de)}"
     if(value!=null)progressBarRangeInfo=ProgressBarRangeInfo(value.toFloat(),1f..7f,5)
     setProgress {number->if(!number.isFinite())false else {onChange(state.choose(number.roundToInt().coerceIn(1,7),"accessibility"));true}}
     customActions=listOf(CustomAccessibilityAction(text("Increase","Erhöhen")){onChange(state.step(1,"accessibility"));true},CustomAccessibilityAction(text("Decrease","Verringern")){onChange(state.step(-1,"accessibility"));true})
    },contentAlignment=Alignment.Center) {
    Box(Modifier.fillMaxWidth().height(58.dp).background(Mint.copy(alpha=.035f))) {
     HorizontalDivider(color=Mint.copy(alpha=.45f));HorizontalDivider(Modifier.align(Alignment.BottomCenter),color=Mint.copy(alpha=.45f))
    }
    if(value!=null&&value>1)Text("${value-1}",Modifier.align(Alignment.TopCenter).padding(top=7.dp).graphicsLayer {alpha=.45f;rotationX=28f}.clearAndSetSemantics {},fontSize=21.sp,color=MutedText)
    Crossfade(value,animationSpec=tween(if(animated)100 else 0),label="Importance wheel") {rating->Text(rating?.toString()?:"—",fontSize=48.sp,fontWeight=FontWeight.SemiBold,color=if(rating==null)MutedText else Pale,modifier=Modifier.clearAndSetSemantics {})}
    if(value!=null&&value<7)Text("${value+1}",Modifier.align(Alignment.BottomCenter).padding(bottom=7.dp).graphicsLayer {alpha=.45f;rotationX=-28f}.clearAndSetSemantics {},fontSize=21.sp,color=MutedText)
   }
   FilledTonalIconButton({onChange(state.step(-1))},enabled=value!=1,modifier=Modifier.size(48.dp).semantics {contentDescription=text("Decrease importance for $name","Wichtigkeit verringern: $name")}){ImportanceChevron(false,Modifier.size(20.dp))}
  }
  Column(Modifier.width(64.dp),horizontalAlignment=Alignment.End){Text("7",style=MaterialTheme.typography.labelMedium);Text(text("Extremely\nimportant","Äußerst\nwichtig"),textAlign=TextAlign.End,style=MaterialTheme.typography.labelSmall,color=MutedText)}
 }
 Text(if(value==null)text("Choose a value","Wert wählen")else tripImportanceAnchor(value,de),Modifier.fillMaxWidth().padding(top=9.dp).semantics {liveRegion=LiveRegionMode.Polite;stateDescription=if(value==null)text("Not rated","Noch nicht bewertet")else "$value / 7"},textAlign=TextAlign.Center,style=MaterialTheme.typography.bodyLarge)
 Text(text("Swipe or use the arrows","Wischen oder Pfeile verwenden"),Modifier.fillMaxWidth().padding(top=4.dp),textAlign=TextAlign.Center,color=MutedText,style=MaterialTheme.typography.bodySmall)
}
