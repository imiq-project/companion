package de.ovgu.imiq.companion

import org.json.JSONObject
import java.time.Instant
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal const val SEARCH_UPLOAD_PREFIX="companion.search-upload.job."
internal const val SEARCH_UPLOAD_PATH="/api/companion/data/search-metadata"
internal const val SEARCH_UPLOAD_CAPABILITY="/api/companion/data/search-transfer/status"

/** Explicit projection of a terminal search. Never serialize the full local record. */
internal object SearchUpload {
 private val requestKeys="search_id datetime start stop journey_availability max_walk_m tolerance_profile record_trajectories context_policy required_model_version required_readout_version contextual_query climate_profile car_parking boarding_stop_id boarding_trip_id boarding_departure_ms boarding_plan_datetime".split(" ")
 private val placeKeys="lat lon label id houseNumber street detail source accuracy_meters".split(" ")
 fun revision(id:String)="search-metadata-"+ProfileUpload.digest("search-metadata-v1|$id")
 fun termination(stage:String,error:Exception):JSONObject {
  val code=when {
   error is CancellationException->"cancelled"
   error is ApiHttpException->when(error.status){401,403->"authentication_required";429->"rate_limited";in 500..599->"backend_unavailable";else->"request_rejected"}
   error is IOException->"network_unavailable"
   stage=="response_validation"->"invalid_response"
   else->"unknown"
  }
  return obj("status" to if(error is CancellationException)"cancelled" else "failed",
   "failure" to obj("stage" to stage,"code" to code,"http_status" to (error as? ApiHttpException)?.status))
 }
 fun pending(record:JSONObject,priority:JSONObject,identity:JSONObject,base:String):JSONObject {
  require(ProfileUpload.localBase(base))
  val owner=panelUploadBinding(identity);val id=record.getString("search_id")
  require(canonical(priority.getJSONObject("upload_identity"))==canonical(owner)&&priority.getString("target_base")==base)
  require(priority.getString("priority_revision_id")==PriorityUpload.revision("predecision_ema",id))
  val parent=JSONObject(priority.getString("body"));val source=record.getJSONObject("request")
  require(parent.getString("kind")=="predecision_ema"&&parent.getString("search_id")==id)
  require(canonical(parent.getJSONObject("contextual_passport_snapshot").copyJson())==canonical(source.getJSONObject("cognitive_passport").copyJson()))
  require(canonical(parent.getJSONObject("journey_availability"))==canonical(source.getJSONObject("journey_availability")))
  require(parent.getString("created_at")==record.getString("created_at"))
  val terminal=record.getJSONObject("search_termination");val status=terminal.getString("status")
  require(status in listOf("complete","failed","cancelled"))
  require(record.getString("status")==if(status=="complete")"complete" else "failed")
  Instant.parse(record.getString("completed_at"))
  val request=JSONObject();requestKeys.filter(source::has).forEach {request.put(it,source.get(it))}
  source.optJSONObject("climate_profile")?.let(::validateVehicleProfile)
  val places=JSONObject();listOf("origin","destination").forEach {name->
   val place=record.getJSONObject("places").getJSONObject(name);val projected=JSONObject()
   placeKeys.forEach {projected.put(it,place.get(it))};places.put(name,projected)
  }
  val lineage=parent.getJSONObject("passport_reference")
  val body=obj("schema_version" to "companion-search-metadata-v1","data_class" to "participant",
   "search_revision_id" to revision(id),"search_id" to id,"source_record_version" to record.getInt("version"),
   "app_version" to record.getString("app_version"),"created_at" to record.getString("created_at"),
   "completed_at" to record.getString("completed_at"),"status" to status,"source_status" to record.getString("status"),
   "priority_reference" to obj("priority_revision_id" to parent.getString("priority_revision_id")),
   "passport_reference" to obj("passport_id" to lineage.getString("passport_id"),"revision" to lineage.getInt("revision")),
   "request" to request,"places" to places,"failure" to terminal.get("failure")).toString()
  require(body.toByteArray(Charsets.UTF_8).size<=512*1024)
  return obj("schema_version" to "companion-search-upload-job-v1","search_revision_id" to revision(id),"search_id" to id,
   "search_status" to status,"created_at" to record.getString("created_at"),"target_base" to base,"upload_identity" to owner,
   "body" to body,"payload_sha256" to ProfileUpload.digest(body),"status" to "pending","attempts" to 0,
   "next_retry_ms" to 0L,"receipt" to null,"reason" to null)
 }
 fun verifyReceipt(job:JSONObject,receipt:JSONObject) {
  require(receipt.optString("schema_version")=="companion-search-storage-receipt-v1")
  require(receipt.opt("stored_on_server")==true&&receipt.opt("learning_applied")==false&&receipt.optString("data_class")=="participant")
  listOf("search_revision_id","search_id","payload_sha256").forEach {require(receipt.getString(it)==job.getString(it))}
  require(receipt.getString("status")==job.getString("search_status"))
  listOf("storage_record_id","priority_storage_record_id").forEach {require(receipt.getString(it).matches(Regex("[0-9a-f]{64}")))}
  job.getJSONObject("upload_identity").let {owner->owner.keys().forEach {require(receipt.getString(it)==owner.getString(it))}}
  Instant.parse(receipt.getString("stored_at"))
 }
}

