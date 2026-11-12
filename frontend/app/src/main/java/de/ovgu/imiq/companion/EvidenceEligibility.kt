package de.ovgu.imiq.companion

import org.json.JSONObject

internal const val EXCLUSION_SCHEMA="longitudinal-external-evaluation-exclusions-v1"
internal const val EXCLUSION_KEY="longitudinal.evidence-exclusions.v1"
internal const val LEGACY_EXCLUSION_KEY="d-day.evidence-exclusions.v1"

internal fun Store.evidenceExclusions():JSONObject? {
 val current=get(EXCLUSION_KEY);val previous=get(LEGACY_EXCLUSION_KEY)
 require(current==null||previous==null||current==previous) {"Conflicting longitudinal exclusion registries"}
 return (current?:previous)?.let(::JSONObject).also {validateEvidenceExclusions(it)}
}

internal fun validateEvidenceExclusions(registry:JSONObject?) {
 if(registry==null)return
 require(registry.optContractString("schema_version")==EXCLUSION_SCHEMA)
 val rows=registry.getJSONArray("exclusions")
 require(rows.objects().size==rows.length())
 rows.objects().forEach {row->
  require(listOf("journey_id","experience_event_id").any {row.opt(it) is String&&row.getString(it).isNotEmpty()})
  require(row.opt("reason") is String&&row.getString("reason").isNotEmpty())
  val targets=row.getJSONArray("targets")
  require(targets.length()>0&&(0 until targets.length()).all {targets.opt(it) in listOf("comfort","affect")})
 }
}

/** Same ordering and both-ID semantics as comfort_pilot.eligibility.exclusion_reason. */
internal fun evidenceExclusionReason(record:JSONObject,journey:JSONObject,target:String,registry:JSONObject?):String? {
 require(target in listOf("comfort","affect"));validateEvidenceExclusions(registry)
 val e=journey.optJSONObject("experience")?:return "experience_not_confirmed"
 if(e.length()==0)return "experience_not_confirmed"
 val classification=if(journey.has("evidence_classification"))journey.opt("evidence_classification")else record.opt("evidence_classification")
 if(classification!=null&&classification!=JSONObject.NULL){
  require(classification in listOf("real_experience","test","synthetic","stationary_test"))
  if(classification!="real_experience")return "explicit_$classification"
 }
 if(journey.optString("status")=="cancelled")return "trip_cancelled"
 if(e.optString("completion")=="not_taken")return "trip_not_taken"
 registry?.objects("exclusions")?.forEach {row->
  val targets=row.getJSONArray("targets")
  if((0 until targets.length()).any {targets.getString(it)==target}&&
   (!row.has("journey_id")||row.opt("journey_id")==journey.opt("journey_id"))&&
   (!row.has("experience_event_id")||row.opt("experience_event_id")==e.opt("event_id")))return row.getString("reason")
 }
 return null
}

internal fun evaluationEligibilitySnapshot(registry:JSONObject?):JSONObject {
 validateEvidenceExclusions(registry)
 return obj("schema_version" to "longitudinal-evaluation-eligibility-v1","exclusions" to registry?.copyJson(),
  "exclusions_digest" to learnedDigest(registry))
}

internal fun exclusionsFromEvaluation(evaluation:JSONObject):JSONObject? {
 val snapshot=evaluation.getJSONObject("eligibility_snapshot")
 require(snapshot.contractString("schema_version")=="longitudinal-evaluation-eligibility-v1")
 val registry=snapshot.optJSONObject("exclusions")
 require(snapshot.getString("exclusions_digest")==learnedDigest(registry)) {"Frozen evaluation exclusions changed"}
 validateEvidenceExclusions(registry)
 return registry
}
