package de.ovgu.imiq.companion

import org.json.JSONObject
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal const val PRIORITY_UPLOAD_PREFIX="companion.priority-upload.job."
internal const val PRIORITY_UPLOAD_PATH="/api/companion/data/priority-observations"
internal const val PRIORITY_UPLOAD_CAPABILITY="/api/companion/data/priority-transfer/status"

internal object PriorityUpload {
 fun revision(kind:String,logicalId:String)="priority-"+ProfileUpload.digest("$kind|$logicalId")
 fun pending(kind:String,previous:JSONObject,current:JSONObject,record:JSONObject?,initial:JSONObject,identity:JSONObject,base:String,at:String,modelHash:String):JSONObject {
  require(kind in listOf("weekly_reference_confirmation","predecision_ema"))
  require(validPassport(previous)&&validPassport(current)&&ProfileUpload.localBase(base))
  val owner=panelUploadBinding(identity)
  require(canonical(initial.getJSONObject("upload_identity"))==canonical(owner)&&initial.getString("target_base")==base)
  require(initial.getString("questionnaire_attempt_id")==current.getString("questionnaire_attempt_id"))
  val before=previous.getJSONObject("response").getJSONObject("cognitive_passport")
  val passport=current.getJSONObject("response").getJSONObject("cognitive_passport")
  val lineage=passport.getJSONObject("lineage");val parent=before.getJSONObject("lineage")
  require(validGoalPriorities(passport)&&modelHash.matches(Regex("[0-9a-f]{64}")))
  val searching=kind=="predecision_ema"
  if(searching){require(record!=null&&validTripImportanceRecord(record));require(canonical(record.getJSONObject("request").getJSONObject("cognitive_passport"))==canonical(passport))}
  val searchId=if(searching)record!!.getString("search_id")else null
  val id=revision(kind,searchId?:lineage.getString("passport_id"))
  val body=obj("schema_version" to "companion-priorities-observation-v1","data_class" to "participant","kind" to kind,
   "priority_revision_id" to id,"search_id" to searchId,"app_version" to BuildConfig.VERSION_NAME,"created_at" to at,
   "initial_profile_revision_id" to initial.getString("profile_revision_id"),"questionnaire_attempt_id" to current.getString("questionnaire_attempt_id"),
   "base_passport_reference" to obj("passport_id" to parent.getString("passport_id"),"revision" to parent.getInt("revision")),
   "passport_reference" to obj(*listOf("passport_id","revision","parent_passport_id","origin").map {it to lineage.get(it)}.toTypedArray()),
   "goal_priorities_reference" to passport.getJSONObject("goal_priorities").copyJson(),
   "situated_importance" to (if(searching)record!!.getJSONObject("situated_importance").copyJson()else null),
   "contextual_passport_snapshot" to passport.copyJson(),
   "journey_availability" to (if(searching)record!!.getJSONObject("request").getJSONObject("journey_availability").copyJson()else null),
   "model_contract_sha256" to modelHash,"model_version" to passport.getString("version"),"readout_version" to "signed-actions-v1").toString()
  require(body.toByteArray(Charsets.UTF_8).size<=2*1024*1024)
  return obj("schema_version" to "companion-priority-upload-job-v1","priority_revision_id" to id,"kind" to kind,
   "search_id" to searchId,"created_at" to at,"target_base" to base,"upload_identity" to owner,
   "body" to body,"payload_sha256" to ProfileUpload.digest(body),"status" to "pending",
   "attempts" to 0,"next_retry_ms" to 0L,"receipt" to null,"reason" to null)
 }

 fun verifyReceipt(job:JSONObject,receipt:JSONObject) {
  require(receipt.optString("schema_version")=="companion-priority-storage-receipt-v1")
  require(receipt.opt("stored_on_server")==true&&receipt.opt("learning_applied")==false&&receipt.optString("data_class")=="participant")
  listOf("priority_revision_id","kind","payload_sha256").forEach {require(receipt.getString(it)==job.getString(it))}
  require(canonical(receipt.opt("search_id"))==canonical(job.opt("search_id")))
  require(receipt.optString("storage_record_id").matches(Regex("[0-9a-f]{64}")))
  job.getJSONObject("upload_identity").let {owner->listOf("participant_id","study_id","panel_origin","test_scope").forEach {require(receipt.optString(it)==owner.getString(it))}}
  Instant.parse(receipt.getString("stored_at"))
 }
}

internal fun Store.priorityUploadJobs():List<JSONObject> {
 val keys=readableDatabase.rawQuery("SELECT key FROM kv WHERE key LIKE ? ORDER BY key",arrayOf("$PRIORITY_UPLOAD_PREFIX%"))
  .use {cursor->buildList {while(cursor.moveToNext())add(cursor.getString(0))}}
 return keys.mapNotNull {json(it)}.sortedBy {it.optString("created_at")}
}

