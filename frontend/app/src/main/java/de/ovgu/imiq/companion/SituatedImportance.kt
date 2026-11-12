package de.ovgu.imiq.companion

import org.json.JSONArray
import org.json.JSONObject

internal const val SITUATED_IMPORTANCE_VERSION="pretrip-situated-importance-v1"
internal const val SITUATED_SCALE_VERSION="importance-seven-anchors-v1"

/** Volatile answers for one search. Missing is never a neutral or inherited rating. */
internal data class TripImportanceDraft(
 val values:List<Int?> = List(5){null},
 val confirmed:List<Int?> = List(5){null},
 val methods:List<String?> = List(5){null},
 val selected:Int=0,
 val reviewing:Boolean=false,
) {
 init {
  require(values.size==5&&confirmed.size==5&&methods.size==5&&selected in 0..4)
  require((values+confirmed).all {it==null||it in 1..7})
  require(values.indices.all {values[it]==null||methods[it] in listOf("wheel","arrows","direct","keyboard","accessibility")})
  require(!reviewing||complete)
 }
 val ratedCount get()=confirmed.indices.count {confirmed[it]!=null&&confirmed[it]==values[it]}
 val complete get()=ratedCount==5
 fun select(index:Int)=copy(selected=index,reviewing=false)
 fun choose(value:Int,method:String):TripImportanceDraft {
  require(value in 1..7&&method in listOf("wheel","arrows","direct","keyboard","accessibility"))
  return copy(values=values.mapIndexed {i,old->if(i==selected)value else old},
   methods=methods.mapIndexed {i,old->if(i==selected)method else old},reviewing=false)
 }
 fun step(delta:Int,method:String="arrows"):TripImportanceDraft {
  require(delta in listOf(-1,1))
  return choose(values[selected]?.let {(it+delta).coerceIn(1,7)}?:1,method)
 }
 fun setSelected():TripImportanceDraft {
  require(values[selected]!=null)
  val next=copy(confirmed=confirmed.mapIndexed {i,old->if(i==selected)values[i] else old})
  return if(next.complete)next.copy(reviewing=true)
   else next.copy(selected=next.confirmed.indices.first {next.confirmed[it]==null||next.confirmed[it]!=next.values[it]})
 }
 fun ratings():JSONObject {
  require(complete)
  return obj(*needKeys.mapIndexed {i,key->key to confirmed[i]!!}.toTypedArray())
 }
 fun observation(language:String,openedAt:String,confirmedAt:String):JSONObject {
  require(complete&&language in listOf("en","de"))
  require(!java.time.Instant.parse(confirmedAt).isBefore(java.time.Instant.parse(openedAt)))
  return obj("schema_version" to SITUATED_IMPORTANCE_VERSION,
   "question_version" to "importance-this-trip-v1","scale_version" to SITUATED_SCALE_VERSION,
   "construct" to "situated_importance","language" to language,"opened_at" to openedAt,
   "confirmed_at" to confirmedAt,"presentation_order" to JSONArray(needKeys),
   "ratings" to ratings(),"entry_methods" to obj(*needKeys.mapIndexed {i,key->key to methods[i]!!}.toTypedArray()),
   "source" to "explicit_fresh_trip_responses","weekly_values_displayed" to false)
 }
}

internal fun tripImportanceName(index:Int,de:Boolean)=if(de)
 listOf("Umwelt","Körperliche Aktivität","Geld sparen","Zeit sparen","Körperlicher Komfort")[index]
 else listOf("Environment","Physical activity","Saving money","Saving time","Physical comfort")[index]
internal fun tripImportanceAnchor(value:Int,de:Boolean):String {
 require(value in 1..7)
 return if(de)listOf("Überhaupt nicht wichtig","Kaum wichtig","Etwas wichtig","Mäßig wichtig","Wichtig","Sehr wichtig","Äußerst wichtig")[value-1]
 else listOf("Not at all important","Slightly important","Somewhat important","Moderately important","Important","Very important","Extremely important")[value-1]
}

internal data class PendingTripImportance(val id:String,val contextKey:String,val openedAt:String,val language:String)
internal data class ConfirmedTripImportance(val contextKey:String,val ratings:JSONObject,val observation:JSONObject)

/** New searches must retain raw declarations consistent with the frozen HOTCO inputs.
 * Historical searches without this instrument are not retroactively reclassified. */
internal fun validTripImportanceRecord(record:JSONObject):Boolean=runCatching {
 if(!record.has("situated_importance")&&!record.optBoolean("situated_importance_required"))return@runCatching true
 val evidence=record.getJSONObject("situated_importance")
 require(evidence.contractString("schema_version")==SITUATED_IMPORTANCE_VERSION)
 require(evidence.getString("question_version")=="importance-this-trip-v1"&&evidence.getString("scale_version")==SITUATED_SCALE_VERSION)
 require(evidence.getString("construct")=="situated_importance"&&evidence.getString("source")=="explicit_fresh_trip_responses")
 require(evidence.opt("weekly_values_displayed")==false&&evidence.getString("language") in listOf("en","de"))
 require(!evidence.has("salience"))
 val opened=java.time.Instant.parse(evidence.getString("opened_at"))
 val confirmedAt=java.time.Instant.parse(evidence.getString("confirmed_at"))
 require(!confirmedAt.isBefore(opened)&&!java.time.Instant.parse(record.getString("created_at")).isBefore(confirmedAt))
 val order=evidence.getJSONArray("presentation_order")
 require(order.length()==5&&(0..4).map {order.getString(it)}==needKeys)
 val ratings=evidence.getJSONObject("ratings");val methods=evidence.getJSONObject("entry_methods")
 require(ratings.keys().asSequence().toSet()==needKeys.toSet()&&methods.keys().asSequence().toSet()==needKeys.toSet())
 val passport=record.getJSONObject("request").getJSONObject("cognitive_passport")
 val priorities=passport.getJSONObject("goal_priorities")
 require(canonical(priorities.getJSONObject("effective_ratings"))==canonical(ratings))
 require(canonical(priorities.getJSONObject("journey_overrides"))==canonical(ratings))
 require(priorities.getString("journey_confirmed_at")==evidence.getString("confirmed_at"))
 needKeys.forEach {key->
  require(ratings.opt(key) is Int&&ratings.getInt(key) in 1..7)
  require(methods.getString(key) in listOf("wheel","arrows","direct","keyboard","accessibility"))
  require(kotlin.math.abs(passport.getJSONObject("profile").getJSONObject("needs").getDouble(key)-(ratings.getInt(key)-1)/6.0)<1e-10)
 }
 true
}.getOrDefault(false)
