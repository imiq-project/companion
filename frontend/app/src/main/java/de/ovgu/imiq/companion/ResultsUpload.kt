package de.ovgu.imiq.companion

import org.json.JSONObject
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal const val RESULTS_UPLOAD_PREFIX="companion.results-upload.job."
internal const val RESULTS_UPLOAD_PATH="/api/companion/data/search-results"
internal const val RESULTS_UPLOAD_CAPABILITY="/api/companion/data/search-results-transfer/status"

/** The backend validates and projects 06A. Freeze that projection, never the complete result. */
internal object ResultsUpload {
 fun revision(id:String)="search-results-"+ProfileUpload.digest("search-results-v1|$id")
 fun pending(record:JSONObject,parent:JSONObject,identity:JSONObject,base:String):JSONObject {
  val id=record.getString("search_id");val owner=panelUploadBinding(identity)
  require(ProfileUpload.localBase(base)&&record.getString("status")=="complete")
  require(parent.getString("search_revision_id")==SearchUpload.revision(id)&&parent.getString("target_base")==base)
  require(canonical(parent.getJSONObject("upload_identity"))==canonical(owner))
  val metadata=JSONObject(parent.getString("body"));require(metadata.getString("status")=="complete")
  val response=record.getJSONObject("result");val transfer=response.getJSONObject("search_results_transfer")
  require(transfer.opt("available")==true)
  val body=transfer.getJSONObject("payload").copyJson()
  val allowed=setOf("schema_version","data_class","results_revision_id","search_id","search_metadata_reference","backend_generated_at","source_response_schema_version","source_response_status","journey_availability","availability_source","routing","route_audit","candidate_routes","route_context_observations","warnings","climate_method")
  require(body.keys().asSequence().all {it in allowed})
  require(body.getString("schema_version")=="companion-search-results-v1"&&body.getString("data_class")=="participant")
  require(body.getString("search_id")==id&&body.getString("results_revision_id")==revision(id))
  require(body.getJSONObject("search_metadata_reference").getString("search_revision_id")==SearchUpload.revision(id))
  require(canonical(body.getJSONObject("journey_availability").copyJson())==canonical(metadata.getJSONObject("request").getJSONObject("journey_availability").copyJson()))
  require(canonical(body.getJSONObject("routing").getJSONArray("routes"))==canonical(response.getJSONObject("routing").getJSONArray("routes").let {org.json.JSONArray(it.toString())}))
  Instant.parse(body.getString("backend_generated_at"));Instant.parse(record.getString("result_received_at"))
  body.put("app_version",record.getString("app_version")).put("response_received_at",record.getString("result_received_at"))
  val raw=body.toString();require(raw.toByteArray(Charsets.UTF_8).size<=32*1024*1024)
  return obj("schema_version" to "companion-results-upload-job-v1","results_revision_id" to revision(id),"search_id" to id,
   "created_at" to record.getString("created_at"),"target_base" to base,"upload_identity" to owner,"body" to raw,
   "payload_sha256" to ProfileUpload.digest(raw),"status" to "pending","attempts" to 0,"next_retry_ms" to 0L,"receipt" to null,"reason" to null)
 }
 fun verifyReceipt(job:JSONObject,receipt:JSONObject) {
  require(receipt.optString("schema_version")=="companion-search-results-storage-receipt-v1"&&receipt.optString("collection")=="search_results")
  require(receipt.opt("stored_on_server")==true&&receipt.opt("learning_applied")==false&&receipt.optString("data_class")=="participant")
  listOf("results_revision_id","search_id","payload_sha256").forEach {require(receipt.getString(it)==job.getString(it))}
  listOf("storage_record_id","search_metadata_storage_record_id").forEach {require(receipt.getString(it).matches(Regex("[0-9a-f]{64}")))}
  job.getJSONObject("upload_identity").let {owner->owner.keys().forEach {require(receipt.getString(it)==owner.getString(it))}}
  Instant.parse(receipt.getString("stored_at"))
 }
}

/** Called only within the terminal search transaction; never scans/imports historical searches. */
internal fun Store.queueResultsUpload(record:JSONObject,identity:JSONObject?,base:String) {
 require(writableDatabase.inTransaction())
 if(record.optString("status")!="complete")return
 val id=ResultsUpload.revision(record.getString("search_id"));val key=RESULTS_UPLOAD_PREFIX+id
 if(get(key)!=null)return
 val job=runCatching {
  require(identity!=null)
  val parent=json(SEARCH_UPLOAD_PREFIX+SearchUpload.revision(record.getString("search_id")))?:error("Search source missing")
  ResultsUpload.pending(record,parent,identity,base)
 }.getOrElse {obj("schema_version" to "companion-results-upload-job-v1","results_revision_id" to id,"search_id" to record.getString("search_id"),
  "created_at" to record.getString("created_at"),"status" to "blocked","reason" to "results_projection_or_identity_unavailable")}
 put(key,job)
}

