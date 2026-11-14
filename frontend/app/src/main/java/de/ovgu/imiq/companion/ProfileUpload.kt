package de.ovgu.imiq.companion

import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal const val PROFILE_UPLOAD_PREFIX="companion.profile-upload.job."
internal const val PROFILE_UPLOAD_PATH="/api/companion/data/profile-versions"
internal const val PROFILE_UPLOAD_CAPABILITY="/api/companion/data/profile-transfer/status"

/** Immutable wire body; never rebuild it during a retry or include journeys/GPS. */
internal object ProfileUpload {
 fun digest(body:String)=MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
 fun localBase(base:String)=runCatching {val uri=URI(base);uri.userInfo==null&&uri.query==null&&uri.fragment==null&&
  uri.scheme in listOf("http","https")&&uri.host in listOf("localhost","127.0.0.1","::1","10.0.2.2")}.getOrDefault(false)

 fun pending(profile:JSONObject,modelHash:String,base:String,at:String,previous:String?=null,identity:JSONObject?=null):JSONObject {
  require(validPassport(profile))
  val attempt=profile.getString("questionnaire_attempt_id")
  val source=profile.getJSONObject("questionnaire_source").copyJson()
  require(source.keys().asSequence().toSet()==setOf("onboarding_request","calibration_start_response","calibration_completion_request","calibration_completion_response","bootstrap_request"))
  val timing=profile.getJSONObject("timing").copyJson()
  require(timing.getString("questionnaire_timing_status") in listOf("measured","unavailable"))
  require(modelHash.matches(Regex("[0-9a-f]{64}")))
  val passport=profile.getJSONObject("response").getJSONObject("cognitive_passport")
  val allowed=listOf("version","fingerprint","confirmedAt","createdAt","weekStart","goalRatings","needsSource","conversion",
   "availability","sourceSnapshot","response","questionnaire_attempt_id","timing","questionnaire_source")
  val snapshot=obj(*allowed.map {it to profile.get(it)}.toTypedArray()).copyJson()
  require(snapshot.getJSONObject("sourceSnapshot").keys().asSequence().toSet()==setOf("plan","valences","availability","habitual_mobility","toleranceState","inputs"))
  val agent=source.getJSONObject("onboarding_request").getString("agent_id")
  val revision="profile-"+UUID.nameUUIDFromBytes((agent+"|"+attempt+"|"+passport.getJSONObject("lineage").getString("passport_id")).toByteArray(Charsets.UTF_8))
  val body=obj("schema_version" to "companion-questionnaire-profile-v1-draft","data_class" to "participant",
   "status" to "confirmed","profile_revision_id" to revision,"previous_revision_id" to previous,
   "questionnaire_attempt_id" to attempt,"timing" to timing,"source" to source,
   "android_profile_snapshot" to snapshot,"model_contract_sha256" to modelHash).toString()
  require(body.toByteArray(Charsets.UTF_8).size<=4*1024*1024) {"Profile exceeds upload limit"}
  return obj("schema_version" to "companion-profile-upload-job-v1","profile_revision_id" to revision,
   "questionnaire_attempt_id" to attempt,"agent_id" to agent,"target_base" to base,
   "created_at" to at,"body" to body,"payload_sha256" to digest(body),"status" to "pending",
   "attempts" to 0,"next_retry_ms" to 0L,"receipt" to null,"reason" to null).also {job->
    if(identity!=null){require(localBase(base));job.put("upload_identity",panelUploadBinding(identity))}
   }
 }

 fun verifyReceipt(job:JSONObject,receipt:JSONObject) {
  require(receipt.optString("schema_version")=="companion-profile-storage-receipt-v1")
  require(receipt.opt("stored_on_server")==true&&receipt.opt("learning_applied")==false)
  require(receipt.optString("data_class")=="participant")
  require(receipt.optString("profile_revision_id")==job.getString("profile_revision_id"))
  require(receipt.optString("payload_sha256")==job.getString("payload_sha256"))
  require(receipt.optString("storage_record_id").matches(Regex("[0-9a-f]{64}")))
  Instant.parse(receipt.getString("stored_at"))
  job.optJSONObject("upload_identity")?.let {owner->
   listOf("participant_id","study_id","panel_origin","test_scope").forEach {require(receipt.optString(it)==owner.getString(it))}
  }
 }

 fun retry(job:JSONObject,reason:String,nowMs:Long):JSONObject {
  val count=job.optInt("attempts")+1
  val seconds=minOf(300L,5L*(1L shl minOf(count-1,6)))
  return job.copyJson().put("status","retry").put("reason",reason).put("attempts",count).put("next_retry_ms",nowMs+seconds*1000)
 }
}

internal fun Store.profileUploadJobs():List<JSONObject> {
 val keys=readableDatabase.rawQuery("SELECT key FROM kv WHERE key LIKE ? ORDER BY key",arrayOf("$PROFILE_UPLOAD_PREFIX%"))
  .use {c->buildList {while(c.moveToNext())add(c.getString(0))}}
 return keys.mapNotNull {json(it)}.sortedBy {it.optString("created_at")}
}

