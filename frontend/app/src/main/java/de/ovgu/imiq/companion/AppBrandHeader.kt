package de.ovgu.imiq.companion

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Supplied OVGU logo, unchanged; shared across app, login and loading. */
@Composable internal fun AppBrandHeader(model:CompanionModel) {
 Row(Modifier.fillMaxWidth().background(Ink)
  .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top+WindowInsetsSides.Horizontal))
  .heightIn(min=56.dp).padding(horizontal=16.dp),
  horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
  Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
   Text("COMPANION",Modifier.weight(1f,false),style=MaterialTheme.typography.titleMedium,color=Gold,maxLines=1,overflow=TextOverflow.Ellipsis)
   Image(painterResource(R.drawable.ovgu_logo),model.text("Otto von Guericke University Magdeburg","Otto-von-Guericke-Universität Magdeburg"),Modifier.size(104.dp,44.dp))
  }
  TextButton({model.setLanguage(if(model.de)"en"else"de")},Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp),contentPadding=PaddingValues(4.dp)){
   Text(if(model.de)"EN"else"DE")
  }
 }
}
