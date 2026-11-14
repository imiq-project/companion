package de.ovgu.imiq.companion

import android.animation.ValueAnimator
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.cos
import kotlin.math.PI

/** Original IMIQ art, cached by painterResource; transforms never repaint its SVG. */
@Composable internal fun ImdilloIllustration(modifier:Modifier=Modifier,animated:Boolean=false,description:String?=null) {
 val lifecycle=LocalLifecycleOwner.current.lifecycle
 val systemMotion=ValueAnimator.areAnimatorsEnabled()
 val enabled=animated&&systemMotion
 var phase by remember {mutableFloatStateOf(0f)}
 LaunchedEffect(enabled,lifecycle){
  phase=0f
  if(enabled)lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){
   var started=0L
   while(true)withFrameNanos {now->if(started==0L)started=now;phase=((now-started)%5_500_000_000L)/5_500_000_000f}
  }
 }
 Image(painterResource(R.drawable.imdillo_wave),description,modifier.graphicsLayer {
  val breath=if(enabled)((1-cos(phase*2*PI))/2).toFloat()else 0f
  scaleX=1f+breath*.012f;scaleY=scaleX;translationY=-3.dp.toPx()*breath
  transformOrigin=TransformOrigin(.5f,.9f)
 })
}

@Composable internal fun ProfileWelcomeScreen(model:CompanionModel) {
 var paused by rememberSaveable {mutableStateOf(false)}
 val motion=model.settings().optString("motion")=="full"&&ValueAnimator.areAnimatorsEnabled()
 Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter){
  Column(Modifier.widthIn(max=390.dp).fillMaxSize()){
   Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=16.dp).padding(top=24.dp,bottom=24.dp)){
    Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally){
     ImdilloIllustration(Modifier.size(134.dp,182.dp),motion&&!paused,
      model.text("Imdillo, the IMIQ armadillo mascot, waving hello","Imdillo, das winkende IMIQ-Gürteltier"))
     if(motion)TextButton({paused=!paused},Modifier.heightIn(min=48.dp)){
      Text(if(paused)model.text("Resume animation","Animation fortsetzen")else model.text("Pause animation","Animation pausieren"),
       textDecoration=TextDecoration.Underline)
     }
    }
    Spacer(Modifier.height(32.dp))
    Text(model.text("Hi, I am Imdillo.","Hallo, ich bin Imdillo."),style=MaterialTheme.typography.displayMedium,color=Pale)
    Spacer(Modifier.height(12.dp))
    Text(model.text("I help you find transport modes and routes that match your mobility preferences.",
     "Ich helfe dir, Verkehrsmittel und Routen zu finden, die zu deinen Mobilitätspräferenzen passen."),
     style=MaterialTheme.typography.bodyLarge.copy(fontSize=(MaterialTheme.typography.bodyLarge.fontSize.value+2).sp),color=Pale)
    Spacer(Modifier.height(32.dp));HorizontalDivider(color=Edge);Spacer(Modifier.height(24.dp))
    Text(model.text("First, let us create your profile.","Erstellen wir zuerst dein Profil."),style=MaterialTheme.typography.titleLarge,color=Pale)
    Spacer(Modifier.height(8.dp))
    Text(model.text("Next, you will tell me what matters to you when you travel.","Als Nächstes erzählst du mir, was dir unterwegs wichtig ist."),style=MaterialTheme.typography.bodyLarge,color=Pale)
   }
   Box(Modifier.fillMaxWidth().padding(horizontal=16.dp).padding(top=16.dp,bottom=18.dp)){
    PixelButton(model.text("Create my mobility profile","Mein Mobilitätsprofil erstellen"),model::startMobilityProfile,
     Modifier.fillMaxWidth(),enabled=!model.busy,primary=true)
   }
  }
 }
}