internal fun Store.resultsUploadJobs():List<JSONObject> {
 // Only materialize one pending body. Stored histories can contain many megabytes each.
 val at=System.currentTimeMillis()
 val keys=readableDatabase.rawQuery("SELECT key FROM kv WHERE key LIKE ? AND json_extract(value,'$.status') IN ('pending','retry') ORDER BY CASE WHEN json_extract(value,'$.next_retry_ms')<=? OR json_extract(value,'$.next_retry_ms')>? THEN 0 ELSE 1 END,json_extract(value,'$.created_at') LIMIT 1",arrayOf("$RESULTS_UPLOAD_PREFIX%",at.toString(),(at+300000L).toString()))
  .use {cursor->buildList {while(cursor.moveToNext())add(cursor.getString(0))}}
 return keys.mapNotNull {json(it)}.sortedBy {it.optString("created_at")}
}

internal fun Store.resultsUploadHeader(id:String):JSONObject? = readableDatabase.rawQuery("SELECT json_object('status',json_extract(value,'$.status'),'payload_sha256',json_extract(value,'$.payload_sha256'),'upload_identity',json_extract(value,'$.upload_identity')) FROM kv WHERE key=?",arrayOf(RESULTS_UPLOAD_PREFIX+id))
 .use {if(it.moveToFirst())JSONObject(it.getString(0))else null}

internal fun Store.updateResultsUpload(job:JSONObject) {
 val db=writableDatabase;db.beginTransaction()
 try {
  val key=RESULTS_UPLOAD_PREFIX+job.getString("results_revision_id")
  val current=resultsUploadHeader(job.getString("results_revision_id"))
  if(current!=null&&current.optString("status")!="stored"&&current.optString("payload_sha256")==job.optString("payload_sha256")&&
   canonical(current.optJSONObject("upload_identity"))==canonical(job.optJSONObject("upload_identity")))put(key,job)
  db.setTransactionSuccessful()
 }finally{db.endTransaction()}
}

internal suspend fun processResultsUploads(store:Store,api:Api,panelRequest:suspend (JSONObject,String,String?)->JSONObject,changed:()->Unit) {
 try {
  var resumed=true
  while(true) {
   currentCoroutineContext().ensureActive()
   val jobs=withContext(Dispatchers.IO){store.resultsUploadJobs().filter {it.optString("status") in listOf("pending","retry")}}
   if(jobs.isEmpty())return
   val nowMs=System.currentTimeMillis()
   val job=jobs.firstOrNull {it.optLong("next_retry_ms")<=nowMs||it.optLong("next_retry_ms")-nowMs>300000L}?:jobs.firstOrNull().takeIf {resumed}
   if(job==null){delay(2000);continue}
   resumed=false
   var stage="prepare"
   val next=try {
    require(ProfileUpload.localBase(api.base())&&job.getString("target_base")==api.base())
    stage="request_capability"
    val capability=panelRequest(job,RESULTS_UPLOAD_CAPABILITY,null)
    stage="verify_capability"
    require(capability.optString("schema_version")=="companion-search-results-transfer-capability-v1"&&capability.optBoolean("enabled")&&
     capability.optString("mode")=="local-panel-account-test"&&capability.optString("results_path")==RESULTS_UPLOAD_PATH)
    job.getJSONObject("upload_identity").let {owner->listOf("participant_id","study_id","panel_origin").forEach {require(capability.optString(it)==owner.getString(it))}}
    require(withContext(Dispatchers.IO){store.resultsUploadHeader(job.getString("results_revision_id"))!=null})
    currentCoroutineContext().ensureActive()
    stage="request_results"
    val receipt=panelRequest(job,RESULTS_UPLOAD_PATH,job.getString("body"))
    stage="verify_receipt";ResultsUpload.verifyReceipt(job,receipt)
    job.copyJson().put("status","stored").put("receipt",receipt).put("reason",JSONObject.NULL)
   }catch(e:PanelUploadPaused){return}
    catch(e:CancellationException){throw e}
    catch(e:ApiHttpException){
     (if(e.status in listOf(409,413,415,422))job.copyJson().put("status","failed").put("reason","server_rejected_results")
      else ProfileUpload.retry(job,"backend_or_parent_unavailable",System.currentTimeMillis()))
      .put("failure_stage",stage).put("failure_http_status",e.status).put("failure_type","ApiHttpException")
    }catch(e:Exception){ProfileUpload.retry(job,"waiting_for_backend",System.currentTimeMillis())
     .put("failure_stage",stage).put("failure_http_status",JSONObject.NULL).put("failure_type",e.javaClass.simpleName)}
   withContext(Dispatchers.IO){store.updateResultsUpload(next)}
   changed()
  }
 }catch(e:CancellationException){throw e}catch(e:Exception){/* Durable jobs resume on reopening. */}
}
