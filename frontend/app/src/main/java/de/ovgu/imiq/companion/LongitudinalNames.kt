package de.ovgu.imiq.companion

import org.json.JSONArray
import org.json.JSONObject

/** Identifier aliases are comparison views; signed documents retain their bytes. */
internal fun longitudinalIdentifier(value:String):String=when {
 value.startsWith("d-day-longitudinal-")->"longitudinal-evidence-"+value.removePrefix("d-day-longitudinal-")
 value.startsWith("d-day-")->"longitudinal-"+value.removePrefix("d-day-")
 else->value
}

internal fun JSONObject.contractString(key:String):String=longitudinalIdentifier(getString(key))
internal fun JSONObject.optContractString(key:String,fallback:String=""):String=longitudinalIdentifier(optString(key,fallback))

private val longitudinalVersionFields=setOf("schema_version","model_version","weather_model_version","digest_encoding","version","projection_version")
internal fun longitudinalContractView(value:Any?,identifier:Boolean=false):Any?=when(value) {
 is JSONObject->JSONObject().also {out->value.keys().forEach {key->
  val item=if(key=="warnings"&&value.opt(key) is JSONArray)JSONArray((value.opt(key) as JSONArray).normalizedWarnings())else value.opt(key)
  out.put(key,longitudinalContractView(item,key in longitudinalVersionFields))
 }}
 is JSONArray->JSONArray((0 until value.length()).map {longitudinalContractView(value.opt(it),identifier)})
 is String->if(identifier)longitudinalIdentifier(value)else value
 else->value
}

internal fun canonicalLongitudinalContract(value:JSONObject):String=canonical(longitudinalContractView(value) as JSONObject)

private fun JSONArray.normalizedWarnings(): List<Any?> = (0 until length()).map {i->
 val item=opt(i);if(item is String)item.replace("D-Day causal assumptions","Longitudinal causal assumptions")else item
}
