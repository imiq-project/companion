package de.ovgu.imiq.companion

import org.json.JSONObject
import kotlin.math.abs

internal data class PriorityDelta(val need:String,val saved:Int,val current:Int) {
 val direction get()=current.compareTo(saved)
}
internal fun priorityDeltas(saved:JSONObject,current:JSONObject):List<PriorityDelta> = needKeys.mapNotNull {key->
 val a=saved.opt(key);val b=current.opt(key)
 if(a !is Int||b !is Int||a !in 1..7||b !in 1..7)null else PriorityDelta(key,a,b)
}.sortedWith(compareByDescending<PriorityDelta> {it.direction!=0}.thenByDescending {it.current}.thenBy {needKeys.indexOf(it.need)})

/** Same state-weighted signed-constraint diagnostic as passport_v2._active_tension.
 * Full network, not route utility, positive action support, or a probability.
 */
internal fun networkCoherence(summary:JSONObject?):Double? {
 val published=summary?.optJSONObject("process_diagnostics")?.optJSONObject("active_constraint_tension_terminal")?.optJSONObject("total")?.number("congruence")
 if(published!=null&&published.isFinite()&&published in -1.0..1.0)return published
 val evidence=summary?.optJSONObject("simulation_evidence")?:return null
 if(evidence.optContractString("model_version")!="hotco_signed_grossberg_5goals_v1")return null
 val columns=evidence.optJSONArray("state_columns")?:return null
 val expected=needKeys+ modes.map {"action:$it"}+modes.map {"valence:$it"}
 val names=(0 until columns.length()).map {columns.optString(it)}
 if(names.toSet()!=expected.toSet()||names.distinct().size!=expected.size)return null
 val matrix=evidence.optJSONArray("topology")?:return null
 if(matrix.length()!=names.size)return null
 val states=names.map {name->
  val value=when {
   name.startsWith("action:")->summary.optJSONObject("final_action_activations")?.number(name.substringAfter(':'))
   name.startsWith("valence:")->summary.optJSONObject("node_activations")?.optJSONObject("valences")?.optJSONObject("final")?.number(name.substringAfter(':'))
   else->summary.optJSONObject("node_activations")?.optJSONObject("needs")?.optJSONObject("final")?.number(name)
  }?:return null
  if(!value.isFinite()||value !in -1.0000000001..1.0000000001)return null
  value
 }
 var positive=0.0;var negative=0.0
 for(i in names.indices){
  val row=matrix.optJSONArray(i)?:return null
  if(row.length()!=names.size)return null
  for(j in i+1 until names.size){
   val w=(row.opt(j) as? Number)?.toDouble()?:return null
   val reverse=(matrix.optJSONArray(j)?.opt(i) as? Number)?.toDouble()?:return null
   if(!w.isFinite()||!reverse.isFinite()||abs(w-reverse)>1e-10)return null
   val contribution=w*states[i]*states[j]
   if(contribution>0)positive+=contribution else negative-=contribution
  }
 }
 val mass=positive+negative
 return if(mass>0)(positive-negative)/mass else null
}
internal fun signedModeEvaluation(card:RouteCard?):Double? {
 if(card==null)return null
 val summary=card?.candidate?.optJSONObject("hotco")?:card?.basicSimulation?:return null
 return summary.optJSONObject("final_action_activations")?.number(card.mode)?.takeIf {it.isFinite()&&it in -1.0000000001..1.0000000001}
}
internal fun visibleFavourite(assessment:Recommendation,time:Long):RouteCard? = assessment.primary?.takeIf {
 assessment.state=="clear"&&assessment.winner==it.mode&&it.route.optBoolean("available")&&!it.expired(time)
}
