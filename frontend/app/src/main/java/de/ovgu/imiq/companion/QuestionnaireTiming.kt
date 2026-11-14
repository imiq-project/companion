package de.ovgu.imiq.companion

import org.json.JSONObject
import java.time.Duration
import java.time.Instant

internal const val QUESTIONNAIRE_ATTEMPT = "companion.questionnaire-attempt.v1"
internal data class QuestionnaireClock(val at:String,val elapsedMs:Long,val boot:Int?)

/** Local aggregate timing only. No per-question events, network calls or learning. */
internal object QuestionnaireTiming {
 fun begin(id:String,clock:QuestionnaireClock,observedStart:Boolean):JSONObject = obj(
  "version" to 1,"questionnaire_attempt_id" to id,
  "questionnaire_started_at" to if(observedStart)clock.at else null,
  "started_elapsed_ms" to if(observedStart)clock.elapsedMs else null,
  "boot_count" to if(observedStart)clock.boot else null,
  "questionnaire_completed_at" to null,"questionnaire_elapsed_seconds" to null,
  "questionnaire_timing_status" to "unavailable",
  "profile_response_received_at" to null,"profile_confirmed_at" to null)

 /** Same frozen submission is idempotent even after an HTTP failure/reopen. */
 fun complete(state:JSONObject,signature:String,clock:QuestionnaireClock):JSONObject {
  if(state.optString("submission_signature")==signature&&!state.isNull("questionnaire_completed_at"))return state.copyJson()
  val next=state.copyJson()
  val elapsed=if(!next.isNull("started_elapsed_ms"))clock.elapsedMs-next.getLong("started_elapsed_ms") else -1L
  val wall=runCatching {Duration.between(Instant.parse(next.getString("questionnaire_started_at")),Instant.parse(clock.at)).toMillis()}.getOrNull()
  val measured=clock.boot!=null&&!next.isNull("boot_count")&&clock.boot==next.getInt("boot_count")&&
   elapsed>=0&&wall!=null&&wall>=0&&kotlin.math.abs(wall.toDouble()-elapsed.toDouble())<=5000.0
  return next.put("submission_signature",signature).put("questionnaire_completed_at",clock.at)
   .put("questionnaire_elapsed_seconds",if(measured)elapsed/1000.0 else JSONObject.NULL)
   .put("questionnaire_timing_status",if(measured)"measured"else"unavailable")
   .put("profile_response_received_at",JSONObject.NULL).put("profile_confirmed_at",JSONObject.NULL)
 }

 fun received(state:JSONObject,at:String):JSONObject {
  // Legacy completed calibration may have no observed questionnaire timestamps.
  return state.copyJson().put("profile_response_received_at",at).put("profile_confirmed_at",JSONObject.NULL)
 }

 fun confirmed(state:JSONObject,at:String):JSONObject {
  require(!state.isNull("profile_response_received_at")) {"Create the Passport first"}
  return state.copyJson().put("profile_confirmed_at",at)
 }

 /** Internal monotonic anchors and submission signatures never enter a profile. */
 fun snapshot(state:JSONObject):JSONObject = obj(*listOf("questionnaire_started_at",
  "questionnaire_completed_at","questionnaire_elapsed_seconds","questionnaire_timing_status",
  "profile_response_received_at","profile_confirmed_at").map {it to state.opt(it)}.toTypedArray())

 /** Device-local monotonic anchors cannot be resumed from a portable backup. */
 fun portableStorage(values:JSONObject):JSONObject {
  val result=values.copyJson();result.remove(QUESTIONNAIRE_ATTEMPT)
    result.keys().asSequence().filter {it.startsWith(PROFILE_UPLOAD_PREFIX)||it.startsWith(PRIORITY_UPLOAD_PREFIX)||it.startsWith(SEARCH_UPLOAD_PREFIX)||it.startsWith(RESULTS_UPLOAD_PREFIX)||it.startsWith(HOTCO_UPLOAD_PREFIX)}.toList().forEach(result::remove)
  val key="companion.passport-renewal.v1"
  val renewal=runCatching {JSONObject(result.getString(key))}.getOrNull()
  renewal?.optJSONObject("answers")?.let {answers->
   answers.remove(QUESTIONNAIRE_ATTEMPT);result.put(key,renewal.toString())
  }
  return result
 }
}
