package de.ovgu.imiq.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

private val vehicleCombiningMarks=Regex("\\p{M}+")
private val vehicleSeparators=Regex("[^a-z0-9]+")
internal fun vehicleSearchKey(text:String):String=Normalizer.normalize(text,Normalizer.Form.NFD)
 .replace(vehicleCombiningMarks,"").lowercase(Locale.ROOT).replace(vehicleSeparators," ").trim()

internal fun vehicleMakeName(value:String):String=when(vehicleSearchKey(value).replace(" ","")) {
 "bmw"->"BMW";"mercedesbenz","mercedes"->"MERCEDES-BENZ";"mg"->"MG";"rollsroyc","rollsroyce"->"ROLLS-ROYCE";"lamborghin"->"LAMBORGHINI"
 else->value.trim()
}

internal fun gbVehicleFuel(value:String):String=when(value) {
 "Petrol","Hybrid electric (petrol)"->"petrol"
 "Diesel","Hybrid electric (diesel)"->"diesel"
 "Battery electric"->"electric"
 "Plug-in hybrid electric (petrol)"->"hybrid_petrol"
 else->"unknown"
}

internal fun usesPersonalVehicleConsumption(profile:JSONObject?):Boolean {
 if(profile==null)return false
 if(profile.has("consumption_mode"))return profile.optString("consumption_mode")=="personal"
 return profile.optString("consumption_basis","user_average")=="user_average"&&
  (profile.number("consumption_l_per_100km")!=null||profile.number("electricity_kwh_per_100km")!=null)
}

internal data class VehicleOption(val id:String,val make:String,val model:String,val variant:String,val fuel:String,
 val fuelLabel:String,val detail:JSONObject?,val source:JSONObject,val searchKey:String) {
 fun snapshot():JSONObject {
  detail?.let {return it.copyJson()}
  val values=obj("fuel" to fuel,"consumption_l_per_100km" to null,"electricity_kwh_per_100km" to null,
   "homologated_co2_g_per_km" to null,"homologation_protocol" to "unknown","consumption_basis" to "homologated","occupants" to 1)
  return obj("schema_version" to "companion-vehicle-catalogue-entry-v1","entry_id" to id,
   "catalogue_version" to source.getString("version"),"source" to source.getString("source"),
   "source_url" to source.getString("publisher_url"),"license" to source.getString("license"),
   "source_updated_at" to source.getString("source_updated_at"),"source_file_sha256" to source.getString("source_sha256"),
   "make" to make,"model" to model,"variant" to variant,"fuel" to fuel,"fuel_label" to fuelLabel,
   "engine_cc" to null,"power_kw" to null,"transmission" to "","profile_values" to values,
   "source_ranges" to JSONObject(),"reference" to source.getJSONObject("reference_by_fuel").getJSONObject(fuel).copyJson(),
   "reference_policy" to "UBA_fleet_average_not_model_specific","market" to "Great Britain","model_year" to null)
 }
}

internal data class VehicleModelGroup(val make:String,val model:String,val options:List<VehicleOption>,val searchKey:String)
internal data class VehicleCatalogue(val models:List<VehicleModelGroup>,val entries:Int) {
 fun search(query:String):List<VehicleModelGroup> {
  val words=vehicleSearchKey(query).split(' ').filter {it.isNotBlank()}
  if(words.joinToString("").length<2)return emptyList()
  return models.filter {g->words.all {it in g.searchKey}||g.options.any {v->words.all {it in v.searchKey}}}
   .sortedWith(compareBy<VehicleModelGroup> {!it.searchKey.startsWith(words.joinToString(" "))}.thenBy {it.make}.thenBy {it.model})
 }
 companion object {
  @Volatile private var cached:VehicleCatalogue?=null
  fun load(context:Context):VehicleCatalogue=cached?:synchronized(this) {cached?:read(context).also {cached=it}}
  private fun read(context:Context):VehicleCatalogue {
   // AAPT expands .gz source assets and packages them without the .gz suffix.
   fun json(name:String)=context.assets.open(name).bufferedReader(Charsets.UTF_8).use {JSONObject(it.readText())}
   return fromSources(json("vehicle-catalogue-v1.json"),json("vehicle-names-gb-v1.json"))
  }
  internal fun fromSources(ademe:JSONObject,gb:JSONObject):VehicleCatalogue {
   val options=ArrayList<VehicleOption>()
   val a=ademe.getJSONArray("entries_data");ademe.remove("entries_data")
   for(i in 0 until a.length()) {
    val r=a.getJSONObject(i);val make=vehicleMakeName(r.getString("make"));val model=r.getString("model");val variant=r.getString("variant")
    val aliases=if(make=="VOLKSWAGEN")" vw"else""
    options.add(VehicleOption(r.getString("entry_id"),make,model,variant,r.getString("fuel"),r.getString("fuel_label"),r,ademe,vehicleSearchKey("$make $model $variant$aliases")))
   }
   val b=gb.getJSONArray("entries_data");gb.remove("entries_data")
   for(i in 0 until b.length()) {
    val r=b.getJSONArray(i);val rawMake=r.getString(0);val make=vehicleMakeName(rawMake)
    val original=r.getString(1);val model=if(original.startsWith("$rawMake ",ignoreCase=true))original.substring(rawMake.length+1)else original
    val variant=r.getString(2);val fuelLabel=r.getString(3);val aliases=if(make=="VOLKSWAGEN")" vw"else""
    options.add(VehicleOption("gb-$i",make,model,variant,gbVehicleFuel(fuelLabel),fuelLabel,null,gb,vehicleSearchKey("$make $model $variant$aliases")))
   }
   val groups=options.groupBy {vehicleSearchKey(it.make)+"|"+vehicleSearchKey(it.model)}.values.map {rows->
    val sorted=rows.sortedWith(compareBy<VehicleOption> {it.detail==null}.thenBy {it.variant}.thenBy {it.fuel})
    VehicleModelGroup(sorted.first().make,sorted.first().model,sorted,vehicleSearchKey(sorted.first().make+" "+sorted.first().model)+(if(sorted.first().make=="VOLKSWAGEN")" vw"else""))
   }
   return VehicleCatalogue(groups,options.size)
  }
 }
}

