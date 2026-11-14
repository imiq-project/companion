package de.ovgu.imiq.companion

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

internal const val STARTUP_DURATION_MS=12000L

class CompanionModel @JvmOverloads constructor(app:Application,databaseName:String="companion-native-v1.db"):AndroidViewModel(app) {
 private val database=Store(app,databaseName)
 var revision by mutableStateOf(0)
 val store:Store get(){revision;return database}
 val api=Api(database)
 val trips=TripRepository(app,database)
 private val panelSessions=PanelSessionStore(app)
 private val panelSessionGate=Mutex()
 internal var panelIdentity by mutableStateOf<JSONObject?>(null)
  private set
 internal var panelLoginBusy by mutableStateOf(false)
  private set
 internal var panelLoginError by mutableStateOf<String?>(null)
  private set
 private var panelOperation:Job?=null
 private var panelRestoreStarted=false
 internal val experienceDrafts=ExperienceDraftWriter(database,viewModelScope)
 var tripSearchId by mutableStateOf<String?>(null)
 var tripId by mutableStateOf<String?>(null)
 var tripStartingSearchId by mutableStateOf<String?>(null)
 init {
  database.put(PANEL_LOGIN_MODE,"1")
  viewModelScope.launch {TripUpdates.revision.collect {revision++}}
  viewModelScope.launch {TripUpdates.errors.collect {failure->
   error=failure.message
   if(failure.searchId!=null&&tripStartingSearchId==failure.searchId){tripStartingSearchId=null;routeView="alternatives"}
  }}
 }
 var ready by mutableStateOf(false)
 var migrationError by mutableStateOf<String?>(null)
 var pendingImport by mutableStateOf<JSONObject?>(null)
 var tab by mutableStateOf("home")
 var step by mutableStateOf("identity")
 var locale by mutableStateOf(store.get("imiq.experimental.language")?:"en")
 var busy by mutableStateOf(false)
 var routeSearching by mutableStateOf(false)
 var error by mutableStateOf<String?>(null)
 var notice by mutableStateOf<String?>(null)
 var journeyPriorityOverrides by mutableStateOf(database.json("imiq.experimental.journey-priorities.v1")?.optJSONObject("overrides")?:JSONObject())
 private var journeyPriorityBasis:String?=database.json("imiq.experimental.journey-priorities.v1")?.optString("basis")?.takeUnless {it.isBlank()||it=="null"}
 private var journeyPriorityConfirmedAt:String?=database.json("imiq.experimental.journey-priorities.v1")?.optString("confirmed_at")?.takeUnless {it.isBlank()||it=="null"}
 internal var pendingTripImportance by mutableStateOf<PendingTripImportance?>(null)
  private set
 internal var tripImportanceDraft by mutableStateOf(TripImportanceDraft())
  private set
 var pendingPassport by mutableStateOf<JSONObject?>(null)
 private var pendingFingerprint:String?=null
 private var pendingQuestionnaireAttemptId:String?=null
 private var profileUploadJob:Job?=null
 private var priorityUploadJob:Job?=null
 private var searchUploadJob:Job?=null
 private var resultsUploadJob:Job?=null
 private var hotcoUploadJob:Job?=null
 internal fun startHotcoUploads(){
  if(panelIdentity?.optBoolean("local_test_profile_upload")!=true||hotcoUploadJob?.isActive==true)return
  hotcoUploadJob=viewModelScope.launch {processHotcoUploads(database,api,::requestPanelProfile){revision++}}
 }
 internal fun hotcoUploadStatus(id:String):String? {revision;return database.hotcoUploadHeader(HotcoUpload.revision(id))?.optString("status")}
 internal fun startResultsUploads(){
  if(panelIdentity?.optBoolean("local_test_profile_upload")!=true||resultsUploadJob?.isActive==true)return
  resultsUploadJob=viewModelScope.launch {processResultsUploads(database,api,::requestPanelProfile){revision++}}
 }
 internal fun resultsUploadStatus(id:String):String? {revision;return database.resultsUploadHeader(ResultsUpload.revision(id))?.optString("status")}
 internal fun startSearchUploads(){
  if(panelIdentity?.optBoolean("local_test_profile_upload")!=true||searchUploadJob?.isActive==true)return
  searchUploadJob=viewModelScope.launch {processSearchUploads(database,api,::requestPanelProfile){revision++}}
 }
 internal fun searchUploadStatus(id:String):String? {revision;return database.json(SEARCH_UPLOAD_PREFIX+SearchUpload.revision(id))?.optString("status")}
 private fun modelContractHash():String=getApplication<Application>().assets.open("companion-identity-v1.json").bufferedReader().use {
  JSONObject(it.readText()).getJSONObject("current_contract_sha256").getString("contracts/hotco-signed-model-v1.json")}
 internal fun startPriorityUploads(){
  if(panelIdentity?.optBoolean("local_test_profile_upload")!=true||priorityUploadJob?.isActive==true)return
  priorityUploadJob=viewModelScope.launch {processPriorityUploads(database,api,::requestPanelProfile){revision++}}
 }
 internal fun priorityUploadStatus(searchId:String):String? {
  revision
  return database.json(PRIORITY_UPLOAD_PREFIX+PriorityUpload.revision("predecision_ema",searchId))?.optString("status")
 }
 internal fun weeklyPriorityUploadStatus():String? {
  revision
  val id=profile()?.optJSONObject("lineage")?.optString("passport_id")?:return null
  return database.json(PRIORITY_UPLOAD_PREFIX+PriorityUpload.revision("weekly_reference_confirmation",id))?.optString("status")
 }
 internal fun startProfileUploads(){
  // Explicit local test permission is separate from unverified study consent.
  if(panelIdentity?.optBoolean("local_test_profile_upload")!=true)return
  database.put(PANEL_LOGIN_MODE,"1")
  startPriorityUploads();startSearchUploads();startResultsUploads();startHotcoUploads()
  if(profileUploadJob?.isActive==true)return
  profileUploadJob=viewModelScope.launch {processProfileUploads(database,api,::requestPanelProfile){revision++}}
 }
 internal fun profileUploadStatus():String? {
  revision
  val attempt=database.passport()?.optString("questionnaire_attempt_id")?:return null
  val job=database.profileUploadJobs().lastOrNull {it.optString("questionnaire_attempt_id")==attempt}?:return null
  if(job.optString("status")=="stored")return "stored"
  if(panelIdentity?.optBoolean("local_test_profile_upload")!=true)return "authorization_pending"
  if(job.optJSONObject("upload_identity")==null)return "identity_unlinked"
  return job.optString("status")
 }
 internal fun restorePanelLogin(){
  if(panelRestoreStarted)return
  panelRestoreStarted=true
  database.put(PANEL_LOGIN_MODE,"1");profileUploadJob?.cancel();priorityUploadJob?.cancel();searchUploadJob?.cancel();resultsUploadJob?.cancel();hotcoUploadJob?.cancel()
  panelOperation=viewModelScope.launch {
   panelLoginBusy=true
   try {
    val saved=withContext(Dispatchers.IO){panelSessions.read()}?:return@launch
    if(!saved.has("access")||!saved.has("refresh"))return@launch
    if(saved.optString("backend")!=api.base())return@launch
    val base=api.base()
    val response=try {api.panelRequest("/me",access=saved.getString("access"),expectedBase=base)}catch(e:ApiHttpException){
     if(e.status!=401)throw e
     api.panelRequest("/refresh",obj("refresh" to saved.getString("refresh")),expectedBase=base)
    }
    val identity=validatePanelIdentity(response.getJSONObject("identity"))
    require(identity.getString("participant_id")==saved.getJSONObject("identity").getString("participant_id"))
    val session=(if(response.has("access"))response.copyJson().put("backend",base)else saved).put("identity",identity)
    withContext(Dispatchers.IO){panelSessions.write(session)}
    require(api.base()==base)
    panelIdentity=identity
    startProfileUploads()
   }catch(e:CancellationException){throw e}catch(e:Exception){panelLoginError=panelFailure(e)}
   finally{panelLoginBusy=false}
  }
 }
 internal fun signInPanel(email:String,password:String){
  if(panelLoginBusy)return
  panelOperation=viewModelScope.launch {
   panelLoginBusy=true;panelLoginError=null
   try {
    val base=api.base()
    val response=api.panelRequest("/login",obj("email" to email,"password" to password),expectedBase=base)
    val identity=validatePanelIdentity(response.getJSONObject("identity"))
    // The binding survives logout/reset/import; another user must clear app data.
    val prior=withContext(Dispatchers.IO){panelSessions.read()}
    require(prior==null||prior.getJSONObject("identity").getString("participant_id")==identity.getString("participant_id")) {"different_panel_account"}
    require(api.base()==base)
    response.put("backend",base)
    withContext(Dispatchers.IO){panelSessions.write(response)}
    panelIdentity=identity
    startProfileUploads()
   }catch(e:CancellationException){throw e}catch(e:Exception){panelLoginError=panelFailure(e)}
   finally{panelLoginBusy=false}
  }
 }
 internal fun signOutPanel(){
  if(panelLoginBusy)return
  if(database.json(ACTIVE_TRIP)!=null||database.json(ACTIVE_PLAYBACK)!=null)return
  panelOperation?.cancel();profileUploadJob?.cancel();priorityUploadJob?.cancel();searchUploadJob?.cancel();resultsUploadJob?.cancel();hotcoUploadJob?.cancel()
  panelOperation=viewModelScope.launch {
   panelLoginBusy=true
   try {panelSessionGate.withLock {
    withContext(Dispatchers.IO){val old=panelSessions.read();if(old!=null){old.remove("access");old.remove("refresh");panelSessions.write(old)}}
    panelIdentity=null;panelLoginError=null
   }}catch(e:CancellationException){throw e}catch(e:Exception){panelLoginError=panelFailure(e)}
   finally{panelLoginBusy=false}
  }
 }
 private suspend fun requestPanelProfile(job:JSONObject,path:String,body:String?):JSONObject=panelSessionGate.withLock {
  val current=panelIdentity?:throw PanelUploadPaused()
  val owner=job.optJSONObject("upload_identity")?:throw PanelUploadPaused()
  if(canonical(owner)!=canonical(panelUploadBinding(current)))throw PanelUploadPaused()
  val base=job.getString("target_base")
  require(ProfileUpload.localBase(base)&&api.base()==base)
  var session=withContext(Dispatchers.IO){panelSessions.read()}?:throw PanelUploadPaused()
  require(session.optString("backend")==base&&canonical(panelUploadBinding(session.getJSONObject("identity")))==canonical(owner))
  if(!session.has("access")||!session.has("refresh"))throw PanelUploadPaused()
  suspend fun send():JSONObject=withContext(Dispatchers.IO){
   require(api.base()==base)
   apiJsonRequest(base+path,timeout=if(path==RESULTS_UPLOAD_PATH||path==HOTCO_UPLOAD_PATH)120000 else 30000,rawBody=body?.toByteArray(Charsets.UTF_8),bearer=session.getString("access"),participantId=owner.getString("participant_id"))
  }
  try {send()}catch(e:ApiHttpException){
   if(e.status!=401)throw e
   val refreshed=try {api.panelRequest("/refresh",obj("refresh" to session.getString("refresh")),expectedBase=base)}catch(refreshError:ApiHttpException){
    if(refreshError.status!=401)throw refreshError
    session.remove("access");session.remove("refresh");withContext(Dispatchers.IO){panelSessions.write(session)}
    panelIdentity=null;panelLoginError=panelFailure(refreshError);throw PanelUploadPaused()
   }
   val identity=validatePanelIdentity(refreshed.getJSONObject("identity"))
   if(canonical(panelUploadBinding(identity))!=canonical(owner))throw PanelUploadPaused()
   session=refreshed.put("backend",base)
   withContext(Dispatchers.IO){panelSessions.write(session)}
   panelIdentity=identity
   send()
  }
 }
 internal fun setPanelBackend(value:String){
  if(panelLoginBusy)return
  runCatching {val base=api.normalizeBase(value);require(safePanelBackend(base));database.put("companion.connection.v1",base);panelLoginError=null}
   .onFailure {panelLoginError=text("Use an HTTPS address or the local USB backend.","Verwende HTTPS oder das lokale USB-Backend.")}
 }
 private fun panelFailure(e:Exception):String=when {
  e.message=="different_panel_account"->text("Local data belongs to another panel account. Clear Companion app data before changing participant.","Die lokalen Daten gehören zu einem anderen Panelkonto. Lösche die App-Daten vor einem Kontowechsel.")
  e is ApiHttpException&&e.status==401->text("Sign-in failed or session expired. Check your email and password and verify your panel account.","Anmeldung fehlgeschlagen oder Sitzung abgelaufen. Prüfe E-Mail, Passwort und die Bestätigung deines Panelkontos.")
  e is ApiHttpException&&e.status==429->text("Too many attempts. Please wait a minute.","Zu viele Versuche. Bitte warte eine Minute.")
  else->text("Unable to verify your panel account. Check the backend connection and try again.","Dein Panelkonto konnte nicht geprüft werden. Prüfe die Backend-Verbindung und versuche es erneut.")
 }
 var result by mutableStateOf<JSONObject?>(null)
 var origin by mutableStateOf<Place?>(null)
 var planningLocationAttempted by mutableStateOf(false)
 var planningPosition by mutableStateOf<JSONObject?>(null)
 var destination by mutableStateOf<Place?>(null)
 var selectedId by mutableStateOf<String?>(null)
 var chosenId by mutableStateOf<String?>(null)
 var routeView by mutableStateOf("search")
 var activeMode by mutableStateOf("walk")
 var historyId by mutableStateOf<String?>(null)
 var routeProfileSnapshot by mutableStateOf<Pair<String,JSONObject>?>(null)
 var reflection by mutableStateOf<JSONObject?>(null)
 var reflectionBusy by mutableStateOf(false)
 var reflectionError by mutableStateOf<String?>(null)
 var companionReflection by mutableStateOf<JSONObject?>(null)
 var companionReflectionBusy by mutableStateOf(false)
 var companionReflectionFailed by mutableStateOf(false)
 private var companionNarration:Job?=null
 private var companionNarrationVersion=0
 var beliefStart by mutableStateOf<JSONObject?>(null)
 var beliefIndex by mutableStateOf(0)
 var beliefAnswers by mutableStateOf(JSONObject())
 private var operation:Job?=null
 private var operationVersion=0
 private var narration:Job?=null
 private var narrationVersion=0
 private val geocodingGate=Mutex()
 private var nextGeocodingStart=0L
 private val geocodingCache=linkedMapOf<String,Pair<Long,List<Place>>>()
 val de get()=locale=="de"
 fun text(en:String,de:String)=if(this.de)de else en
 fun refresh(){journeyPriorityOverrides=database.json("imiq.experimental.journey-priorities.v1")?.optJSONObject("overrides")?:JSONObject();journeyPriorityBasis=database.json("imiq.experimental.journey-priorities.v1")?.optString("basis");journeyPriorityConfirmedAt=database.json("imiq.experimental.journey-priorities.v1")?.optString("confirmed_at")?.takeUnless {it=="null"||it.isBlank()};revision++;ready=true;step=store.get(ONBOARDING)?.takeIf { it in listOf("identity","travel-kit","goals","valences","beliefs","passport") }?:"identity";ProspectiveLearning.resume(getApplication());LearnedPassportLearning.resume(getApplication());startProfileUploads()}
 fun companion()=imdilloPresentation(store.json(COMPANION)?:store.json("imiq.experimental.companion-draft.v1"))
 fun passport()=store.passport()
 fun profile()=passport()?.optJSONObject("response")?.optJSONObject("cognitive_passport")
 fun settings():JSONObject {
  val value=store.json(SETTINGS)?:obj("version" to 1,"motion" to "full","textSize" to "normal","maxWalk" to 1500)
  if(value.optInt("walkingDefaultVersion")<2) {
   if(!value.has("maxWalk")||value.optInt("maxWalk")==500)value.put("maxWalk",1500)
   value.put("walkingDefaultVersion",2);store.put(SETTINGS,value)
  }
  return value
 }
 fun availability()=store.json("imiq.experimental.availability.v1")?.optJSONObject("answers")?:obj(*modes.map { it to null }.toTypedArray())
 fun valences()=store.json("imiq.experimental.valences-draft.v1")?.optJSONObject("answers")?:store.json("imiq.experimental.valences.v1")?.optJSONObject("answers")?:obj(*modes.map { it to null }.toTypedArray())
 fun plan():JSONObject=proposedDailyPlan(store.json(dailyKey(today(),"draft"))?:store.json(dailyKey(today(),"saved")),passport(),today())
 fun saveWeeklyPriorities(values:JSONObject)=run {
  val previous=passport()?:error("Confirm your Passport first")
  val uploadIdentity=panelIdentity?.copyJson();val hash=modelContractHash();val target=api.base()
  val weekly=explicitWeeklyReference(values)
  val response=api.call("/api/companion/goal-personalization/prepare",priorityRequest(previous,weekly,JSONObject(),null))
  val next=validatedPriorityPassport(previous,weekly,JSONObject(),null,response)
  withContext(Dispatchers.IO){database.commitGoalPassport(previous,next){
   database.queuePriorityUpload("weekly_reference_confirmation",previous,next,null,uploadIdentity,target,weekly.getString("confirmed_at"),hash)
  }}
  startPriorityUploads();startSearchUploads();startResultsUploads();startHotcoUploads()
  journeyPriorityOverrides=JSONObject();journeyPriorityBasis=null;journeyPriorityConfirmedAt=null
  database.remove("imiq.experimental.journey-priorities.v1")
  notice=text("Weekly priorities saved. Journey adjustments were reset.","Wochenprioritäten gespeichert. Reiseanpassungen zurückgesetzt.");revision++
 }

