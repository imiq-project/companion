package de.ovgu.imiq.companion

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import org.json.JSONObject

// IMIQ Styleguide 16.03.26, pp.18–23. Keep this feature's palette local.
internal val NavigationPrimary=CompanionPalette.Primary
internal val NavigationBlue=CompanionPalette.Blue
internal val NavigationAccent=CompanionPalette.Burgundy
internal val NavigationOutline=CompanionPalette.Lavender
internal val NavigationBackground=CompanionPalette.Background
internal val NavigationWarning=CompanionPalette.Rose

enum class NavigationCameraMode { FOLLOW, OVERVIEW, FREE }
@Stable class NavigationMapState {
 var mode by mutableStateOf(NavigationCameraMode.FOLLOW);private set
 var command by mutableIntStateOf(0);private set
 var topInset by mutableStateOf(0.dp)
 var bottomInset by mutableStateOf(0.dp)
 var sideInset by mutableStateOf(0.dp)
 fun follow(){mode=NavigationCameraMode.FOLLOW;command++}
 fun overview(){mode=NavigationCameraMode.OVERVIEW;command++}
 fun pan(){mode=NavigationCameraMode.FREE}
}

/** Full-bleed map with safe, measured overlays; no GPS ownership or persistence here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun NavigationJourneyLayout(model:CompanionModel,record:JSONObject,card:RouteCard?,
 elapsed:String,distanceMeters:Double?,guidance:NavigationGuidance,pending:Boolean=false,
 canResume:Boolean=true,keepAwake:Boolean=true,isPlayback:Boolean=false,onPauseResume:()->Unit,onFinish:()->Unit,onCancel:()->Unit,
 onMinimize:()->Unit,map:@Composable (NavigationMapState)->Unit) {
 val view=LocalView.current
 DisposableEffect(view,keepAwake) {
  val previous=view.keepScreenOn;view.keepScreenOn=keepAwake
  val window=(view.context as? Activity)?.window
  val controller=window?.let {WindowCompat.getInsetsController(it,view)}
  val status=controller?.isAppearanceLightStatusBars;val navigation=controller?.isAppearanceLightNavigationBars
  controller?.isAppearanceLightStatusBars=true;controller?.isAppearanceLightNavigationBars=true
  onDispose {view.keepScreenOn=previous;if(controller!=null){if(status!=null)controller.isAppearanceLightStatusBars=status;if(navigation!=null)controller.isAppearanceLightNavigationBars=navigation}}
 }
 val viewport=remember(record.optString("search_id")){NavigationMapState()}
 var details by remember {mutableStateOf(false)}
 var finish by remember {mutableStateOf(false)}
 var cancel by remember {mutableStateOf(false)}
 BackHandler {details=true}
 val de=model.de
 fun text(en:String,german:String)=model.text(en,german)
 val destination=record.optJSONObject("places")?.optJSONObject("destination")?.optString("label").orEmpty().ifBlank {text("Your destination","Dein Ziel")}
 val paused=guidance.signal==NavigationSignal.PAUSED
 val playback=isPlayback
 val signal=when(guidance.signal){
  NavigationSignal.LIVE->text("GPS live","GPS aktiv")
  NavigationSignal.WAITING->text("Waiting for GPS","Warte auf GPS")
  NavigationSignal.STALE->text("GPS signal lost","GPS-Signal verloren")
  NavigationSignal.UNCERTAIN->text("Location uncertain","Standort ungenau")
  NavigationSignal.PAUSED->if(playback&&!canResume)text("Playback complete","Wiedergabe beendet")else if(playback)text("Playback paused","Wiedergabe pausiert")else text("Paused · GPS off","Pausiert · GPS aus")
  NavigationSignal.PLAYBACK->text("Playback · No GPS","Wiedergabe · Ohne GPS")
 }
 val message=when {
  guidance.offRoute->text("You are away from the planned route. Your position stays at the GPS location.","Du bist abseits der geplanten Route. Dein Standort bleibt an der GPS-Position.")
  playback&&paused&&!canResume->text("Playback complete. Finish to review your experience.","Wiedergabe beendet. Beende die Reise, um deine Erfahrung zu bewerten.")
  paused->text("Resume when you are ready.","Setze die Reise fort, wenn du bereit bist.")
  playback->text("Planned route playback, without GPS recording.","Wiedergabe der geplanten Route, ohne GPS-Aufzeichnung.")
  guidance.signal==NavigationSignal.STALE->text("Last position shown. Waiting for a fresh GPS signal.","Letzter Standort angezeigt. Warte auf ein neues GPS-Signal.")
  guidance.signal==NavigationSignal.UNCERTAIN->text("Position accuracy is low. Use the map with care.","Standortgenauigkeit ist gering. Nutze die Karte mit Bedacht.")
  guidance.signal==NavigationSignal.WAITING->text("Your route is ready. Waiting for your position.","Deine Route ist bereit. Warte auf deinen Standort.")
  else->text("Follow the highlighted route.","Folge der hervorgehobenen Route.")
 }
 MaterialTheme(colorScheme=companionColorScheme) {
  BoxWithConstraints(Modifier.fillMaxSize()) {
   val wide=maxWidth>maxHeight&&maxWidth>=600.dp
   val panelWidth=if(wide)340.dp else maxWidth
   val density=LocalDensity.current
   val safeTop=with(density){WindowInsets.safeDrawing.getTop(this).toDp()}
   val safeBottom=with(density){WindowInsets.safeDrawing.getBottom(this).toDp()}
   var topHeight by remember {mutableStateOf(0.dp)}
   var bottomHeight by remember {mutableStateOf(0.dp)}
   SideEffect {viewport.topInset=if(wide)safeTop else topHeight;viewport.bottomInset=if(wide)safeBottom else bottomHeight;viewport.sideInset=if(wide)panelWidth else 0.dp}
   map(viewport)
   Column(Modifier.align(Alignment.TopStart).width(panelWidth).onSizeChanged {topHeight=with(density){it.height.toDp()}}
    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top+WindowInsetsSides.Horizontal))
    .padding(horizontal=16.dp,vertical=12.dp)
    ,verticalArrangement=Arrangement.spacedBy(10.dp)) {
    if(!wide)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
     NavigationChip(if(card!=null)modeName(card.mode,de)else text("Journey","Reise"),Modifier.weight(1f))
     NavigationChip(signal,Modifier.weight(1.5f),if(guidance.offRoute)NavigationAccent else NavigationPrimary)
    }
    Surface(shape=RoundedCornerShape(24.dp),color=if(guidance.offRoute)NavigationWarning else Color.White,
     border=BorderStroke(1.dp,if(guidance.offRoute)NavigationAccent else NavigationOutline),shadowElevation=6.dp) {
     Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
      Text(if(guidance.offRoute)text("Away from planned route","Abseits der geplanten Route")else destination,
       color=if(guidance.offRoute)NavigationAccent else NavigationPrimary,fontWeight=FontWeight.SemiBold,fontSize=19.sp,lineHeight=23.sp,maxLines=if(wide)1 else 2,overflow=TextOverflow.Ellipsis)
      Text(if(wide)signal else message,color=Color.Black,style=MaterialTheme.typography.bodyMedium,maxLines=if(wide)2 else 3,overflow=TextOverflow.Ellipsis)
     }
    }
   }
   Column(Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
    .padding(end=16.dp,bottom=if(wide)24.dp else bottomHeight+32.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
    NavigationCameraButton(text("Show whole route","Gesamte Route zeigen"),"overview",viewport.mode==NavigationCameraMode.OVERVIEW,viewport::overview)
    NavigationCameraButton(text("Follow my position","Meinem Standort folgen"),"locate",viewport.mode==NavigationCameraMode.FOLLOW,viewport::follow)
   }
   Surface(Modifier.align(Alignment.BottomStart).width(panelWidth).onSizeChanged {bottomHeight=with(density){it.height.toDp()}}
    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom+WindowInsetsSides.Horizontal))
    .padding(horizontal=16.dp,vertical=12.dp),
    shape=RoundedCornerShape(24.dp),color=Color.White,border=BorderStroke(1.dp,NavigationOutline),shadowElevation=8.dp) {
    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
     Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
      NavigationMetric(if(playback)text("GPS recording off","GPS-Aufzeichnung aus")else text("Recorded","Aufgezeichnet"),
       distanceMeters?.takeIf {it.isFinite()&&it>=0}?.let {if(it<1000)"${metric(it,0)} m"else "${metric(it/1000,1)} km"}?:"—",Modifier.weight(1f),NavigationPrimary)
      NavigationMetric(text("Elapsed","Verstrichen"),elapsed,Modifier.weight(1f))
      FilledTonalIconButton({details=true},Modifier.size(48.dp).semantics {contentDescription=text("Journey details and options","Reisedetails und Optionen")},colors=IconButtonDefaults.filledTonalIconButtonColors(containerColor=NavigationBackground,contentColor=NavigationPrimary)){NavigationGlyph("more")}
     }
     card?.route?.optJSONObject("summary")?.let {summary->
      val metres=summary.number("distance_meters");val duration=summary.number("duration_seconds")
      Text(text("Planned: ","Geplant: ")+(metres?.let {"${metric(it/1000,1)} km"}?:"—")+" · "+(duration?.let {"${metric(it/60,0)} min"}?:"—"),style=MaterialTheme.typography.bodySmall,color=Color.Black)
     }
     Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
      OutlinedButton(onPauseResume,Modifier.weight(1f).heightIn(min=48.dp),enabled=!pending&&(!paused||canResume),shape=RoundedCornerShape(15.dp),border=BorderStroke(1.dp,NavigationPrimary)) {
       NavigationGlyph(if(paused)"play"else"pause",Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(if(paused)text("Resume","Fortsetzen")else text("Pause","Pause"))
      }
      Button({finish=true},Modifier.weight(1f).heightIn(min=48.dp),enabled=!pending,shape=RoundedCornerShape(15.dp)) {
       if(pending)CircularProgressIndicator(Modifier.size(20.dp),color=Color.White,strokeWidth=2.dp)else Text(text("Finish","Beenden"))
      }
     }
    }
   }
  }
  if(details)ModalBottomSheet(onDismissRequest={details=false},containerColor=Color.White) {
   Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
    Text(text("Your selected route","Deine gewählte Route"),style=MaterialTheme.typography.titleLarge,color=NavigationPrimary)
    Text(record.optJSONObject("places")?.optJSONObject("origin")?.optString("label").orEmpty()+" → "+destination,color=Color.Black)
    card?.route?.objects("legs")?.forEach {leg->
     val transit=leg.optJSONObject("transit")
     Text(if(leg.optString("mode")=="pt")listOf(transit?.optString("line"),transit?.optJSONObject("from")?.optString("name"),transit?.optJSONObject("to")?.optString("name")).filterNotNull().filter {it.isNotBlank()}.joinToString(" · ")else modeName(modelMode(leg.optString("mode")),de),color=Color.Black)
    }
    Text(if(playback)text("This is planned route playback. It does not record GPS.","Dies ist eine Wiedergabe der geplanten Route. Sie zeichnet kein GPS auf.")else text("Your actual GPS position and recorded trace remain separate from the planned route. Finishing opens your experience review; it does not automatically upload GPS.","Dein tatsächlicher GPS-Standort und die aufgezeichnete Spur bleiben von der geplanten Route getrennt. Beenden öffnet deine Erfahrungsbewertung; GPS wird nicht automatisch hochgeladen."),style=MaterialTheme.typography.bodyMedium,color=Color.Black)
    if(card?.let {originAccessGap(it)!=null||destinationAccessGap(it)!=null}==true)Text(text("The mapped route has an unmeasured access section. Planned time may not cover the complete journey.","Die kartierte Route enthält einen nicht vermessenen Zugangsabschnitt. Die geplante Zeit deckt möglicherweise nicht die gesamte Reise ab."),color=NavigationAccent)
    if(mapRoutes(record.optJSONObject("result")).find {it.id==card?.id}?.let {it.supplemental||it.segments.any {s->s.approximate||!s.drawable}}==true)Text(text("Dashed sections are approximate. Missing geometry is not replaced by an invented path.","Gestrichelte Abschnitte sind ungefähr. Fehlende Geometrie wird nicht durch einen erfundenen Weg ersetzt."),color=NavigationAccent)
    OutlinedButton({details=false;onMinimize()},Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text(if(playback)text("Return home · playback continues","Zur Startseite · Wiedergabe läuft weiter")else if(paused)text("Return home · journey stays paused","Zur Startseite · Reise bleibt pausiert")else text("Return home · recording continues","Zur Startseite · Aufzeichnung läuft weiter"))}
    TextButton({details=false;cancel=true},Modifier.fillMaxWidth().heightIn(min=48.dp),colors=ButtonDefaults.textButtonColors(contentColor=NavigationAccent),enabled=!pending) {Text(text("Cancel journey…","Reise abbrechen…"))}
   }
  }
  if(finish)AlertDialog(onDismissRequest={finish=false},title={Text(text("Finish journey?","Reise beenden?"))},text={Text(text("Next, review your journey and confirm your experience.","Prüfe als Nächstes deine Reise und bestätige deine Erfahrung."))},confirmButton={TextButton({finish=false;onFinish()},enabled=!pending){Text(text("Continue to review","Weiter zur Bewertung"))}},dismissButton={TextButton({finish=false}){Text(text("Keep travelling","Weiterreisen"))}})
  if(cancel)AlertDialog(onDismissRequest={cancel=false},title={Text(text("Cancel this journey?","Diese Reise abbrechen?"))},text={Text(text("Recorded points stay in history. This journey will not count as completed.","Aufgezeichnete Punkte bleiben im Verlauf. Diese Reise zählt nicht als abgeschlossen."))},confirmButton={TextButton({cancel=false;onCancel()},enabled=!pending){Text(text("Cancel journey","Reise abbrechen"),color=NavigationAccent)}},dismissButton={TextButton({cancel=false}){Text(text("Keep journey","Reise behalten"))}})
 }
}

@Composable private fun NavigationChip(label:String,modifier:Modifier=Modifier,dot:Color?=null) {
 Surface(modifier,shape=CircleShape,color=Color.White,border=BorderStroke(1.dp,NavigationOutline),shadowElevation=2.dp) {
  Row(Modifier.padding(horizontal=12.dp,vertical=9.dp),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
   if(dot!=null)Canvas(Modifier.size(7.dp)){drawCircle(dot)}
   Text(label,color=Color.Black,style=MaterialTheme.typography.labelMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
  }
 }
}
@Composable private fun NavigationMetric(label:String,value:String,modifier:Modifier=Modifier,color:Color=Color.Black) {
 Column(modifier,verticalArrangement=Arrangement.spacedBy(2.dp)) {
  Text(label,style=MaterialTheme.typography.bodySmall,color=Color.Black.copy(alpha=.7f),maxLines=2)
  Text(value,color=color,fontSize=if(value.length>5)21.sp else 25.sp,lineHeight=29.sp,fontWeight=FontWeight.Bold,maxLines=1)
 }
}
@Composable private fun NavigationCameraButton(label:String,icon:String,selected:Boolean,onClick:()->Unit) {
 Surface(shape=RoundedCornerShape(17.dp),shadowElevation=4.dp,color=if(selected)NavigationPrimary else Color.White) {
  IconButton(onClick,Modifier.size(48.dp).semantics {contentDescription=label},colors=IconButtonDefaults.iconButtonColors(contentColor=if(selected)Color.White else NavigationPrimary)){NavigationGlyph(icon)}
 }
}
@Composable internal fun NavigationGlyph(icon:String,modifier:Modifier=Modifier.size(22.dp)) {
 val color=LocalContentColor.current
 Canvas(modifier) {
  val w=size.width;val h=size.height;val stroke=2.dp.toPx()
  when(icon) {
   "pause"->{drawLine(color,Offset(w*.33f,h*.18f),Offset(w*.33f,h*.82f),stroke,StrokeCap.Round);drawLine(color,Offset(w*.67f,h*.18f),Offset(w*.67f,h*.82f),stroke,StrokeCap.Round)}
   "play"->drawPath(Path().apply {moveTo(w*.27f,h*.15f);lineTo(w*.82f,h*.5f);lineTo(w*.27f,h*.85f);close()},color)
   "more"->listOf(.2f,.5f,.8f).forEach {drawCircle(color,stroke/1.6f,Offset(w*it,h*.5f))}
   "overview"->{drawPath(Path().apply {moveTo(w*.1f,h*.2f);lineTo(w*.37f,h*.1f);lineTo(w*.63f,h*.2f);lineTo(w*.9f,h*.1f);lineTo(w*.9f,h*.8f);lineTo(w*.63f,h*.9f);lineTo(w*.37f,h*.8f);lineTo(w*.1f,h*.9f);close()},color,style=Stroke(stroke));drawLine(color,Offset(w*.37f,h*.1f),Offset(w*.37f,h*.8f),stroke);drawLine(color,Offset(w*.63f,h*.2f),Offset(w*.63f,h*.9f),stroke)}
   else->{drawCircle(color,w*.3f,Offset(w*.5f,h*.5f),style=Stroke(stroke));drawCircle(color,w*.07f,Offset(w*.5f,h*.5f));listOf(Offset(w*.5f,0f) to Offset(w*.5f,h*.17f),Offset(w*.5f,h*.83f) to Offset(w*.5f,h),Offset(0f,h*.5f) to Offset(w*.17f,h*.5f),Offset(w*.83f,h*.5f) to Offset(w,h*.5f)).forEach {(a,b)->drawLine(color,a,b,stroke)}}
  }
 }
}