/** Must run in the same transaction that saves the newly confirmed Passport. */
internal fun Store.queueProfileUpload(profile:JSONObject,modelHash:String,base:String,at:String,identity:JSONObject?=null) {
 require(writableDatabase.inTransaction())
 val agent=profile.optJSONObject("questionnaire_source")?.optJSONObject("onboarding_request")?.optString("agent_id")
 val owner=identity?.takeIf {it.optBoolean("local_test_profile_upload")}?.let(::panelUploadBinding)
 val previous=profileUploadJobs().lastOrNull {it.optString("status")=="stored"&&it.optString("agent_id")==agent&&it.optString("target_base")==base&&canonical(it.optJSONObject("upload_identity"))==canonical(owner)}?.optString("profile_revision_id")
 val job=runCatching {ProfileUpload.pending(profile,modelHash,base,at,previous,if(owner!=null)identity else null)}.getOrElse {
  obj("schema_version" to "companion-profile-upload-job-v1","profile_revision_id" to "unavailable-${UUID.randomUUID()}",
   "questionnaire_attempt_id" to profile.opt("questionnaire_attempt_id"),"status" to "failed",
   "reason" to "profile_source_unavailable","created_at" to at,"target_base" to base)
 }
 val key=PROFILE_UPLOAD_PREFIX+job.getString("profile_revision_id")
 if(get(key)==null)put(key,job)
}

internal fun Store.updateProfileUpload(job:JSONObject) {
 val db=writableDatabase;db.beginTransaction()
 try {
  val key=PROFILE_UPLOAD_PREFIX+job.getString("profile_revision_id")
  val current=json(key)
  // A reset/import must not be undone by a late network response.
  if(current!=null&&current.optString("payload_sha256")==job.optString("payload_sha256")&&canonical(current.optJSONObject("upload_identity"))==canonical(job.optJSONObject("upload_identity"))&&current.optString("status")!="stored")put(key,job)
  db.setTransactionSuccessful()
 }finally{db.endTransaction()}
}

internal suspend fun processProfileUploads(store:Store,api:Api,panelRequest:(suspend (JSONObject,String,String?)->JSONObject)?=null,changed:()->Unit) {
 try {
  var firstAttemptAfterResume=true
  while(true) {
   currentCoroutineContext().ensureActive()
   val panelMode=withContext(Dispatchers.IO){store.get(PANEL_LOGIN_MODE)=="1"}
   if(panelMode&&panelRequest==null)return
   val jobs=withContext(Dispatchers.IO){store.profileUploadJobs().filter {it.optString("status") in listOf("pending","retry")&&(!panelMode||it.optJSONObject("upload_identity")!=null)}}
   if(jobs.isEmpty())return
   val nowMs=System.currentTimeMillis()
   val job=jobs.firstOrNull {it.optLong("next_retry_ms")<=nowMs||it.optLong("next_retry_ms")-nowMs>300000L}
    ?:jobs.firstOrNull().takeIf {firstAttemptAfterResume}
   if(job==null){delay(2000);continue}
   firstAttemptAfterResume=false
   var failureStage="prepare"
   val next=try {
    require(ProfileUpload.localBase(api.base())&&job.getString("target_base")==api.base()) {"Backend target changed"}
    failureStage="request_capability"
    val capability=if(panelMode)panelRequest!!(job,PROFILE_UPLOAD_CAPABILITY,null)else api.call(PROFILE_UPLOAD_CAPABILITY,timeout=15000)
    failureStage="verify_capability"
    require(capability.optString("schema_version")=="companion-profile-transfer-capability-v1"&&
     capability.optBoolean("enabled")&&capability.optString("mode")== (if(panelMode)"local-panel-account-test"else"local-profile-validation")&&
     capability.optString("profile_path")==PROFILE_UPLOAD_PATH)
    if(panelMode)job.getJSONObject("upload_identity").let {owner->listOf("participant_id","study_id","panel_origin").forEach {require(capability.optString(it)==owner.getString(it))}}
    currentCoroutineContext().ensureActive()
    require(withContext(Dispatchers.IO){store.get(PROFILE_UPLOAD_PREFIX+job.getString("profile_revision_id"))!=null})
    failureStage="request_profile"
    val receipt=if(panelMode)panelRequest!!(job,PROFILE_UPLOAD_PATH,job.getString("body"))else api.callEncoded(PROFILE_UPLOAD_PATH,job.getString("body"),timeout=30000,expectedBase=job.getString("target_base"))
    failureStage="verify_receipt"
    ProfileUpload.verifyReceipt(job,receipt)
    job.copyJson().put("status","stored").put("receipt",receipt).put("reason",JSONObject.NULL)
   }catch(e:PanelUploadPaused){return}
    catch(e:CancellationException){throw e}
    catch(e:ApiHttpException){
     (if(e.status in listOf(409,413,415,422))job.copyJson().put("status","failed").put("reason","server_rejected_profile")
     else ProfileUpload.retry(job,"backend_unavailable",System.currentTimeMillis()))
      .put("failure_stage",failureStage).put("failure_http_status",e.status).put("failure_type","ApiHttpException")
    }
    catch(e:Exception){ProfileUpload.retry(job,"waiting_for_backend",System.currentTimeMillis()).put("failure_stage",failureStage).put("failure_http_status",JSONObject.NULL).put("failure_type",e.javaClass.simpleName)}
   withContext(Dispatchers.IO){store.updateProfileUpload(next)}
   changed()
  }
 }catch(e:CancellationException){throw e}
 catch(e:Exception){ /* Persisted jobs remain for the next application launch. */ }
}
