package de.ovgu.imiq.companion

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale

/** Shared rounded, native line language: same optical size for all five goals. */
@Composable internal fun GoalLineIcon(key:String,modifier:Modifier=Modifier) {
 Canvas(modifier) {
  val stroke=Stroke(1.8f,cap=StrokeCap.Round,join=StrokeJoin.Round)
  fun path(block:Path.()->Unit)=drawPath(Path().apply(block),Mint,style=stroke)
  fun line(x:Float,y:Float,a:Float,b:Float)=drawLine(Mint,Offset(x,y),Offset(a,b),1.8f,StrokeCap.Round)
  scale(minOf(size.width,size.height)/24f,pivot=Offset.Zero) {
   when(key) {
    "env"-> {
     path {moveTo(20f,4f);cubicTo(10f,3f,3f,7f,4f,14f);cubicTo(4.7f,19f,10f,21f,14f,18f);cubicTo(18f,15f,19f,9f,20f,4f);close()}
     line(4f,21f,15f,10f);line(9f,15f,9f,11f);line(9f,15f,14f,15f)
    }
    "health_activity"-> {
     drawCircle(Mint,2f,Offset(14f,4f),style=stroke)
     path {moveTo(12f,8f);lineTo(9f,13f);lineTo(13f,15f);lineTo(11f,21f)}
     path {moveTo(12f,8f);lineTo(16f,13f);lineTo(20f,13f)}
     line(9f,13f,5f,13f);line(13f,15f,18f,21f)
    }
    "cost"-> {
     path {moveTo(4f,6f);lineTo(19f,6f);lineTo(19f,20f);lineTo(4f,20f);quadraticTo(2f,20f,2f,18f);lineTo(2f,6f);quadraticTo(2f,4f,4f,4f);lineTo(17f,4f);lineTo(17f,6f)}
     path {moveTo(19f,11f);lineTo(14f,11f);lineTo(14f,16f);lineTo(19f,16f)}
     drawCircle(Mint,.8f,Offset(16f,13.5f))
    }
    "time"-> {
     drawCircle(Mint,9f,Offset(12f,12f),style=stroke)
     path {moveTo(12f,7f);lineTo(12f,12f);lineTo(15f,14f)}
    }
    "comfort_physical"-> {
     path {moveTo(6f,3f);lineTo(6f,14f);lineTo(19f,14f)}
     path {moveTo(6f,10f);lineTo(4f,10f);lineTo(4f,17f);lineTo(20f,17f);lineTo(20f,10f);lineTo(17f,10f)}
     path {moveTo(8f,6f);lineTo(16f,6f);lineTo(16f,14f)}
     line(6f,17f,6f,21f);line(18f,17f,18f,21f)
    }
   }
  }
 }
}
