package de.ovgu.imiq.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A proposal is display-only until its original search context is confirmed. */
internal data class PlanningPointProposal(val start:Boolean,val point:GeoPoint,val origin:Place?,val destination:Place?,val context:String)
internal data class PlanningAddressLabel(val proposal:PlanningPointProposal,val language:String,val address:String)
internal fun planningAddressFor(label:PlanningAddressLabel?,proposal:PlanningPointProposal?,language:String)=
 label?.takeIf {it.proposal==proposal&&it.language==language}?.address
internal fun currentPlanningProposal(proposal:PlanningPointProposal,start:Boolean,origin:Place?,destination:Place?,context:String,busy:Boolean)=
 !busy&&proposal.start==start&&proposal.origin==origin&&proposal.destination==destination&&proposal.context==context

@Composable internal fun PlanningLocationIcon(blocked:Boolean) {
 Canvas(Modifier.size(24.dp)) {
  val c=center;val r=7.dp.toPx();val stroke=1.7.dp.toPx()
  drawCircle(Color.White,r,c,style=Stroke(stroke));drawCircle(Color.White,2.dp.toPx(),c)
  for(sign in listOf(-1,1)) {
   drawLine(Color.White,Offset(c.x+sign*9.dp.toPx(),c.y),Offset(c.x+sign*11.dp.toPx(),c.y),stroke,StrokeCap.Round)
   drawLine(Color.White,Offset(c.x,c.y+sign*9.dp.toPx()),Offset(c.x,c.y+sign*11.dp.toPx()),stroke,StrokeCap.Round)
  }
  if(blocked)drawLine(Color.White,Offset(3.dp.toPx(),21.dp.toPx()),Offset(21.dp.toPx(),3.dp.toPx()),2.5.dp.toPx(),StrokeCap.Round)
 }
}

@Composable internal fun PlanningPointSheet(model:CompanionModel,proposal:PlanningPointProposal,address:String?,onHeight:(Dp)->Unit,onConfirm:()->Unit,onCancel:()->Unit,modifier:Modifier=Modifier) {
 val density=LocalDensity.current
 Surface(modifier.fillMaxWidth().onSizeChanged {onHeight(with(density){it.height.toDp()})},color=Panel,
  shape=RoundedCornerShape(topStart=26.dp,topEnd=26.dp),shadowElevation=8.dp) {
  Column(Modifier.heightIn(max=320.dp).verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(bottom=18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
   Box(Modifier.fillMaxWidth().height(24.dp),contentAlignment=Alignment.Center){Box(Modifier.size(32.dp,4.dp).background(Edge,RoundedCornerShape(2.dp)))}
   Row(verticalAlignment=Alignment.CenterVertically) {
    Text(model.text(if(proposal.start)"Your origin"else"Your destination",if(proposal.start)"Dein Start"else"Dein Ziel"),Modifier.weight(1f),style=MaterialTheme.typography.labelLarge,color=MutedText)
    TextButton(onCancel,Modifier.heightIn(min=48.dp)){Text(model.text("Cancel","Abbrechen"))}
   }
   Text(address?.let {model.text("Near $it","Nahe $it")}?:model.text("Selected map point","Gewählter Kartenpunkt"),style=MaterialTheme.typography.titleLarge)
   Text(String.format(java.util.Locale.ROOT,"%.5f, %.5f",proposal.point.lat,proposal.point.lon),style=MaterialTheme.typography.bodySmall,color=MutedText)
   Button(onConfirm,Modifier.fillMaxWidth().heightIn(min=50.dp),enabled=!model.busy,shape=RoundedCornerShape(14.dp)) {
    Text(model.text(if(proposal.start)"Use as origin"else"Use as destination",if(proposal.start)"Als Start verwenden"else"Als Ziel verwenden"))
   }
  }
 }
}
