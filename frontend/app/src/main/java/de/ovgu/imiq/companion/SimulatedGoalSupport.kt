package de.ovgu.imiq.companion

/** Signed need input to an action at the saved terminal state: W[action,need]*need.
 * A solver input component, not causal attribution or measured satisfaction.
 */
internal data class SimulatedGoalContribution(val need:String,val input:Double,val activation:Double,val weight:Double)

internal fun simulatedGoalContributions(card:RouteCard):List<SimulatedGoalContribution>? {
 val hotco=card.candidate?.optJSONObject("hotco")?:card.basicSimulation?:return null
 val evidence=hotco.optJSONObject("simulation_evidence")?:return null
 val columns=evidence.optJSONArray("state_columns")?:return null
 val names=(0 until columns.length()).map {columns.optString(it)}
 if(names.distinct().size!=names.size)return null
 val actionIndex=names.indexOf("action:${card.mode}").takeIf {it>=0}?:return null
 val row=evidence.optJSONArray("topology")?.optJSONArray(actionIndex)?:return null
 val needs=hotco.optJSONObject("node_activations")?.optJSONObject("needs")?.optJSONObject("final")?:return null
 val signed=evidence.optContractString("model_version") in listOf("hotco_signed_grossberg_5goals_v1","hotco_signed_grossberg_v1")
 val lower=if(signed)-1.0 else 0.0
 val links=needKeys.map {need->
  val needIndex=names.indexOf(need).takeIf {it>=0}?:return null
  val value=needs.number(need)?.takeIf {it.isFinite()&&it in lower..1.0}?:return null
  val weight=row.optDouble(needIndex).takeIf {it.isFinite()}?:return null
  SimulatedGoalContribution(need,weight*value,value,weight)
 }
 return links.sortedWith(compareByDescending<SimulatedGoalContribution> {it.input}.thenBy {needKeys.indexOf(it.need)})
}

internal fun strongestGoalSupports(links:List<SimulatedGoalContribution>?)=
 links.orEmpty().filter {it.input>0}.take(3)

internal fun strongestGoalObstacles(links:List<SimulatedGoalContribution>?)=
 links.orEmpty().filter {it.input<0}.sortedBy {it.input}.take(3)
