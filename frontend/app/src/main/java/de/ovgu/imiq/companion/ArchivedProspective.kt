package de.ovgu.imiq.companion

import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.time.Instant

internal const val ARCHIVED_FORECAST_REQUEST="longitudinal-prospective-request-v2"
internal const val ARCHIVED_FORECAST_RESULT="longitudinal-prospective-forecast-v2"
internal const val PRECHOICE_FORECAST="prechoice-archived-v2"

/** One consistent selection before routing. Reading history is unnecessary. */
internal fun freezeProspectiveSource(store:Store,active:JSONObject):JSONObject=store.consistentRead {
 val original=originalPassport(store,active).copyJson()
 val root=original.getJSONObject("lineage").getString("passport_id")
 val review=store.get("$LEARNED.review.$root")
 val selected=review?:store.get("$LEARNED.tip.$root")
 val version=selected?.takeUnless {it==root}?.let {
  val v=store.json("$LEARNED.version.$it")?:error("Selected learned archive unavailable")
  validateLearnedVersion(v)
  require(v.getString("original_passport_id")==root&&v.getString("original_passport_digest")==learnedDigest(original))
  v
 }
 obj("schema_version" to "longitudinal-prospective-source-v2","selected_at" to now(),
  "initial_passport" to original,"learned_version" to version,
  "evidence_exclusions" to store.evidenceExclusions(),
  "selection_rule" to if(review!=null)"explicit_shadow_review"else"latest_computed_shadow_only")
}

/** Forecast from stored draws, then persist the returned record before publishing routes.
 * Failure is terminal for this search: routes remain usable, no prediction is fabricated.
 */
internal suspend fun forecastBeforeChoice(record:JSONObject,source:JSONObject?,
 call:suspend (JSONObject)->JSONObject,clock:()->String=::now) {
 record.put("prospective_pipeline",PRECHOICE_FORECAST)
 val frozen=obj("status" to "pending");record.put("d_day_forecast",frozen)
 try {
  require(source!=null){"Frozen learned/original source unavailable"}
  val candidates=record.getJSONObject("result").optJSONArray("d_day_prediction_contexts")
   ?:error("Backend does not provide compact prospective contexts")
  require(candidates.length()>0){"No available route for a prospective prediction"}
  val request=obj("schema_version" to ARCHIVED_FORECAST_REQUEST,"search_id" to record.getString("search_id"),
   "as_of" to record.getString("completed_at"),"source" to source,"candidates" to candidates)
  frozen.put("as_of",request.getString("as_of")).put("request",request)
  val response=call(request)
  validateArchivedForecast(request,response)
  val received=clock()
  require(!Instant.parse(received).isBefore(Instant.parse(request.getString("as_of")))) {"Forecast receipt precedes its cutoff"}
  frozen.put("status","complete").put("recorded_at",received).put("result",response)
 }catch(e:CancellationException){throw e}catch(e:Exception){
  frozen.put("status","failed").put("failed_at",clock()).put("failure",e.message?.take(300)?:"Prospective forecast unavailable")
 }
}

