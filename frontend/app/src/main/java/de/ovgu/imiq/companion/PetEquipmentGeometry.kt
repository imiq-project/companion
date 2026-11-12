package de.ovgu.imiq.companion

import androidx.compose.ui.geometry.Rect

/** Equipment and hit targets share the same local geometry; motion is a small inset tolerance. */
internal fun petEquipmentRect(pet:String,width:Float,height:Float):Rect {
 val side=minOf(width,height)*.54f
 val x=when(pet){"dogs"->.15f;"spirit"->.42f;else->.29f}*width
 val y=when(pet){"dogs"->.42f;"spirit"->.48f;else->.46f}*height
 return Rect(x,y,x+side,y+side)
}
internal fun petEquipmentSlots(rect:Rect,pet:String="drone"):List<Rect> {
 val centres=when(pet){
  "dogs"->listOf(.49f to .43f,.32f to .74f,.66f to .75f)
  "spider"->listOf(.50f to .50f,.34f to .72f,.64f to .72f)
  "spirit"->listOf(.45f to .47f,.28f to .72f,.60f to .75f)
  else->listOf(.56f to .435f,.42f to .66f,.69f to .65f)
 }
 val side=rect.width*.24f
 return centres.map {(x,y)->val cx=rect.left+x*rect.width;val cy=rect.top+y*rect.height;Rect(cx-side/2,cy-side/2,cx+side/2,cy+side/2)}
}
