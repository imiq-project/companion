package de.ovgu.imiq.companion

import org.json.JSONObject

internal data class ClimateComparison(val grams:Double,val partial:Boolean,val reference:JSONObject)

internal fun climateVisibleGrams(climate:JSONObject?):Double? {
 if(climate==null)return null
 val value=when(climate.optString("status")) {
  "estimated"->climate.number("g_co2e")
  "partial"->climate.number("known_portion_g_co2e")
  else->null
 }
 if(value==null||!value.isFinite()||value<0)return null
 climate.optJSONArray("segments")?.let {
  val known=climate.objects("segments").mapNotNull {s->s.number("g_co2e")}
  if(known.isEmpty()||known.any {g->!g.isFinite()||g<0}||kotlin.math.abs(value-known.sum())>.00001)return null
 }
 return value
}

/** A view-only difference between frozen routes of the SAME search and vehicle.
 * Partial portions remain partial; no total savings are written to journey evidence. */
internal fun routeCarComparison(route:JSONObject,routes:List<JSONObject>):ClimateComparison? {
 if(modelMode(route.optString("mode_key"))=="car")return null
 val selected=route.optJSONObject("climate_estimate")?:return null
 val amount=climateVisibleGrams(selected)?:return null
 if(selected.optString("search_id").isBlank())return null
 fun same(ref:JSONObject):Boolean=listOf("search_id","profile_revision","factor_version","factor_sha256","boundary","profile_snapshot").all {
  selected.has(it)&&ref.has(it)&&canonical(selected.opt(it))==canonical(ref.opt(it))
 }
 val saved=selected.optJSONObject("comparisons")?.optJSONObject("car")?.optJSONObject("reference")
 val candidates=listOfNotNull(saved)+routes.filter {modelMode(it.optString("mode_key"))=="car"&&it.optBoolean("available")}
  .mapNotNull {it.optJSONObject("climate_estimate")}
 val reference=candidates.filter {same(it)&&climateVisibleGrams(it)!=null}
  .sortedBy {it.optString("status")!="estimated"}.firstOrNull()?:return null
 return ClimateComparison(climateVisibleGrams(reference)!!-amount,
  selected.optString("status")!="estimated"||reference.optString("status")!="estimated",reference)
}