/** Caller holds the same transaction as the weekly profile or pending search. */
internal fun Store.queuePriorityUpload(kind:String,previous:JSONObject,current:JSONObject,record:JSONObject?,identity:JSONObject?,base:String,at:String,modelHash:String) {
 require(writableDatabase.inTransaction())
 val logicalId=record?.getString("search_id")?:current.getJSONObject("response").getJSONObject("cognitive_passport").getJSONObject("lineage").getString("passport_id")
 val id=PriorityUpload.revision(kind,logicalId)
 val key=PRIORITY_UPLOAD_PREFIX+id
 if(get(key)!=null)return
 val job=runCatching {
  require(identity!=null)
  val owner=panelUploadBinding(identity)
  val initial=profileUploadJobs().firstOrNull {it.optString("questionnaire_attempt_id")==current.optString("questionnaire_attempt_id")&&
   it.optString("target_base")==base&&it.optString("status")!="failed"&&canonical(it.optJSONObject("upload_identity"))==canonical(owner)}?:error("Initial profile not linked")
  PriorityUpload.pending(kind,previous,current,record,initial,identity,base,at,modelHash)
 }.getOrElse {obj("schema_version" to "companion-priority-upload-job-v1","priority_revision_id" to id,"kind" to kind,
  "search_id" to record?.opt("search_id"),"created_at" to at,"target_base" to base,"status" to "blocked","reason" to "priority_source_or_identity_unavailable")}
 put(key,job)
}

internal fun Store.updatePriorityUpload(job:JSONObject) {
 val db=writableDatabase;db.beginTransaction()
 try {
  val key=PRIORITY_UPLOAD_PREFIX+job.getString("priority_revision_id")
  val current=json(key)
  if(current!=null&&current.optString("status")!="stored"&&current.optString("payload_sha256")==job.optString("payload_sha256")&&
   canonical(current.optJSONObject("upload_identity"))==canonical(job.optJSONObject("upload_identity")))put(key,job)
  db.setTransactionSuccessful()
 }finally{db.endTransaction()}
}

internal suspend fun processPriorityUploads(store:Store,api:Api,panelRequest:suspend (JSONObject,String,String?)->JSONObject,changed:()->Unit) {
 try {
  var resumed=true
  while(true) {
   currentCoroutineContext().ensureActive()
   val jobs=withContext(Dispatchers.IO){store.priorityUploadJobs().filter {it.optString("status") in listOf("pending","retry")}}
   if(jobs.isEmpty())return
   val nowMs=System.currentTimeMillis()
   val job=jobs.firstOrNull {it.optLong("next_retry_ms")<=nowMs||it.optLong("next_retry_ms")-nowMs>300000L}?:jobs.firstOrNull().takeIf {resumed}
   if(job==null){delay(2000);continue}
   resumed=false
   var stage="prepare"
   val next=try {
    require(ProfileUpload.localBase(api.base())&&job.getString("target_base")==api.base())
    stage="request_capability"
    val capability=panelRequest(job,PRIORITY_UPLOAD_CAPABILITY,null)
    stage="verify_capability"
    require(capability.optString("schema_version")=="companion-priority-transfer-capability-v1"&&capability.optBoolean("enabled")&&
     capability.optString("mode")=="local-panel-account-test"&&capability.optString("priority_path")==PRIORITY_UPLOAD_PATH)
    job.getJSONObject("upload_identity").let {owner->listOf("participant_id","study_id","panel_origin").forEach {require(capability.optString(it)==owner.getString(it))}}
    require(withContext(Dispatchers.IO){store.get(PRIORITY_UPLOAD_PREFIX+job.getString("priority_revision_id"))!=null})
    currentCoroutineContext().ensureActive()
    stage="request_priority"
    val receipt=panelRequest(job,PRIORITY_UPLOAD_PATH,job.getString("body"))
    stage="verify_receipt";PriorityUpload.verifyReceipt(job,receipt)
    job.copyJson().put("status","stored").put("receipt",receipt).put("reason",JSONObject.NULL)
   }catch(e:PanelUploadPaused){return}
    catch(e:CancellationException){throw e}
    catch(e:ApiHttpException){
     (if(e.status in listOf(409,413,415,422))job.copyJson().put("status","failed").put("reason","server_rejected_priorities")
      else ProfileUpload.retry(job,"backend_or_parent_unavailable",System.currentTimeMillis()))
      .put("failure_stage",stage).put("failure_http_status",e.status).put("failure_type","ApiHttpException")
    }catch(e:Exception){ProfileUpload.retry(job,"waiting_for_backend",System.currentTimeMillis())
     .put("failure_stage",stage).put("failure_http_status",JSONObject.NULL).put("failure_type",e.javaClass.simpleName)}
   withContext(Dispatchers.IO){store.updatePriorityUpload(next)}
   changed()
  }
 }catch(e:CancellationException){throw e}catch(e:Exception){/* Durable jobs resume on reopening. */}
}
