package de.ovgu.imiq.companion

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.math.roundToInt

// Existing presentation names now resolve to the shared official IMIQ roles.
val Ink=CompanionPalette.Background
val Panel=CompanionPalette.Surface
val PanelRaised=CompanionPalette.Background
val MutedText=CompanionPalette.Muted
val Gold=CompanionPalette.Burgundy
val Mint=CompanionPalette.Primary
val Pale=CompanionPalette.Text
val Edge=CompanionPalette.Lavender
fun color(text:String)=runCatching { Color(android.graphics.Color.parseColor(text)) }.getOrDefault(Pale)
fun modeColor(mode:String)=when(modelMode(mode)){"car"->CompanionPalette.Text;"bike"->CompanionPalette.Primary;"pt"->CompanionPalette.Burgundy;else->CompanionPalette.Primary}

// Pale Blue is a map stroke with contrasting casing, never small text on white.
// Transit vehicle identities and explicit mode labels remain independent of hue.
fun mapSegmentColor(segment:MapSegment)=when {
 segment.mode=="bike"->CompanionPalette.Blue
 segment.mode=="pt"&&segment.transitVehicle in listOf("BUS","TROLLEYBUS")->CompanionPalette.Primary
 else->modeColor(segment.mode)
}

@Composable fun CompanionTheme(large:Boolean,content:@Composable ()->Unit) {
 MaterialTheme(colorScheme=companionColorScheme,typography=companionTypography(large),content=content)
}
@Composable fun PixelCard(modifier:Modifier=Modifier,highlight:Boolean=false,spacing:androidx.compose.ui.unit.Dp=12.dp,padding:androidx.compose.ui.unit.Dp=14.dp,content:@Composable ColumnScope.()->Unit) {
 val shape=RoundedCornerShape(18.dp)
 Column(modifier.fillMaxWidth().clip(shape).background(Panel).border(if(highlight)2.dp else 1.dp,if(highlight)Mint else Edge,shape).padding(padding),verticalArrangement=Arrangement.spacedBy(spacing),content=content)
}
@Composable fun PixelButton(text:String,onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,primary:Boolean=false) {
 if(primary)Button(onClick,modifier.heightIn(min=48.dp),enabled=enabled,shape=RoundedCornerShape(12.dp),contentPadding=PaddingValues(12.dp,10.dp)){Text(text)}
 else OutlinedButton(onClick,modifier.heightIn(min=48.dp),enabled=enabled,shape=RoundedCornerShape(12.dp),border=androidx.compose.foundation.BorderStroke(1.dp,MutedText.copy(alpha=.65f)),contentPadding=PaddingValues(12.dp,10.dp)){Text(text,color=if(enabled)Pale else MutedText)}
}
@Composable fun Heading(text:String){Text(text,style=MaterialTheme.typography.titleMedium,color=Mint)}
@Composable fun Copy(text:String,color:Color=Pale){Text(text,color=color)}
@Composable fun catalog():JSONObject {val context=LocalContext.current;return remember { context.assets.open("catalog.json").bufferedReader().use { JSONObject(it.readText()) } }}
@Composable fun modeName(mode:String,de:Boolean):String {val c=catalog();return c.getJSONObject("beliefs").getJSONObject(if(de)"de" else "en").getJSONObject("modes").optString(modelMode(mode),mode)}
@Composable fun needName(need:String,de:Boolean):String {val c=catalog();return c.getJSONObject("beliefs").getJSONObject(if(de)"de" else "en").getJSONObject("needs").optString(need,need)}
@Composable fun goalCopy(key:String,de:Boolean):JSONObject=catalog().getJSONObject("weekly").getJSONObject(if(de)"de"else"en").getJSONObject("goals").getJSONObject(key)
