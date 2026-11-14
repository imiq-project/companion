package de.ovgu.imiq.companion

import org.json.JSONObject
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal const val HOTCO_UPLOAD_PREFIX="companion.hotco-upload.job."
internal const val HOTCO_UPLOAD_PATH="/api/companion/data/hotco-simulations"
internal const val HOTCO_UPLOAD_CAPABILITY="/api/companion/data/hotco-transfer/status"

/** Preserve the backend-signed compressed packet; Android never rounds or decompresses states. */
internal object HotcoUpload {
 fun revision(id:String)="hotco-"+ProfileUpload.digest("hotco-v1|$id")
 fun pending(record:JSONObject,parent:JSONObject,identity:JSONObject,base:String):JSONObject {
  val id=record.getString("search_id");val owner=panelUploadBinding(identity)
  require(ProfileUpload.localBase(base)&&record.getString("status")=="complete")
  require(parent.getString("results_revision_id")==ResultsUpload.revision(id)&&parent.getString("target_base")==base)
  require(canonical(parent.getJSONObject("upload_identity"))==canonical(owner))
  val transfer=record.getJSONObject("result").getJSONObject("hotco_transfer");require(transfer.opt("available")==true)
  val body=transfer.getJSONObject("payload").copyJson()
  val allowed=setOf("schema_version","data_class","search_id","hotco_revision_id","results_reference","backend_generated_at","passport_input_sha256","packet","key_id","signature")
  require(body.keys().asSequence().toSet()==allowed)
  require(body.getString("schema_version")=="companion-hotco-transfer-v1"&&body.getString("data_class")=="participant")
  require(body.getString("search_id")==id&&body.getString("hotco_revision_id")==revision(id))
  require(body.getJSONObject("results_reference").getString("results_revision_id")==ResultsUpload.revision(id))
  require(body.getString("signature").matches(Regex("[0-9a-f]{64}")))
  require(body.getJSONObject("packet").getString("codec")=="gzip-json-utf8-v1")
  Instant.parse(body.getString("backend_generated_at"));Instant.parse(record.getString("result_received_at"))
  body.put("app_version",record.getString("app_version")).put("response_received_at",record.getString("result_received_at"))
  val raw=body.toString();require(raw.toByteArray(Charsets.UTF_8).size<=32*1024*1024)
  return obj("schema_version" to "companion-hotco-upload-job-v1","hotco_revision_id" to revision(id),"search_id" to id,
   "created_at" to record.getString("created_at"),"target_base" to base,"upload_identity" to owner,"body" to raw,
   "payload_sha256" to ProfileUpload.digest(raw),"status" to "pending","attempts" to 0,"next_retry_ms" to 0L,"receipt" to null,"reason" to null)
 }
 fun verifyReceipt(job:JSONObject,receipt:JSONObject) {
  require(receipt.optString("schema_version")=="companion-hotco-storage-receipt-v1"&&receipt.optString("collection")=="hotco_simulations")
  require(receipt.opt("stored_on_server")==true&&receipt.opt("learning_applied")==false&&receipt.optString("data_class")=="participant")
  listOf("hotco_revision_id","search_id","payload_sha256").forEach {require(receipt.getString(it)==job.getString(it))}
  listOf("storage_record_id","results_storage_record_id").forEach {require(receipt.getString(it).matches(Regex("[0-9a-f]{64}")))}
  require(receipt.getString("evidence_sha256")==JSONObject(job.getString("body")).getJSONObject("packet").getString("raw_sha256"))
  job.getJSONObject("upload_identity").let {owner->owner.keys().forEach {require(receipt.getString(it)==owner.getString(it))}}
  Instant.parse(receipt.getString("stored_at"))
 }
}

internal fun Store.queueHotcoUpload(record:JSONObject,identity:JSONObject?,base:String) {
 require(writableDatabase.inTransaction())
 if(record.optString("status")!="complete")return
 val id=HotcoUpload.revision(record.getString("search_id"));val key=HOTCO_UPLOAD_PREFIX+id
 if(get(key)!=null)return
 if(record.getJSONObject("result").isNull("contextual_deliberation")){put(key,obj("hotco_revision_id" to id,"status" to "not_applicable"));return}
 val job=runCatching {
  require(identity!=null)
  val parent=json(RESULTS_UPLOAD_PREFIX+ResultsUpload.revision(record.getString("search_id")))?:error("Results source missing")
  HotcoUpload.pending(record,parent,identity,base)
 }.getOrElse {obj("schema_version" to "companion-hotco-upload-job-v1","hotco_revision_id" to id,"search_id" to record.getString("search_id"),
  "created_at" to record.getString("created_at"),"status" to "blocked","reason" to "hotco_packet_or_identity_unavailable")}
 put(key,job)
}