/** Build active inputs separately from the immutable catalogue reference. */
internal fun vehicleProfileDraft(previous:JSONObject?,catalogue:JSONObject?,manualName:String,fuel:String,
 personal:Boolean,litres:Double?,electricity:Double?,occupants:Int,at:String,revision:String=UUID.randomUUID().toString()):JSONObject {
 val next=previous?.copyJson()?:JSONObject()
 next.put("schema_version","companion-vehicle-profile-v2").put("profile_id",previous?.optString("profile_id")?:"vehicle-${UUID.randomUUID()}")
  .put("revision",revision).put("updated_at",at).put("fuel",fuel).put("occupants",occupants)
 val same=if(catalogue!=null)catalogue.optString("entry_id")==previous?.optJSONObject("catalogue")?.optString("entry_id")
  else manualName.trim()==listOf(previous?.optString("make").orEmpty(),previous?.optString("model").orEmpty()).filter {it.isNotBlank()}.joinToString(" ")
 if(!same)next.remove("year")
 next.put("make",catalogue?.optString("make")?.take(160)?:"")
  .put("model",(catalogue?.optString("model")?:manualName.trim()).take(160))
  .put("variant",catalogue?.optString("variant")?.take(160)?:"")
  .put("catalogue",catalogue?.copyJson()?:JSONObject.NULL)
 val legacy=same&&catalogue==null&&previous!=null&&(previous.optString("consumption_basis")=="homologated"||previous.number("homologated_co2_g_per_km")!=null)&&listOf("consumption_l_per_100km","electricity_kwh_per_100km","homologated_co2_g_per_km").any {previous.number(it)!=null}
 val reference=catalogue?.optJSONObject("profile_values")?:if(legacy)previous else null
 next.put("consumption_mode",if(personal)"personal"else if(catalogue!=null)"catalogue"else if(legacy)"legacy_reference"else"uba")
 next.put("consumption_basis",if(personal)"user_average"else"homologated")
 next.put("consumption_l_per_100km",(if(personal)litres else reference?.number("consumption_l_per_100km"))?:JSONObject.NULL)
  .put("electricity_kwh_per_100km",(if(personal)electricity else reference?.number("electricity_kwh_per_100km"))?:JSONObject.NULL)
  .put("homologated_co2_g_per_km",(if(personal)null else reference?.number("homologated_co2_g_per_km"))?:JSONObject.NULL)
  .put("homologation_protocol",if(personal)"unknown"else reference?.optString("homologation_protocol","unknown")?:"unknown")
 val oldBlend=next.optString("fuel_blend","unknown")
 if(oldBlend in listOf("E5","E10")&&fuel !in listOf("petrol","hybrid_petrol")||oldBlend=="B7"&&fuel!="diesel")next.put("fuel_blend","unknown")
 if(!next.has("fuel_blend"))next.put("fuel_blend","unknown")
 if(!next.has("bicycle_type"))next.put("bicycle_type","assumed_conventional")
 validateVehicleProfile(next)
 return next
}
