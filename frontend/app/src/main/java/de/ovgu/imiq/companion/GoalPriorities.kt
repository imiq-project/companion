package de.ovgu.imiq.companion

import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

internal const val GOAL_PRIORITIES_VERSION="goal-priorities-5-v1"
internal fun declaredGoalRatings(item:JSONObject):JSONObject {
 val p=item.getJSONObject("response").getJSONObject("cognitive_passport")
 val current=p.optJSONObject("weekly_goals")?.optJSONObject("ratings")
 val raw=current?:p.optJSONObject("observed_measurements")?.optJSONObject("needs_raw_1_to_7")
 val values=raw?.copyJson()?:JSONObject()
 // Only explicitly declared historical adjustments can augment the original answers.
 if(current==null)p.optJSONObject("focal_goals")?.let {old->
  listOf("ratings","importance_overrides").forEach {name->old.optJSONObject(name)?.let {ratings->ratings.keys().forEach {values.put(it,ratings.get(it))}}}
 }
 require(values.keys().asSequence().toSet()==needKeys.toSet()&&needKeys.all {values.opt(it) is Int&&values.getInt(it) in 1..7}) {"Review all five importance ratings in Goal Personalization. Allocation points cannot be converted into importance ratings."}
 return values
}
internal fun weeklyGoalReference(item:JSONObject):JSONObject {
 val p=item.getJSONObject("response").getJSONObject("cognitive_passport")
 val saved=p.optJSONObject("weekly_goals")
 val zone=ZoneId.systemDefault().id
 if(saved?.optString("week_start")==week()&&saved.optString("timezone")==zone)return saved.copyJson()
 return obj("week_start" to week(),"timezone" to zone,"confirmed_at" to null,"source" to "inherited_explicit_ratings",
  "inherited_from" to (p.optJSONObject("goal_priorities")?.optString("weekly_reference_id")?:p.getJSONObject("lineage").getString("passport_id")),"ratings" to declaredGoalRatings(item))
}
internal fun explicitWeeklyReference(values:JSONObject)=obj("week_start" to week(),"timezone" to ZoneId.systemDefault().id,
 "confirmed_at" to now(),"source" to "explicit_weekly_confirmation","inherited_from" to null,"ratings" to values.copyJson())
internal fun priorityRequest(item:JSONObject,weekly:JSONObject,overrides:JSONObject,at:String?)=obj("schema_version" to GOAL_PRIORITIES_VERSION,
 "previous_passport" to item.getJSONObject("response").getJSONObject("cognitive_passport"),"weekly_goals" to weekly,
 "journey_overrides" to overrides,"journey_confirmed_at" to at)
internal fun effectiveGoalRatings(weekly:JSONObject,overrides:JSONObject):JSONObject {
 val result=weekly.getJSONObject("ratings").copyJson()
 require(result.keys().asSequence().toSet()==needKeys.toSet()&&needKeys.all {result.opt(it) is Int&&result.getInt(it) in 1..7})
 overrides.keys().forEach {key->require(key in needKeys&&overrides.opt(key) is Int&&overrides.getInt(key) in 1..7);result.put(key,overrides.getInt(key))}
 return result
}
internal fun validatedPriorityPassport(previous:JSONObject,weekly:JSONObject,overrides:JSONObject,at:String?,response:JSONObject):JSONObject {
 val p=response.getJSONObject("cognitive_passport");val ref=p.getJSONObject("goal_priorities")
 require(validGoalPriorities(p))
 require(ref.contractString("schema_version")==GOAL_PRIORITIES_VERSION&&!p.has("focal_goals")&&!p.has("daily_goals"))
 require(canonical(ref.getJSONObject("weekly_reference"))==canonical(weekly.copyJson()))
 require(canonical(ref.getJSONObject("journey_overrides"))==canonical(overrides.copyJson())&&canonical(ref.opt("journey_confirmed_at"))==canonical(at))
 val effective=effectiveGoalRatings(weekly,overrides)
 require(canonical(ref.getJSONObject("effective_ratings"))==canonical(effective))
 needKeys.forEach {require(kotlin.math.abs(p.getJSONObject("profile").getJSONObject("needs").getDouble(it)-(effective.getInt(it)-1)/6.0)<1e-10)}
 val old=previous.getJSONObject("response").getJSONObject("cognitive_passport")
 listOf("beliefs","valences","availability").forEach {require(canonical(old.getJSONObject("profile").opt(it))==canonical(p.getJSONObject("profile").opt(it))) {"Goal personalization changed $it"}}
 require(canonical(old.opt("observed_measurements"))==canonical(p.opt("observed_measurements")))
 return previous.copyJson().put("response",response).put("goalRatings",obj(*goalKeys.mapIndexed {i,key->key to effective.getInt(needKeys[i])}.toTypedArray())).put("needsSource",NEED_IMPORTANCE_SCALE)
  .put("conversion","rating_minus1_div6_v1").put("fingerprint",canonical(ref)).put("weekStart",weekly.getString("week_start"))
}

internal fun validGoalPriorities(passport:JSONObject):Boolean=runCatching {
 val ref=passport.getJSONObject("goal_priorities")
 require(ref.contractString("schema_version")==GOAL_PRIORITIES_VERSION)
 val weekly=ref.getJSONObject("weekly_reference")
 require(canonical(weekly)==canonical(passport.getJSONObject("weekly_goals")))
 val start=LocalDate.parse(weekly.getString("week_start"));require(start.dayOfWeek==java.time.DayOfWeek.MONDAY)
 val zone=ZoneId.of(weekly.getString("timezone"))
 if(weekly.getString("source")=="explicit_weekly_confirmation"){
  val at=java.time.Instant.parse(weekly.getString("confirmed_at")).atZone(zone).toLocalDate()
  require(!at.isBefore(start)&&at.isBefore(start.plusDays(7))&&weekly.isNull("inherited_from"))
 }else require(weekly.getString("source")=="inherited_explicit_ratings"&&weekly.isNull("confirmed_at")&&weekly.getString("inherited_from").isNotBlank())
 val edits=ref.getJSONObject("journey_overrides")
 val effective=effectiveGoalRatings(weekly,edits)
 require(canonical(effective)==canonical(ref.getJSONObject("effective_ratings")))
 if(edits.length()==0)require(ref.isNull("journey_confirmed_at"))else{
  val at=java.time.Instant.parse(ref.getString("journey_confirmed_at")).atZone(zone).toLocalDate()
  require(!at.isBefore(start)&&at.isBefore(start.plusDays(7)))
 }
 needKeys.forEach {key->
  val expected=(effective.getInt(key)-1)/6.0
  require(kotlin.math.abs(passport.getJSONObject("profile").getJSONObject("needs").getDouble(key)-expected)<1e-10)
  require(kotlin.math.abs(ref.getJSONObject("normalized").getDouble(key)-expected)<1e-10)
 }
 true
}.getOrDefault(false)