 fun goStep(next:String){store.put(ONBOARDING,next);step=next;error=null;pendingPassport=null;pendingQuestionnaireAttemptId=null;revision++}
 private fun questionnaireClock()=QuestionnaireClock(now(),android.os.SystemClock.elapsedRealtime(),trips.boot())
 internal fun questionnaireVisible(){
  if(step !in listOf("travel-kit","goals","valences","beliefs","passport")||database.json(QUESTIONNAIRE_ATTEMPT)!=null)return
  // A pre-existing incomplete questionnaire has no observed start; never backfill one.
  val fresh=step=="travel-kit"&&(database.json("companion.passport-renewal.v1")!=null||
   listOf("imiq.experimental.valences.v1","imiq.experimental.valences-draft.v1","imiq.experimental.adaptive-beliefs.v1","imiq.experimental.adaptive-profile.v1").all {database.json(it)==null}&&database.json(dailyKey(today(),"saved"))==null)
  database.put(QUESTIONNAIRE_ATTEMPT,QuestionnaireTiming.begin("questionnaire-${UUID.randomUUID()}",questionnaireClock(),fresh))
 }
 private fun questionnaireAttempt():JSONObject=database.json(QUESTIONNAIRE_ATTEMPT)?:
  QuestionnaireTiming.begin("questionnaire-${UUID.randomUUID()}",questionnaireClock(),false).also {database.put(QUESTIONNAIRE_ATTEMPT,it)}
 fun setLanguage(next:String){locale=next;store.put("imiq.experimental.language",next)}
 fun saveCompanion(name:String,appearance:String){require(name.trim().isNotEmpty());val item=obj("version" to 1,"name" to name.trim().take(24),"appearance" to appearance,"palette" to "mint");store.put(COMPANION,item);store.put("imiq.experimental.companion-draft.v1",item);revision++}
 fun startMobilityProfile(){
  // Leave legacy names/appearances and confirmed Passport evidence untouched.
  if(store.json(COMPANION)==null&&store.json("imiq.experimental.companion-draft.v1")==null)saveCompanion("Imdillo","imdillo")
  goStep("travel-kit")
 }
 fun saveAvailability(value:JSONObject){store.put("imiq.experimental.availability.v1",obj("version" to 1,"answers" to value));revision++}
 fun mobilityHabits()=store.json("companion.mobility-habits.v1")?.optJSONObject("answers")?:obj(*modes.map {it to null}.toTypedArray())
 fun saveMobilityHabit(mode:String,habitual:Boolean){require(mode in modes);store.put("companion.mobility-habits.v1",obj("version" to 1,"updated_at" to now(),"answers" to mobilityHabits().copyJson().put(mode,habitual)));revision++}
 fun confirmMobilityOptions(){
  require(modes.all {mobilityHabits().opt(it) is Boolean})
  // Unknown access stays included unless the user explicitly excludes it in route options.
  val available=availability().copyJson();modes.forEach {if(available.opt(it) !is Boolean)available.put(it,true)}
  require(modes.any {available.optBoolean(it)})
  saveAvailability(available);goStep("goals")
 }
 fun adjustGoal(key:String,delta:Int){require(key in goalKeys&&delta in listOf(-1,1));val p=plan();val points=p.getJSONObject("points");val next=points.optInt(key)+delta;if(next in 1..7){points.put(key,next);store.put(dailyKey(today(),"draft"),p);revision++}}
 fun rateGoal(key:String,rating:Int){val next=editedDailyGoal(plan(),key,rating);store.put(dailyKey(today(),"draft"),next);revision++}
 fun saveGoals(){val p=plan();needImportanceRatings(p.getJSONObject("points"));p.put("confirmedAt",now()).put("timezone",java.time.ZoneId.systemDefault().id);store.put(dailyKey(today(),"saved"),p);store.remove(dailyKey(today(),"draft"));store.remove("imiq.experimental.adaptive-profile.v1");goStep("valences")}
 fun rateFeeling(mode:String,value:Int,index:Int){val answers=valences();answers.put(mode,value.coerceIn(-3,3));store.put("imiq.experimental.valences-draft.v1",obj("version" to 1,"index" to index,"answers" to answers));revision++}
 fun saveFeelings(){val answers=valences();require(modes.all { answers.opt(it) is Int&&answers.getInt(it) in -3..3 });store.put("imiq.experimental.valences.v1",obj("version" to 1,"index" to 0,"answers" to answers));goStep("beliefs");prepareBeliefs()}
 fun onboardingInputs():JSONObject {
  val needs=needImportanceRatings(plan().getJSONObject("points"))
  val feelings=valences();require(modes.all { !feelings.isNull(it) })
  val agent=store.get("imiq.experimental.agent-id.v1")?:"exp-${UUID.randomUUID()}".also { store.put("imiq.experimental.agent-id.v1",it) }
  return obj("schema_version" to "cognitive_passport_5goals_onboarding_v1","agent_id" to agent,"responses" to obj("needs" to needs,"valences" to feelings))
 }
 fun fingerprint()=canonical(obj("localDate" to today(),"payload" to onboardingInputs()))
 fun run(routeSearch:Boolean=false,block:suspend ()->Unit){if(busy)return;error=null;busy=true;routeSearching=routeSearch;val version=++operationVersion;operation=viewModelScope.launch { try{block()}catch(e:CancellationException){throw e}catch(e:Exception){if(version==operationVersion)error=e.message?:text("Connection failed","Verbindung fehlgeschlagen")}finally{if(version==operationVersion){busy=false;routeSearching=false}} }}
 fun cancel(){operationVersion++;operation?.cancel();busy=false;routeSearching=false;notice=text("Cancelled","Abgebrochen")}
 fun prepareBeliefs(force:Boolean=false)=run {
  val inputs=onboardingInputs();val initialFingerprint=fingerprint();val cached=store.json("imiq.experimental.adaptive-beliefs.v1")
  if(!force&&cached?.optString("fingerprint")==fingerprint()){beliefStart=cached.getJSONObject("start");beliefAnswers=cached.getJSONObject("answers");beliefIndex=cached.optInt("index")}else{
   val start=api.call("/api/companion/adaptive-passport/start",inputs)
   require(initialFingerprint==fingerprint()) { "Your answers changed. Review your goals and feelings." }
   require(start.optString("stage")=="questions"&&start.objects("questions").size==20&&start.objects("questions").map {it.optString("cell_id")}.distinct().size==20&&start.optString("agent_id")==inputs.getString("agent_id")&&start.objects("questions").all {it.optString("hotco_mode") in modes&&it.optString("model_need") in needKeys&&it.optString("cell_id")=="${it.optString("hotco_mode")}__${it.optString("model_need")}"&&it.optJSONObject("response_scale")?.optInt("minimum")==1&&it.optJSONObject("response_scale")?.optInt("maximum")==7}) { "Invalid calibration response" }
   beliefStart=start;beliefAnswers=JSONObject();beliefIndex=0;persistBeliefs()
  }
 }
 fun persistBeliefs(){val start=beliefStart?:return;store.put("imiq.experimental.adaptive-beliefs.v1",obj("fingerprint" to fingerprint(),"start" to start,"answers" to beliefAnswers,"index" to beliefIndex));revision++}
 fun rateBelief(cell:String,rating:Int){if(busy)return;beliefAnswers=beliefAnswers.copyJson().put(cell,rating.coerceIn(1,7));persistBeliefs()}
 fun beliefNext(){if(busy)return;if(beliefIndex<(beliefStart?.objects("questions")?.lastIndex?:19)){beliefIndex++;persistBeliefs()}else run {
  val start=beliefStart?:error("Missing questions");require(start.objects("questions").size==20&&start.objects("questions").all { val cell=it.getString("cell_id");beliefAnswers.opt(cell) is Int&&beliefAnswers.getInt(cell) in 1..7 })
  val initialFingerprint=fingerprint()
  val submission=obj("schema_version" to start.contractString("schema_version"),"start" to start.copyJson(),"answers" to beliefAnswers.copyJson())
  val source=obj("onboarding_request" to onboardingInputs(),"calibration_start_response" to start.copyJson(),"calibration_completion_request" to submission.copyJson())
  val attempt=QuestionnaireTiming.complete(questionnaireAttempt(),canonical(source),questionnaireClock()).put("source",source)
  database.put(QUESTIONNAIRE_ATTEMPT,attempt)
  val completed=api.call("/api/companion/adaptive-passport/complete",submission)
  require(initialFingerprint==fingerprint()) { "Your answers changed. Review your goals and feelings." }
  require(database.json(QUESTIONNAIRE_ATTEMPT)?.optString("questionnaire_attempt_id")==attempt.getString("questionnaire_attempt_id")) {"The questionnaire attempt changed"}
  require(completed.optString("stage")=="complete"&&completed.optBoolean("ready_for_hotco")) { "Invalid calibration completion" }
  attempt.getJSONObject("source").put("calibration_completion_response",completed.copyJson());database.put(QUESTIONNAIRE_ATTEMPT,attempt)
  store.put("imiq.experimental.adaptive-profile.v1",obj("version" to 1,"fingerprint" to fingerprint(),"response" to completed,
   "questionnaire_attempt_id" to attempt.getString("questionnaire_attempt_id"),"questionnaire_submission_signature" to attempt.getString("submission_signature")))
  createPassportInternal()
  require(canonical(beliefAnswers)==canonical(submission.getJSONObject("answers"))) {"Your connections changed. Confirm your answers again."}
  confirmPassport()
 }}
 private suspend fun createPassportInternal(){
  val completed=store.json("imiq.experimental.adaptive-profile.v1")?:error("Complete your twenty connections first")
  require(completed.optString("fingerprint")==fingerprint()) { "Your answers changed. Review your connections." }
  val available=availability();require(modes.all { available.opt(it) is Boolean }&&modes.any { available.optBoolean(it) })
  val initialFingerprint=fingerprint()
  val attempt=questionnaireAttempt()
  if(attempt.has("submission_signature"))require(completed.optString("questionnaire_attempt_id")==attempt.getString("questionnaire_attempt_id")&&
   completed.optString("questionnaire_submission_signature")==attempt.getString("submission_signature")) {"Complete the latest questionnaire submission first"}
  val request=obj("schema_version" to "hotco_5goals_bootstrap_v1","completed_passport" to completed.getJSONObject("response"),"availability" to available,"weekly_goals" to explicitWeeklyReference(needImportanceRatings(plan().getJSONObject("points"))))
  val value=api.call("/api/companion/adaptive-passport/bootstrap",request)
  val responseReceivedAt=now()
  require(initialFingerprint==fingerprint()&&canonical(available)==canonical(availability())) { "Your answers changed. Create the Passport again." }
  require(database.json(QUESTIONNAIRE_ATTEMPT)?.optString("questionnaire_attempt_id")==attempt.getString("questionnaire_attempt_id")) {"The questionnaire attempt changed"}
  val p=value.optJSONObject("cognitive_passport")?:error("Invalid Passport response")
  require(p.optContractString("schema_version")=="2.0"&&p.optJSONObject("profile")?.optJSONObject("beliefs")!=null)
  val received=QuestionnaireTiming.received(attempt,responseReceivedAt)
  val source=received.optJSONObject("source")?:JSONObject()
  source.put("bootstrap_request",request.copyJson());received.put("source",source);database.put(QUESTIONNAIRE_ATTEMPT,received)
  pendingPassport=value
  pendingFingerprint=initialFingerprint
  pendingQuestionnaireAttemptId=received.getString("questionnaire_attempt_id")
 }
 private fun confirmPassport(){
  try {
  val response=pendingPassport?:return;val p=plan();val available=availability();val feelings=obj("answers" to valences())
  require(pendingFingerprint==fingerprint()) { "Your answers changed. Create the Passport again." }
  val attempt=questionnaireAttempt();require(pendingQuestionnaireAttemptId==attempt.getString("questionnaire_attempt_id")) {"Create the Passport again for this questionnaire"}
  val confirmedAt=now();val measured=QuestionnaireTiming.confirmed(attempt,confirmedAt)
  val source=obj("plan" to p,"valences" to feelings,"availability" to available,"habitual_mobility" to (store.json("companion.mobility-habits.v1")?:JSONObject.NULL),"toleranceState" to obj("answers" to JSONObject()),"inputs" to obj("weekStart" to week()))
  val saved=obj("version" to 1,"fingerprint" to fingerprint(),"confirmedAt" to now(),"createdAt" to now(),"weekStart" to week(),"goalRatings" to p.getJSONObject("points"),"needsSource" to NEED_IMPORTANCE_SCALE,"conversion" to "rating_minus1_div6_v1","availability" to available,"sourceSnapshot" to source,"response" to response)
  saved.put("confirmedAt",confirmedAt).put("createdAt",confirmedAt)
   .put("questionnaire_attempt_id",measured.getString("questionnaire_attempt_id"))
   .put("timing",QuestionnaireTiming.snapshot(measured)).put("questionnaire_source",measured.optJSONObject("source")?.copyJson()?:JSONObject.NULL)
  require(validPassport(saved))
  val modelHash=getApplication<Application>().assets.open("companion-identity-v1.json").bufferedReader().use {
   JSONObject(it.readText()).getJSONObject("current_contract_sha256").getString("contracts/hotco-signed-model-v1.json")}
  val db=database.writableDatabase;db.beginTransaction()
  try {database.passport()?.let(database::archive);database.put(PASSPORT,saved);database.put(QUESTIONNAIRE_ATTEMPT,measured);database.queueProfileUpload(saved,modelHash,api.base(),confirmedAt,panelIdentity);database.remove("companion.passport-renewal.v1");db.setTransactionSuccessful()}finally{db.endTransaction()}
  journeyPriorityOverrides=JSONObject();journeyPriorityBasis=null;journeyPriorityConfirmedAt=null;store.remove("imiq.experimental.journey-priorities.v1");pendingPassport=null;goStep("passport");tab="home";notice=text("Your Passport is ready.","Dein Passport ist bereit.");startProfileUploads()
  }catch(e:Exception){error=e.message}
 }
 fun beginRenewal(){
  if(store.json("companion.passport-renewal.v1")!=null)return
  val keys=store.storage();val answers=JSONObject()
  // Keep the current companion/kit prefilled, but restore them if the draft is cancelled.
  listOf(COMPANION,"imiq.experimental.companion-draft.v1","imiq.experimental.availability.v1","companion.mobility-habits.v1").forEach {key->answers.put(key,keys.opt(key)?:JSONObject.NULL)}
  keys.keys().asSequence().filter { (it.startsWith("imiq.experimental.weekly-goals.v1.")||it.startsWith("imiq.experimental.daily-goals.v1."))||it in listOf(QUESTIONNAIRE_ATTEMPT,ONBOARDING,"imiq.experimental.valences.v1","imiq.experimental.valences-draft.v1","imiq.experimental.adaptive-beliefs.v1","imiq.experimental.adaptive-profile.v1") }.toList().forEach { key->answers.put(key,keys.getString(key));store.remove(key) }
  store.put("companion.passport-renewal.v1",obj("version" to 1,"answers" to answers));tab="passport";goStep("identity")
 }
 fun cancelRenewal(){val answers=store.json("companion.passport-renewal.v1")?.optJSONObject("answers")?:return;val current=store.storage();current.keys().asSequence().filter { (it.startsWith("imiq.experimental.weekly-goals.v1.")||it.startsWith("imiq.experimental.daily-goals.v1."))||it in listOf(QUESTIONNAIRE_ATTEMPT,ONBOARDING,"imiq.experimental.valences.v1","imiq.experimental.valences-draft.v1","imiq.experimental.adaptive-beliefs.v1","imiq.experimental.adaptive-profile.v1") }.toList().forEach(store::remove);answers.keys().forEach { if(answers.isNull(it))store.remove(it)else store.put(it,answers.getString(it)) };store.remove("companion.passport-renewal.v1");goStep("passport");tab="home"}
 var originParkingId by mutableStateOf<String?>(null)
 var destinationParkingId by mutableStateOf<String?>(null)
 private var parkingReselect=false
 var boardingStopId by mutableStateOf<String?>(null)
 var boardingTripId by mutableStateOf<String?>(null)
 var boardingDepartureMs:Double?=null
 private var boardingPlanTime:String?=null
 private var transitReselect=false
 fun planStopItinerary(stop:MobilityPoi,route:JSONObject){
  if(busy)return
  val board=route.objects("legs").firstOrNull {it.optString("mode")=="pt"}?.optJSONObject("transit")?:return
  val stopId=board.optJSONObject("from")?.optJSONObject("stop")?.optString("gtfsId")?:return
  if(stopId !in stop.gtfsIds)return
  boardingStopId=stopId;boardingTripId=board.optString("gtfs_trip_id");boardingDepartureMs=board.number("scheduled_start_time_ms")
  // The plan window belongs to the timetable; context remains captured now.
  boardingPlanTime=java.time.Instant.ofEpochMilli(maxOf(System.currentTimeMillis(),(route.optJSONObject("transit")?.number("start_time_ms")?.toLong()?:System.currentTimeMillis())-30000)).toString()
  transitReselect=true;search()
 }
 fun changeParking(start:Boolean,id:String?){if(busy)return;if(start)originParkingId=id else destinationParkingId=id;parkingReselect=true;search()}
 fun setPlace(start:Boolean,place:Place?){
  val old=if(start)origin else destination
  if(old?.lat!=place?.lat||old?.lon!=place?.lon){journeyPriorityOverrides=JSONObject();journeyPriorityBasis=null;journeyPriorityConfirmedAt=null;database.remove("imiq.experimental.journey-priorities.v1")}
  if(start){origin=place;originParkingId=null}else{destination=place;destinationParkingId=null};boardingStopId=null;boardingTripId=null;boardingDepartureMs=null;boardingPlanTime=null;result=null;selectedId=null;chosenId=null;reflection=null;routeView="search"}
 suspend fun findPlaces(query:String,bias:Place?):List<Place>{
  require(query.trim().length in 3..240)
  val language=locale
  val key="${api.base()}:$language:${bias?.lat}:${bias?.lon}:${query.trim().lowercase()}"
  geocodingCache[key]?.takeIf {System.currentTimeMillis()-it.first<300000}?.let {return it.second}
  geocodingGate.withLock {delay((nextGeocodingStart-System.currentTimeMillis()).coerceAtLeast(0));nextGeocodingStart=System.currentTimeMillis()+1000}
  val suffix="?q=${URLEncoder.encode(query.trim(),"UTF-8")}&lang=$language&lat=${bias?.lat?:52.13}&lon=${bias?.lon?:11.62}"
  val places=addressSearch(query,
   backend={api.call("/api/companion/geocoding$suffix",timeout=4000).objects("features").mapNotNull(::placeFromFeature)},
   device={deviceAddressSearch(getApplication(),query.trim(),language,bias)})
  geocodingCache[key]=System.currentTimeMillis() to places
  if(geocodingCache.size>50)geocodingCache.remove(geocodingCache.keys.first())
  return places
 }
 private fun tripImportanceContext():String {
  val item=passport()?:throw IllegalStateException("Confirm your Passport first")
  val profile=item.getJSONObject("response").getJSONObject("cognitive_passport")
  val start=origin?:throw IllegalStateException("Select an origin suggestion")
  val stop=destination?:throw IllegalStateException("Select a destination suggestion")
  return canonical(obj("profile" to profile.getJSONObject("profile"),"lineage" to profile.getJSONObject("lineage"),
   "weekly" to weeklyGoalReference(item),"start" to start.json(),"stop" to stop.json(),"language" to locale,
   "settings" to settings(),"vehicle" to database.json(VEHICLE_PROFILE),"origin_parking" to originParkingId,
   "destination_parking" to destinationParkingId,"boarding_stop" to boardingStopId,"boarding_trip" to boardingTripId,
   "boarding_departure" to boardingDepartureMs,"boarding_plan_time" to boardingPlanTime))
 }
 /** Every new search, including route refreshes, must obtain fresh explicit ratings. */
 fun search(){
  if(busy||pendingTripImportance!=null)return
  runCatching {
   val context=tripImportanceContext()
   tripImportanceDraft=TripImportanceDraft()
   pendingTripImportance=PendingTripImportance(UUID.randomUUID().toString(),context,now(),locale)
   error=null
  }.onFailure {error=it.message}
 }
 internal fun updateTripImportance(draft:TripImportanceDraft){if(pendingTripImportance!=null&&!busy)tripImportanceDraft=draft}
 internal fun dismissTripImportance(){pendingTripImportance=null;tripImportanceDraft=TripImportanceDraft();parkingReselect=false;transitReselect=false}
 internal fun confirmTripImportance(sessionId:String){
  val pending=pendingTripImportance?:return
  if(pending.id!=sessionId||busy||!tripImportanceDraft.complete||!tripImportanceDraft.reviewing)return
  val observation=runCatching {
   require(pending.contextKey==tripImportanceContext()) {text("Trip or profile changed. Please rate the goals again.","Reise oder Profil geändert. Bitte Ziele erneut bewerten.")}
   tripImportanceDraft.observation(pending.language,pending.openedAt,now())
  }.getOrElse {dismissTripImportance();error=it.message;return}
  val confirmation=ConfirmedTripImportance(pending.contextKey,tripImportanceDraft.ratings(),observation)
  // Consume the session before any network work, so duplicate taps cannot submit twice.
  pendingTripImportance=null;tripImportanceDraft=TripImportanceDraft()
  searchInternal(confirmation)
 }
 private fun searchInternal(confirmation:ConfirmedTripImportance)=run(routeSearch=true) {
  val previous=passport()?:error("Confirm your Passport first")
  val uploadIdentity=panelIdentity?.copyJson();val hash=modelContractHash();val target=api.base()
  val weekly=weeklyGoalReference(previous)
  require(confirmation.contextKey==tripImportanceContext()) {"Trip or profile changed. Rate the goals again."}
  // Keep every explicit response, including those equal to the weekly reference.
  val edits=confirmation.ratings.copyJson();val adjustedAt=confirmation.observation.getString("confirmed_at")
  require(edits.keys().asSequence().toSet()==needKeys.toSet())
  effectiveGoalRatings(weekly,edits)
  val start=origin?:error("Select an origin suggestion");val stop=destination?:error("Select a destination suggestion")
  val prepared=api.call("/api/companion/goal-personalization/prepare",priorityRequest(previous,weekly,edits,adjustedAt))
  require(confirmation.contextKey==tripImportanceContext()) {"Trip or profile changed. Rate the goals again."}
  val active=validatedPriorityPassport(previous,weekly,edits,adjustedAt,prepared)
  val p=active.getJSONObject("response").getJSONObject("cognitive_passport")
  require(canonical(p.getJSONObject("goal_priorities").getJSONObject("effective_ratings"))==canonical(confirmation.ratings))
  val available=settings().optJSONObject("journeyAvailability")?:p.getJSONObject("profile").getJSONObject("availability")
  require(modes.any { available.optBoolean(it) }) { "Enable at least one transport mode" }
  val id="route-${UUID.randomUUID()}";val request=obj("search_id" to id,"datetime" to now(),"cognitive_passport" to p,"journey_availability" to available,"start" to start.coordinates(),"stop" to stop.coordinates(),"max_walk_m" to settings().optInt("maxWalk",1500),"tolerance_profile" to JSONObject(),"record_trajectories" to true,"context_policy" to "observations_only","required_model_version" to "hotco_signed_grossberg_5goals_v1","required_readout_version" to "signed-actions-v1","contextual_query" to obj("query_orion" to true,"entity_families" to JSONArray(listOf("weather")),"weather_radius_meters" to 5000))
  val pois=withContext(Dispatchers.IO){MagdeburgMobility.load(getApplication())}
  request.put("climate_profile",database.json(VEHICLE_PROFILE)?.copyJson()?:JSONObject.NULL)
  val parkingSupported=nearestPublicParking(pois,GeoPoint(start.lat,start.lon))!=null&&nearestPublicParking(pois,GeoPoint(stop.lat,stop.lon))!=null
  if(available.optBoolean("car")&&parkingSupported)request.put("car_parking",obj("policy" to "nearest-public-osm-magdeburg-v1","origin_id" to originParkingId,"destination_id" to destinationParkingId))
  val keepCar=parkingReselect;parkingReselect=false
  val keepTransit=transitReselect;transitReselect=false
  if(boardingStopId!=null)request.put("boarding_stop_id",boardingStopId).put("boarding_trip_id",boardingTripId).put("boarding_departure_ms",boardingDepartureMs).put("boarding_plan_datetime",boardingPlanTime)
  val lineage=p.getJSONObject("lineage");val record=obj("version" to 1,"app_version" to BuildConfig.VERSION_NAME,"comfort_pilot_required" to true,"affect_pilot_required" to true,"goal_priorities_reference" to p.getJSONObject("goal_priorities").copyJson(),"recommendation_rule" to "signed-actions-unanimous-clear-mode-v3","search_id" to id,"created_at" to now(),"status" to "pending","request" to request,"places" to obj("origin" to start.json(),"destination" to stop.json()),"passport_reference" to obj("passport_id" to lineage.getString("passport_id"),"revision" to lineage.getInt("revision"),"fingerprint" to active.getString("fingerprint"),"confirmed_at" to active.optString("confirmedAt")),"confirmed_snapshot" to active,"choices" to JSONArray())
  record.put("situated_importance_required",true).put("situated_importance",confirmation.observation.copyJson())
  val forecastSource=withContext(Dispatchers.IO){database.archive(previous);database.archive(active);runCatching {freezeProspectiveSource(database,active)}.getOrElse {record.put("prospective_source_failure",it.message?.take(300));null}}
  withContext(Dispatchers.IO){
   val db=database.writableDatabase;db.beginTransaction()
   try {database.archive(active);database.saveRecord(record)
    database.queuePriorityUpload("predecision_ema",previous,active,record,uploadIdentity,target,record.getString("created_at"),hash)
    db.setTransactionSuccessful()
   }finally{db.endTransaction()}
  }
  startPriorityUploads();routeProfileSnapshot=id to p;historyId=id;result=null;chosenId=null;reflection=null
  var failureStage="routing_request"
  try {
   val response=api.call("/api/companion/routed-contextual-deliberation",request,180000)
   record.put("result_received_at",now())
   failureStage="response_validation"
   require(response.optContractString("schema_version")=="routed-contextual-deliberation-v1"&&response.optString("search_id")==id&&response.optJSONObject("routing")?.optJSONArray("routes")!=null&&response.optJSONArray("route_audit")!=null) { "Invalid route response" }
   require(modes.all { response.optJSONObject("journey_availability")?.opt(it)==available.opt(it) }) { "Availability changed in response" }
   val simulationVersion=response.optJSONObject("contextual_deliberation")?.optContractString("model_version")
   require(simulationVersion==null||simulationVersion=="hotco_signed_grossberg_5goals_v1") {"The backend model needs updating before a new search can be saved."}
   val readoutVersion=response.optJSONObject("contextual_deliberation")?.optJSONObject("baseline")?.optString("readout_version")
   require(simulationVersion==null||readoutVersion=="signed-actions-v1") {"The backend needs the direct signed output update."}
   record.put("action_readout_version",readoutVersion?:JSONObject.NULL)
   record.put("simulation_model_version",simulationVersion?:JSONObject.NULL).put("input_passport_model_version",p.opt("version")?:JSONObject.NULL)
   failureStage="recommendation"
   val recommendation=withContext(Dispatchers.Default){recommendation(response)}
   withContext(Dispatchers.IO){
    record.put("status","complete").put("completed_at",now()).put("result",response).put("recommendation",recommendation.json())
    failureStage="forecast"
    forecastBeforeChoice(record,forecastSource,{api.call("/api/companion/longitudinal/prospective/forecast",it,30000)})
    failureStage="local_commit"
    record.put("search_termination",obj("status" to "complete","failure" to null))
    database.commitSearchTerminal(record,uploadIdentity,target)
   }
   startSearchUploads();startResultsUploads();startHotcoUploads()
   result=response;activeMode=if(keepTransit)"pt"else if(keepCar)"car"else recommendation.winner?:cards(response).firstOrNull()?.mode?:"walk";selectedId=if(keepTransit)cards(response).firstOrNull {it.mode=="pt"&&it.route.optBoolean("available")}?.id else if(keepCar)cards(response).firstOrNull {it.mode=="car"}?.id else recommendation.primary?.id;routeView="alternatives"
   if(keepTransit&&selectedId==null)error=text("That departure can no longer be planned. Open the stop to refresh its itineraries.","Diese Abfahrt kann nicht mehr geplant werden. Öffne die Haltestelle, um Verbindungen zu aktualisieren.")
   // Results are durable and ready to interact with. Auxiliary persistence
   // must not keep the loading screen visible or turn a complete search failed.
   routeSearching=false
   viewModelScope.launch {
    runCatching {withContext(Dispatchers.IO){val recent=JSONArray();(listOf(start,stop)+recents()).distinctBy { it.lat to it.lon }.take(6).forEach {recent.put(it.json())};database.put("companion.recent-places.v1",recent)}}
     .onFailure {android.util.Log.w("CompanionRouteSearch","Recent places could not be saved",it)}
   };revision++
  }catch(e:Exception){withContext(NonCancellable+Dispatchers.IO){record.put("status","failed").put("completed_at",now()).put("failure",if(e is CancellationException)"cancelled" else e.message)
   record.put("search_termination",SearchUpload.termination(failureStage,e));database.commitSearchTerminal(record,uploadIdentity,target)};startSearchUploads();throw e}
 }
 fun recents():List<Place> { val raw=store.get("companion.recent-places.v1")?:return emptyList();return runCatching { val parsed=JSONArray(raw);parsed.objects().mapNotNull(Place::fromJson) }.getOrDefault(emptyList()) }
 fun choose(card:RouteCard,onSelected:((String,Int)->Unit)?=null)=run {
  if(store.json(ACTIVE_PLAYBACK)!=null){openPlayback();return@run}
  require(card.route.optBoolean("available")&&!card.expired()) {"Departure has passed; search again"}
  val id=historyId?:error("Journey not found")
  val selection=withContext(Dispatchers.IO){
   val active=trips.active()
   if(active!=null)return@withContext Triple(active.first.getString("search_id"),-1,active.second.getString("journey_id"))
   val choice=obj("chosen_at" to now(),"route_id" to card.audit?.opt("route_id"),"routing_rank" to card.route.getInt("rank"),"mode_key" to card.route.getString("mode_key"),"summary" to card.route.optJSONObject("summary"),"choice_event_id" to "choice-${UUID.randomUUID()}")
   if(card.route.optString("provider")=="otp")listOf("provider","provider_itinerary_id","transit","legs").forEach {choice.put(it,card.route.opt(it))}
   var index=-1
   database.mutateRecord(id){record->
    requireProspectiveReadyForChoice(record,choice.getString("chosen_at"))
    require(record.optString("status")=="complete"&&record.optJSONObject("result")?.optJSONObject("routing")?.objects("routes")?.any {it.optInt("rank")==card.route.optInt("rank")&&it.optString("mode_key")==card.route.optString("mode_key")&&it.optBoolean("available")}==true)
    index=record.getJSONArray("choices").length();record.getJSONArray("choices").put(choice)
   }
   Triple(id,index,null)
  }
  if(selection.third!=null){openTrip(selection.first,selection.third);return@run}
  chosenId=card.id;selectedId=card.id;activeMode=card.mode;revision++
  if(onSelected==null)routeView="preview" else onSelected(selection.first,selection.second)
 }
 fun loadReflection(force:Boolean=false){
  narration?.cancel();val version=++narrationVersion;reflection=null;reflectionError=null;reflectionBusy=true
  val id=historyId
  if(id==null){reflectionBusy=false;return}
  val language=locale
  narration=viewModelScope.launch { try {
   val record=withContext(Dispatchers.IO){store.recordForReflection(id)}?:error("Journey not saved");val index=record.getJSONArray("choices").length()-1
   val existing=if(force)null else record.objects("explanations").lastOrNull { it.optInt("choice_index")==index&&it.optString("language")==language }?.optJSONObject("response")
   val retryNarration=existing?.optJSONObject("narration")?.optString("status")=="TEMPLATE_FALLBACK"
   val value=if(existing==null||retryNarration)api.call("/api/companion/choice-explanation",obj("record" to record,"choice_index" to index,"confirmed_snapshot" to (store.confirmedSnapshot(record)?:error("The original Passport for this journey is unavailable")),"language" to language),30000)else existing
   require(validReflection(value,record,index,language,getApplication<Application>().assets.open("choiceWordings.json").bufferedReader().use { JSONObject(it.readText()) })) { "Unverified explanation" }
   if(existing==null||retryNarration)withContext(Dispatchers.IO){store.saveExplanation(id,index,language,value)}
   if(version==narrationVersion)reflection=value
  }catch(e:CancellationException){throw e}catch(e:Exception){if(version==narrationVersion)reflectionError=e.message}finally{if(version==narrationVersion)reflectionBusy=false} }
 }
 fun loadCompanionReflection(force:Boolean=false) {
  companionNarration?.cancel();val version=++companionNarrationVersion
  companionReflection=null;companionReflectionFailed=false;companionReflectionBusy=true
  val language=locale
  companionNarration=viewModelScope.launch {try {
   val request=withContext(Dispatchers.IO){companionRequest(store,language)}
   val key="$COMPANION_REFLECTION.$language"
   val cached=if(force)null else withContext(Dispatchers.IO){store.json(key)}?.takeIf {validCompanionReflection(it,request)}
   val response=cached?:api.call("/api/companion/companion-reflection",request,30000)
   require(validCompanionReflection(response,request)) {"Unverified companion reflection"}
   if(version==companionNarrationVersion){
    if(cached==null)withContext(Dispatchers.IO){store.put(key,response)}
    companionReflection=response
   }
  }catch(e:CancellationException){throw e}catch(e:Exception){if(version==companionNarrationVersion)companionReflectionFailed=true}
   finally {if(version==companionNarrationVersion)companionReflectionBusy=false}
  }
 }
 fun openHistory(record:JSONObject){if(record.optString("status")!="complete")return;result=record.getJSONObject("result");historyId=record.getString("search_id");routeProfileSnapshot=record.optJSONObject("request")?.optJSONObject("cognitive_passport")?.let {record.getString("search_id") to it};origin=Place.fromJson(record.getJSONObject("places").getJSONObject("origin"));destination=Place.fromJson(record.getJSONObject("places").getJSONObject("destination"));val choice=record.objects("choices").lastOrNull();chosenId=choice?.let { "${it.optInt("routing_rank")}-${it.optString("mode_key")}" };selectedId=chosenId;activeMode=cards(result).find { it.id==chosenId }?.mode?:cards(result).firstOrNull()?.mode?:"walk";tab="journey";routeView=if(choice==null)"alternatives" else "preview"}
 fun saveSettings(value:JSONObject){val max=value.optInt("maxWalk",1500);require(max in 0..10000);store.put(SETTINGS,value);revision++}
 fun openTrip(searchId:String,journeyId:String?=null){tripSearchId=searchId;tripId=journeyId;tab="journey";routeView="trip"}
 var pendingGpsStart:Pair<String,Int>?=null
 var noGpsJourney by mutableStateOf<Pair<String,Int>?>(null)
 fun startWithoutGps(searchId:String,index:Int){
  runCatching {
   require(trips.active()==null){text("Finish the active journey first","Beende zuerst die aktive Reise")}
   val existing=store.json(ACTIVE_PLAYBACK)
   if(existing==null)store.put(ACTIVE_PLAYBACK,JourneyPlaybackClock.create(searchId,index,android.os.SystemClock.elapsedRealtime(),trips.boot()))
   TrackingService.action(getApplication(),TrackingService.PLAYBACK)
   openPlayback()
  }.onFailure {error=it.message}
 }
 fun openPlayback(){val p=store.json(ACTIVE_PLAYBACK)?:return;noGpsJourney=p.getString("search_id") to p.getInt("choice_index");tab="journey";routeView="without_gps";tripStartingSearchId=null;error=null}
 fun cancelPlayback(){if(manualTripSaving)return;TrackingService.action(getApplication(),TrackingService.CANCEL);noGpsJourney=null;routeView="alternatives"}
 fun finishPlayback(){if(manualTripSaving)return;val p=store.json(ACTIVE_PLAYBACK)?:return;prepareManualTrip(p.getString("search_id"),p.getInt("choice_index"),true)}
 fun openingTracking(searchId:String){tripStartingSearchId=searchId;openTrip(searchId)}
 var experienceSaved by mutableStateOf(false)
 fun returnAfterExperience() {
  narration?.cancel();narrationVersion++
  tripSearchId=null;tripId=null;historyId=null
  result=null;selectedId=null;chosenId=null;reflection=null;reflectionBusy=false;reflectionError=null
  notice=null;error=null;tab="journey";routeView="search"
  experienceSaved=true;revision++
 }
 fun clearJourneyHistory(){runCatching {store.clearHistory();revision++;result=null;historyId=null;tripSearchId=null;tripId=null;notice=text("History cleared","Verlauf gelöscht")}.onFailure {error=it.message}}
 fun resetLocalData(){runCatching {profileUploadJob?.cancel();priorityUploadJob?.cancel();searchUploadJob?.cancel();resultsUploadJob?.cancel();hotcoUploadJob?.cancel();store.reset();refresh();tab="home";result=null;historyId=null;tripSearchId=null;tripId=null}.onFailure {error=it.message}}
 var manualTripSaving by mutableStateOf(false)
  private set
 private var preparedTripRecord:Pair<String,JSONObject>?=null
 internal fun takePreparedTripRecord(searchId:String?):JSONObject? {
  val saved=preparedTripRecord?.takeIf {it.first==searchId}?:return null
  preparedTripRecord=null;return saved.second
 }
 fun manualTrip(searchId:String,index:Int){prepareManualTrip(searchId,index,false)}
 private fun prepareManualTrip(searchId:String,index:Int,finishingPlayback:Boolean) {
  if(manualTripSaving)return
  manualTripSaving=true;error=null
  viewModelScope.launch {
   try {
    // Keep the playback view available while the durable manual journey is
    // prepared. Never parse/validate/write a saved search on the UI thread.
    if(finishingPlayback)TrackingService.action(getApplication(),TrackingService.PAUSE)
    val (record,trip)=withContext(Dispatchers.IO){trips.prepareManual(searchId,index)}
    preparedTripRecord=searchId to record
    if(finishingPlayback){TrackingService.action(getApplication(),TrackingService.FINISH);noGpsJourney=null}
    openTrip(searchId,trip.getString("journey_id"))
   }catch(e:CancellationException){throw e}catch(e:Exception){error=e.message}
   finally {manualTripSaving=false}
  }
 }
 fun validateTrip(record:JSONObject,trip:JSONObject)=run {
  val fingerprint=withContext(Dispatchers.IO){canonical(trip)}
  val minimized=withContext(Dispatchers.IO){store.recordForJourneyValidation(record.getString("search_id"))?:error("Journey not saved")}
  val receipt=api.call("/api/companion/journeys/validate",obj("record" to minimized,"journey" to trip),30000)
  val stored=validateJourneyReceipt(receipt,trip)
  store.put("imiq.experimental.journey-validation.v1.${trip.getString("journey_id")}",obj("fingerprint" to fingerprint,"receipt" to receipt));revision++;notice=if(stored)text("Journey saved on backend","Reise im Backend gespeichert")else text("Journey contract validated; not saved on backend","Reisevertrag validiert; nicht im Backend gespeichert")
 }
 fun openSavedAlternatives(searchId:String)=run {
  val original=withContext(Dispatchers.IO){store.recordForUi(searchId)}?:error("Journey not saved")
  openHistory(original);routeView="alternatives"
 }
}

