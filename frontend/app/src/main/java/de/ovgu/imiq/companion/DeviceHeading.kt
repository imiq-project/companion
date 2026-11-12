package de.ovgu.imiq.companion

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import org.json.JSONObject
import kotlin.math.roundToInt

internal data class PhoneHeading(val magneticDegrees:Double,val accuracy:Int,val elapsedMs:Long)

/** Screen-only sensor stream. Never writes tracking, GPS, trip or learning data. */
internal fun phoneHeadings(context:Context,rotation:()->Int)=callbackFlow<PhoneHeading?> {
 val manager=context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
 if(manager==null){close();return@callbackFlow}
 val vector=manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
  ?:manager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
 val accelerometer=if(vector==null)manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)else null
 val magnetometer=if(vector==null)manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)else null
 val sensors=if(vector!=null)listOf(vector)else listOfNotNull(accelerometer,magnetometer)
 if(vector==null&&(accelerometer==null||magnetometer==null)){close();return@callbackFlow}
 val gravity=FloatArray(3);val magnetic=FloatArray(3);val matrix=FloatArray(9)
 var gravityAt:Long?=null;var magneticAt:Long?=null
 var accuracy=SensorManager.SENSOR_STATUS_UNRELIABLE
 var frame:Int?=null
 val smoother=HeadingSmoother()
 fun unknown(){smoother.reset();trySend(null)}
 val listener=object:SensorEventListener {
  override fun onAccuracyChanged(sensor:Sensor?,value:Int) {
   if(sensor?.type==vector?.type||sensor?.type==Sensor.TYPE_MAGNETIC_FIELD){accuracy=value;if(value<=SensorManager.SENSOR_STATUS_UNRELIABLE)unknown()}
  }
  override fun onSensorChanged(event:SensorEvent) {
   if(event.values.any {!it.isFinite()}){unknown();return}
   val stamp=event.timestamp/1_000_000
   if(vector!=null) {
    accuracy=event.accuracy
    if(event.values.size<3){unknown();return}
    SensorManager.getRotationMatrixFromVector(matrix,event.values)
   }else {
    if(event.values.size<3){unknown();return}
    when(event.sensor.type) {
     Sensor.TYPE_ACCELEROMETER->{for(i in 0..2)gravity[i]=if(gravityAt==null)event.values[i]else gravity[i]*.8f+event.values[i]*.2f;gravityAt=stamp}
     Sensor.TYPE_MAGNETIC_FIELD->{for(i in 0..2)magnetic[i]=event.values[i];magneticAt=stamp;accuracy=event.accuracy}
    }
    val a=gravityAt;val b=magneticAt
    if(a==null||b==null||stamp-a !in 0..500||stamp-b !in 0..500)return
    if(!SensorManager.getRotationMatrix(matrix,null,gravity,magnetic)){unknown();return}
   }
   if(accuracy<=SensorManager.SENSOR_STATUS_UNRELIABLE){unknown();return}
   val screen=rotation()
   if(frame!=screen){frame=screen;smoother.reset()}
   val bearing=headingFromRotationMatrix(matrix,screen)?:run {unknown();return}
   val filtered=smoother.update(bearing,stamp)?:return
   trySend(PhoneHeading(filtered,accuracy,stamp))
  }
 }
 val handler=Handler(Looper.getMainLooper())
 val registered=runCatching {sensors.all {manager.registerListener(listener,it,SensorManager.SENSOR_DELAY_UI,handler)}}.getOrDefault(false)
 if(!registered){manager.unregisterListener(listener);close();return@callbackFlow}
 awaitClose {manager.unregisterListener(listener)}
}.conflate()

@Composable private fun rememberPhoneHeading():State<PhoneHeading?> {
 val context=LocalContext.current.applicationContext
 val view=LocalView.current
 val lifecycle=LocalLifecycleOwner.current.lifecycle
 return produceState<PhoneHeading?>(null,context,view,lifecycle) {
  lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
   value=null
   try {phoneHeadings(context){view.display?.rotation?:0}.collect {value=it}}
   finally {value=null}
  }
 }
}

/** Fan shows phone orientation, independently of GPS course and route geometry. */
@Composable internal fun DeviceDirectionOverlay(fix:JSONObject,center:Offset,mapBearing:Double,de:Boolean) {
 val reading by rememberPhoneHeading()
 val lifecycle=LocalLifecycleOwner.current.lifecycle
 var now by remember {mutableLongStateOf(SystemClock.elapsedRealtime())}
 LaunchedEffect(lifecycle) {lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED){while(true){now=SystemClock.elapsedRealtime();delay(500)}}}
 val sample=reading?:return
 val current=maxOf(now,SystemClock.elapsedRealtime())
 if(!freshHeading(fix.number("monotonic_ms")?.toLong(),sample.elapsedMs,current))return
 val lat=fix.number("lat")?:return;val lon=fix.number("lon")?:return
 val day=System.currentTimeMillis()/86_400_000
 val declination=remember(lat,lon,day){runCatching {GeomagneticField(lat.toFloat(),lon.toFloat(),0f,System.currentTimeMillis()).declination.toDouble()}.getOrNull()}?:return
 val angle=screenHeading(sample.magneticDegrees,declination,mapBearing)?.toFloat()?:return
 val geographic=normalizedHeading(sample.magneticDegrees+declination).roundToInt()%360
 val description=if(de)"Geräterichtung · ungefähr $geographic° von Norden"else"Device direction · about $geographic° from north"
 Canvas(Modifier.fillMaxSize().semantics {contentDescription=description}) {
  if(!center.x.isFinite()||!center.y.isFinite())return@Canvas
  val radius=56.dp.toPx()
  val halfAngle=if(sample.accuracy==SensorManager.SENSOR_STATUS_ACCURACY_LOW)42f else 26f
  val blue=Color(0xFF188AE8)
  drawArc(Brush.radialGradient(listOf(blue.copy(alpha=.40f),blue.copy(alpha=.04f),Color.Transparent),center,radius),
   angle-90-halfAngle,halfAngle*2,true,topLeft=center-Offset(radius,radius),size=Size(radius*2,radius*2))
  rotate(angle,center) {
   val tip=Path().apply {moveTo(center.x,center.y-19.dp.toPx());lineTo(center.x+7.dp.toPx(),center.y-7.dp.toPx());lineTo(center.x,center.y-10.dp.toPx());lineTo(center.x-7.dp.toPx(),center.y-7.dp.toPx());close()}
   drawPath(tip,Color.White)
   val arrow=Path().apply {moveTo(center.x,center.y-17.dp.toPx());lineTo(center.x+5.dp.toPx(),center.y-8.dp.toPx());lineTo(center.x,center.y-11.dp.toPx());lineTo(center.x-5.dp.toPx(),center.y-8.dp.toPx());close()}
   drawPath(arrow,blue)
  }
 }
}