internal fun Store.hotcoUploadJobs():List<JSONObject> {
 // Only materialize one pending body. Stored histories can contain many megabytes each.
 val at=System.currentTimeMillis()
 val keys=readableDatabase.rawQuery("SELECT key FROM kv WHERE key LIKE ? AND json_extract(value,'$.status') IN ('pending','retry') ORDER BY CASE WHEN json_extract(value,'$.next_retry_ms')<=? OR json_extract(value,'$.next_retry_ms')>? THEN 0 ELSE 1 END,json_extract(value,'$.created_at') LIMIT 1",arrayOf("$HOTCO_UPLOAD_PREFIX%",at.toString(),(at+300000L).toString()))
  .use {cursor->buildList {while(cursor.moveToNext())add(cursor.getString(0))}}
 return keys.mapNotNull {json(it)}.sortedBy {it.optString("created_at")}
}

internal fun Store.hotcoUploadHeader(id:String):JSONObject? = readableDatabase.rawQuery("SELECT json_object('status',json_extract(value,'$.status'),'payload_sha256',json_extract(value,'$.payload_sha256'),'upload_identity',json_extract(value,'$.upload_identity')) FROM kv WHERE key=?",arrayOf(HOTCO_UPLOAD_PREFIX+id))
 .use {if(it.moveToFirst())JSONObject(it.getString(0))else null}

internal fun Store.updateHotcoUpload(job:JSONObject) {
 val db=writableDatabase;db.beginTransaction()
 try {
  val key=HOTCO_UPLOAD_PREFIX+job.getString("hotco_revision_id")
  val current=hotcoUploadHeader(job.getString("hotco_revision_id"))
  if(current!=null&&current.optString("status")!="stored"&&current.optString("payload_sha256")==job.optString("payload_sha256")&&
   canonical(current.optJSONObject("upload_identity"))==canonical(job.optJSONObject("upload_identity")))put(key,job)
  db.setTransactionSuccessful()
 }finally{db.endTransaction()}
}

internal suspend fun processHotcoUploads(store:Store,api:Api,panelRequest:suspend (JSONObject,String,String?)->JSONObject,changed:()->Unit) {
 try {
  var resumed=true
  while(true) {
   currentCoroutineContext().ensureActive()
   val jobs=withContext(Dispatchers.IO){store.hotcoUploadJobs().filter {it.optString("status") in listOf("pending","retry")}}
   if(jobs.isEmpty())return
   val nowMs=System.currentTimeMillis()
   val job=jobs.firstOrNull {it.optLong("next_retry_ms")<=nowMs||it.optLong("next_retry_ms")-nowMs>300000L}?:jobs.firstOrNull().takeIf {resumed}
   if(job==null){delay(2000);continue}
   resumed=false
   var stage="prepare"
   val next=try {
    require(ProfileUpload.localBase(api.base())&&job.getString("target_base")==api.base())
    stage="request_capability"
    val capability=panelRequest(job,HOTCO_UPLOAD_CAPABILITY,null)
    stage="verify_capability"
    require(capability.optString("schema_version")=="companion-hotco-transfer-capability-v1"&&capability.optBoolean("enabled")&&
     capability.optString("mode")=="local-panel-account-test"&&capability.optString("hotco_path")==HOTCO_UPLOAD_PATH)
    job.getJSONObject("upload_identity").let {owner->listOf("participant_id","study_id","panel_origin").forEach {require(capability.optString(it)==owner.getString(it))}}
    require(withContext(Dispatchers.IO){store.hotcoUploadHeader(job.getString("hotco_revision_id"))!=null})
    currentCoroutineContext().ensureActive()
    stage="request_hotco"
    val receipt=panelRequest(job,HOTCO_UPLOAD_PATH,job.getString("body"))
    stage="verify_receipt";HotcoUpload.verifyReceipt(job,receipt)
    job.copyJson().put("status","stored").put("receipt",receipt).put("reason",JSONObject.NULL)
   }catch(e:PanelUploadPaused){return}
    catch(e:CancellationException){throw e}
    catch(e:ApiHttpException){
     (if(e.status in listOf(409,413,415,422))job.copyJson().put("status","failed").put("reason","server_rejected_hotco")
      else ProfileUpload.retry(job,"backend_or_parent_unavailable",System.currentTimeMillis()))
      .put("failure_stage",stage).put("failure_http_status",e.status).put("failure_type","ApiHttpException")
    }catch(e:Exception){ProfileUpload.retry(job,"waiting_for_backend",System.currentTimeMillis())
     .put("failure_stage",stage).put("failure_http_status",JSONObject.NULL).put("failure_type",e.javaClass.simpleName)}
   withContext(Dispatchers.IO){store.updateHotcoUpload(next)}
   changed()
  }
 }catch(e:CancellationException){throw e}catch(e:Exception){/* Durable jobs resume on reopening. */}
}