/** One final transition and its frozen outbox entry commit together. Late cancellation cannot downgrade it. */
internal fun Store.commitSearchTerminal(record:JSONObject,identity:JSONObject?,base:String):Boolean {
 val db=writableDatabase;db.beginTransaction()
 try {
  val canFinalize=db.rawQuery("SELECT json_type(value,'$.search_termination') FROM searches WHERE id=?",arrayOf(record.getString("search_id")))
   .use {it.moveToFirst()&&it.isNull(0)}
  if(!canFinalize){db.setTransactionSuccessful();return false}
  require(record.has("search_termination"))
  val id=SearchUpload.revision(record.getString("search_id"));val key=SEARCH_UPLOAD_PREFIX+id
  if(get(key)==null) {
   val job=runCatching {
    require(identity!=null)
    val parent=json(PRIORITY_UPLOAD_PREFIX+PriorityUpload.revision("predecision_ema",record.getString("search_id")))?:error("Priority source missing")
    SearchUpload.pending(record,parent,identity,base)
   }.getOrElse {obj("schema_version" to "companion-search-upload-job-v1","search_revision_id" to id,"search_id" to record.getString("search_id"),
    "created_at" to record.getString("created_at"),"status" to "blocked","reason" to "search_source_or_identity_unavailable")}
   put(key,job)
  }
  queueResultsUpload(record,identity,base)
  queueHotcoUpload(record,identity,base)
  record.optJSONObject("result")?.remove("search_results_transfer")
  record.optJSONObject("result")?.remove("hotco_transfer")
  saveRecord(record)
  db.setTransactionSuccessful();return true
 }finally{db.endTransaction()}
}

internal fun Store.searchUploadJobs():List<JSONObject> {
 val keys=readableDatabase.rawQuery("SELECT key FROM kv WHERE key LIKE ? ORDER BY key",arrayOf("$SEARCH_UPLOAD_PREFIX%"))
  .use {cursor->buildList {while(cursor.moveToNext())add(cursor.getString(0))}}
 return keys.mapNotNull {json(it)}.sortedBy {it.optString("created_at")}
}

internal fun Store.updateSearchUpload(job:JSONObject) {
 val db=writableDatabase;db.beginTransaction()
 try {
  val key=SEARCH_UPLOAD_PREFIX+job.getString("search_revision_id")
  val current=json(key)
  if(current!=null&&current.optString("status")!="stored"&&current.optString("payload_sha256")==job.optString("payload_sha256")&&
   canonical(current.optJSONObject("upload_identity"))==canonical(job.optJSONObject("upload_identity")))put(key,job)
  db.setTransactionSuccessful()
 }finally{db.endTransaction()}
}

internal suspend fun processSearchUploads(store:Store,api:Api,panelRequest:suspend (JSONObject,String,String?)->JSONObject,changed:()->Unit) {
 try {
  var resumed=true
  while(true) {
   currentCoroutineContext().ensureActive()
   val jobs=withContext(Dispatchers.IO){store.searchUploadJobs().filter {it.optString("status") in listOf("pending","retry")}}
   if(jobs.isEmpty())return
   val nowMs=System.currentTimeMillis()
   val job=jobs.firstOrNull {it.optLong("next_retry_ms")<=nowMs||it.optLong("next_retry_ms")-nowMs>300000L}?:jobs.firstOrNull().takeIf {resumed}
   if(job==null){delay(2000);continue}
   resumed=false
   var stage="prepare"
   val next=try {
    require(ProfileUpload.localBase(api.base())&&job.getString("target_base")==api.base())
    stage="request_capability"
    val capability=panelRequest(job,SEARCH_UPLOAD_CAPABILITY,null)
    stage="verify_capability"
    require(capability.optString("schema_version")=="companion-search-transfer-capability-v1"&&capability.optBoolean("enabled")&&
     capability.optString("mode")=="local-panel-account-test"&&capability.optString("search_path")==SEARCH_UPLOAD_PATH)
    job.getJSONObject("upload_identity").let {owner->listOf("participant_id","study_id","panel_origin").forEach {require(capability.optString(it)==owner.getString(it))}}
    require(withContext(Dispatchers.IO){store.get(SEARCH_UPLOAD_PREFIX+job.getString("search_revision_id"))!=null})
    currentCoroutineContext().ensureActive()
    stage="request_search"
    val receipt=panelRequest(job,SEARCH_UPLOAD_PATH,job.getString("body"))
    stage="verify_receipt";SearchUpload.verifyReceipt(job,receipt)
    job.copyJson().put("status","stored").put("receipt",receipt).put("reason",JSONObject.NULL)
   }catch(e:PanelUploadPaused){return}
    catch(e:CancellationException){throw e}
    catch(e:ApiHttpException){
     (if(e.status in listOf(409,413,415,422))job.copyJson().put("status","failed").put("reason","server_rejected_search")
      else ProfileUpload.retry(job,"backend_or_parent_unavailable",System.currentTimeMillis()))
      .put("failure_stage",stage).put("failure_http_status",e.status).put("failure_type","ApiHttpException")
    }catch(e:Exception){ProfileUpload.retry(job,"waiting_for_backend",System.currentTimeMillis())
     .put("failure_stage",stage).put("failure_http_status",JSONObject.NULL).put("failure_type",e.javaClass.simpleName)}
   withContext(Dispatchers.IO){store.updateSearchUpload(next)}
   changed()
  }
 }catch(e:CancellationException){throw e}catch(e:Exception){/* Durable jobs resume on reopening. */}
}
