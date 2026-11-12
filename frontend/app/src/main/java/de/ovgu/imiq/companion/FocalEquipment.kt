package de.ovgu.imiq.companion

import org.json.JSONObject

// Read-only validation of historical records. No selection or confirmation flow.
internal const val FOCAL_METHOD="focal-initial-selection-v1"
internal fun goalForNeed(need:String)=goalKeys.getOrNull(needKeys.indexOf(need))
internal fun needForGoal(goal:String)=needKeys[goalKeys.indexOf(goal)]
internal fun validFocalGoals(value:JSONObject,profile:JSONObject):Boolean = runCatching {
 require(value.contractString("schema_version")=="focal-goals-v1"&&value.getString("method_version")==FOCAL_METHOD)
 val keys=listOf(value.getString("primary"))+(0 until value.getJSONArray("secondary").length()).map {value.getJSONArray("secondary").getString(it)}
 require(keys.size in 1..3&&keys.all {it in needKeys}&&keys.distinct().size==keys.size)
 val ratings=value.getJSONObject("ratings");require(ratings.length()==keys.size&&keys.all {ratings.opt(it) is Int&&ratings.getInt(it) in 1..7})
 val reference=value.getJSONObject("reference_needs");val needs=profile.getJSONObject("needs")
 require(reference.length()==needKeys.size&&needKeys.all {reference.number(it)?.let {n->n.isFinite()&&n in 0.0..1.0&&n==needs.number(it)}==true})
 require(keys.all {k->kotlin.math.abs(reference.getDouble(k)-(ratings.getInt(k)-1)/6.0)<1e-8})
 value.optJSONObject("importance_overrides")?.let {overrides->require(overrides.keys().asSequence().all {k->k in needKeys&&overrides.opt(k) is Int&&overrides.getInt(k) in 1..7&&kotlin.math.abs(reference.getDouble(k)-(overrides.getInt(k)-1)/6.0)<1e-8})}
 java.time.Instant.parse(value.getString("confirmed_at"));require(value.getString("event_id").isNotBlank()&&value.getString("original_passport_id").isNotBlank())
 true
}.getOrDefault(false)
