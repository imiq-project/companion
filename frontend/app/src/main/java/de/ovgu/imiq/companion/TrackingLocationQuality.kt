package de.ovgu.imiq.companion

/** Provisional provider arbitration, independent of the chosen route/mode.
 * A coarse network echo should not replace a recent, precise GPS observation.
 * It becomes eligible again when GPS is unavailable; missing signal is real.
 */
internal class TrackingLocationQuality {
 private var gpsAt:Long?=null
 private var gpsAccuracy=Double.POSITIVE_INFINITY
 fun observeValidGps(accuracy:Double,at:Long){
  if(accuracy.isFinite()&&accuracy in 0.0..15.0&&at>=0&&(gpsAt==null||at>gpsAt!!)){gpsAt=at;gpsAccuracy=accuracy}
 }
 fun suppressCoarseEcho(provider:String,accuracy:Double,at:Long):Boolean {
  val previous=gpsAt?:return false
  return provider=="network"&&at>=previous&&at-previous<=15000&&accuracy>gpsAccuracy*1.5
 }
}
