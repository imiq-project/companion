package de.ovgu.imiq.companion

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

internal fun experienceDraftKey(journeyId:String)="imiq.experimental.experience-draft.v1.$journeyId"

/** One FIFO writer survives navigation and activity recreation with the model. */
internal class ExperienceDraftWriter(private val store:Store,scope:CoroutineScope) {
 private data class Write(val action:()->Unit,val done:CompletableDeferred<Unit>)
 private val writes=Channel<Write>(Channel.UNLIMITED)
 val failure=MutableStateFlow<String?>(null)
 init {scope.launch(Dispatchers.IO){for(write in writes){
  try {write.action();failure.value=null;write.done.complete(Unit)}
  catch(e:Exception){failure.value=e.message?:"Draft could not be saved";write.done.completeExceptionally(e)}
 }}}
 private fun submit(action:()->Unit):Deferred<Unit> {
  val done=CompletableDeferred<Unit>();check(writes.trySend(Write(action,done)).isSuccess)
  return done
 }
 fun save(journeyId:String,value:JSONObject):Deferred<Unit> {
  val snapshot=value.toString()
  return submit {store.put(experienceDraftKey(journeyId),snapshot)}
 }
 suspend fun load(journeyId:String):JSONObject? {
  submit {}.await()
  return withContext(Dispatchers.IO){store.json(experienceDraftKey(journeyId))}
 }
 suspend fun flush(){submit {}.await()}
}

/** Explicitly confirmed itinerary modes; no GPS inference and no duplicate modes. */
internal fun plannedExperienceModes(record:JSONObject,selection:JSONObject):List<String> {
 val route=record.optJSONObject("result")?.optJSONObject("routing")?.objects("routes")?.singleOrNull {
  it.optInt("rank",-1)==selection.optInt("routing_rank",-2)&&it.optString("mode_key")==selection.optString("mode_key")
 }
 val actual=route?.objects("legs")?.map {modelMode(it.optString("mode"))}?.filter {it in modes}?.distinct().orEmpty()
 return actual.ifEmpty {listOf(modelMode(selection.getString("mode_key")))}
}

internal const val EXPERIENCE_MODE_REPORTING="confirmed-itinerary-modes-v1"
