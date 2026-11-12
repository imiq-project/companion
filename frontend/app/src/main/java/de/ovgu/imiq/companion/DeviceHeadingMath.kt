package de.ovgu.imiq.companion

import kotlin.math.*

internal fun normalizedHeading(degrees:Double):Double=((degrees%360)+360)%360

/** Top of the displayed screen, projected onto the horizontal ENU plane. */
internal fun headingFromRotationMatrix(matrix:FloatArray,displayRotation:Int):Double? {
 if(matrix.size!=9||matrix.any {!it.isFinite()}||displayRotation !in 0..3)return null
 val (east,north)=when(displayRotation) {
  0->matrix[1].toDouble() to matrix[4].toDouble()
  1->-matrix[0].toDouble() to -matrix[3].toDouble()
  2->-matrix[1].toDouble() to -matrix[4].toDouble()
  else->matrix[0].toDouble() to matrix[3].toDouble()
 }
 // Looking along a nearly vertical edge has no stable horizontal direction.
 if(hypot(east,north)<.15)return null
 return normalizedHeading(Math.toDegrees(atan2(east,north)))
}

internal fun screenHeading(magnetic:Double,declination:Double,mapBearing:Double):Double? =
 if(listOf(magnetic,declination,mapBearing).all {it.isFinite()})normalizedHeading(magnetic+declination-mapBearing)else null

internal fun freshHeading(fixMs:Long?,headingMs:Long?,nowMs:Long):Boolean =
 fixMs!=null&&headingMs!=null&&fixMs>=0&&headingMs>=0&&nowMs>=fixMs&&nowMs>=headingMs&&
 nowMs-fixMs<=30_000&&nowMs-headingMs<=2_000

/** Circular smoothing follows the short arc across north and resets after gaps. */
internal class HeadingSmoother {
 private var previous:Double?=null
 private var at:Long?=null
 fun reset(){previous=null;at=null}
 fun update(degrees:Double,elapsedMs:Long):Double? {
  if(!degrees.isFinite()||elapsedMs<0)return null
  val old=previous;val stamp=at
  if(stamp!=null&&elapsedMs<=stamp)return null
  val value=if(old==null||stamp==null||elapsedMs-stamp>2_000)normalizedHeading(degrees)else {
   val delta=(normalizedHeading(degrees)-old+540)%360-180
   val alpha=1-exp(-(elapsedMs-stamp)/120.0)
   normalizedHeading(old+delta*alpha)
  }
  previous=value;at=elapsedMs;return value
 }
}