internal fun validateArchivedForecast(request:JSONObject,result:JSONObject) {
 require(request.contractString("schema_version")==ARCHIVED_FORECAST_REQUEST&&result.contractString("schema_version")==ARCHIVED_FORECAST_RESULT)
 require(result.getString("request_digest")==learnedDigest(request)) {"Archived forecast does not match frozen request"}
 require(result.getString("source_digest")==learnedDigest(request.getJSONObject("source")))
 require(result.getString("search_id")==request.getString("search_id")&&result.getString("as_of")==request.getString("as_of"))
 require(!result.getBoolean("learning_applied_to_active_passport")&&!result.getBoolean("hotco_modified")&&!result.getBoolean("stored_on_server"))
 val source=request.getJSONObject("source")
 require(result.getString("source_selected_at")==source.getString("selected_at"))
 val version=source.optJSONObject("learned_version")
 if(version!=null){
  validateLearnedVersion(version)
  val original=source.getJSONObject("initial_passport")
  require(version.getString("original_passport_id")==original.getJSONObject("lineage").getString("passport_id")&&version.getString("original_passport_digest")==learnedDigest(original))
 }
 require(result.optString("source_passport_id").takeUnless {it.isEmpty()||it=="null"}==version?.getString("passport_id"))
 require(result.optString("evidence_cutoff").takeUnless {it.isEmpty()||it=="null"}==version?.getString("evidence_cutoff"))
 require(result.getString("forecast_method")=="archived_posterior_no_refit_v2")
 require(!Instant.parse(source.getString("selected_at")).isAfter(Instant.parse(request.getString("as_of"))))
 if(version!=null)require(Instant.parse(version.getString("evidence_cutoff")).isBefore(Instant.parse(source.getString("selected_at"))))
 val expected=request.objects("candidates").map {it.getString("prediction_id") to it.getString("mode")}
 require(expected.size==expected.distinct().size)
 for(target in listOf("comfort","affect")){
  val rows=result.getJSONObject("targets").getJSONObject(target).objects("predictions")
  require(rows.map {it.getString("prediction_id") to it.getString("mode")}==expected)
  rows.forEach {p->
   if(p.has("admission_shadow"))validateAdmissionShadow(p.getJSONObject("admission_shadow"),target,p.getString("mode"),request,result)
   for(name in listOf("static","basic","contextual")){
    val m=p.getJSONObject("models").getJSONObject(name)
    if(m.getBoolean("prediction_usable_for_research")){
     val probabilities=m.getJSONArray("category_probabilities")
     val values=(0 until probabilities.length()).map {probabilities.getDouble(it)}
     require(values.size==7&&values.all {it.isFinite()&&it>=0}&&kotlin.math.abs(values.sum()-1)<1e-6)
    }
   }
  }
 }
}

/** Additional receipt is optional for older V2 servers; when present it is immutable and shadow-only. */
internal fun validateAdmissionShadow(receipt:JSONObject,target:String,mode:String,request:JSONObject,result:JSONObject) {
 require(receipt.contractString("schema_version")=="longitudinal-admission-shadow-v1")
 val body=receipt.copyJson().apply {remove("receipt_digest")}
 require(receipt.getString("receipt_digest")==learnedDigest(body)) {"Admission receipt was modified"}
 val policy=receipt.getJSONObject("policy")
 require(receipt.getString("policy_digest")==learnedDigest(policy))
 require(policy.contractString("schema_version")=="longitudinal-admission-shadow-v1"&& !policy.getBoolean("method_qualified")&& !policy.getBoolean("production_activation_allowed"))
 require(receipt.getString("target")==target&&receipt.getString("mode")==mode&&receipt.getString("as_of")==request.getString("as_of"))
 require(receipt.getString("source_digest")==result.getString("source_digest"))
 require(receipt.getString("operational_source")=="original"&&receipt.getString("operational_reason")=="method_not_qualified_shadow_only")
 require(listOf("active_passport_modified","hotco_modified","activation_applied").all {!receipt.getBoolean(it)})
 val profile=request.getJSONObject("source").getJSONObject("initial_passport").getJSONObject("profile")
 val initial=if(target=="affect")profile.getJSONObject("valences").getDouble(mode)else profile.getJSONObject("beliefs").getJSONObject(mode).getDouble("comfort")
 require(receipt.getDouble("operational_value")==initial)
}

internal fun requireProspectiveReadyForChoice(record:JSONObject,chosenAt:String) {
 if(record.optString("prospective_pipeline")!=PRECHOICE_FORECAST)return
 val f=record.getJSONObject("d_day_forecast")
 require(f.getString("status") in listOf("complete","failed")) {"Prospective preparation is not yet saved"}
 val finished=f.getString(if(f.getString("status")=="complete")"recorded_at"else"failed_at")
 require(Instant.parse(finished).isBefore(Instant.parse(chosenAt))) {"Choice must follow saved prospective preparation"}
}