data class Place(val lat:Double,val lon:Double,val label:String,val id:String,val house:String="",val street:String="",val detail:String="",val source:String="geocoding",val accuracy:Float?=null) {
 fun coordinates()=obj("lat" to lat,"lon" to lon)
 fun json()=obj("lat" to lat,"lon" to lon,"label" to label,"id" to id,"houseNumber" to house,"street" to street,"detail" to detail,"source" to source,"accuracy_meters" to accuracy)
 companion object { fun fromJson(value:JSONObject):Place? { val lat=value.number("lat")?:return null;val lon=value.number("lon")?:return null;if(kotlin.math.abs(lat)>90||kotlin.math.abs(lon)>180)return null;return Place(lat,lon,value.optString("label"),value.optString("id"),value.optString("houseNumber"),value.optString("street"),value.optString("detail"),value.optString("source","geocoding"),value.number("accuracy_meters")?.toFloat()) } }
}
fun placeFromFeature(feature:JSONObject):Place? {
 val geometry=feature.optJSONObject("geometry")?:return null;if(geometry.optString("type")!="Point")return null
 val c=geometry.optJSONArray("coordinates")?:return null;val p=feature.optJSONObject("properties")?:return null
 val lon=c.optDouble(0);val lat=c.optDouble(1);if(!lat.isFinite()||!lon.isFinite()||kotlin.math.abs(lat)>90||kotlin.math.abs(lon)>180)return null
 val street=p.optString("street");val house=p.optString("housenumber");val city=p.optString("city");val name=p.optString("name");val address=listOf(street,house).filter { it.isNotEmpty() }.joinToString(" ")
 val label=listOf(name,address,city).filter { it.isNotEmpty() }.distinct().joinToString(", ");if(label.isEmpty())return null
 return Place(lat,lon,label,p.optString("osm_type")+p.optString("osm_id"),house,street,listOf(p.optString("postcode"),city).filter { it.isNotEmpty() }.joinToString(" "))
}
