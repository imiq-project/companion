package de.ovgu.imiq.companion

/** Presentation only: the companion follows the measured position, including
 * deviations. Route geometry never changes or constrains the user's location.
 * The unused route argument keeps older offline audit probes callable.
 */
@Suppress("UNUSED_PARAMETER")
internal fun routeAvatarTarget(route:MapRoute?,fix:GeoPoint?,recording:Boolean):GeoPoint? {
 return fix?.takeIf {recording&&it.lat.isFinite()&&it.lon.isFinite()&&it.lat in -90.0..90.0&&it.lon in -180.0..180.0}
}
