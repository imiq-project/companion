package de.ovgu.imiq.companion

import org.json.JSONArray
import org.json.JSONObject

internal const val VEHICLE_PROFILE="imiq.experimental.vehicle-profile.v1"
internal const val CLIMATE_METHOD="companion-climate-wtw-v1"

internal fun validateVehicleProfile(p:JSONObject) {
 val v2=p.optContractString("schema_version")=="companion-vehicle-profile-v2"
 val allowed=setOf("schema_version","profile_id","revision","updated_at","make","model","year","variant","fuel","fuel_blend","occupants","consumption_l_per_100km","electricity_kwh_per_100km","homologated_co2_g_per_km","homologation_protocol","consumption_basis","bicycle_type")+if(v2)setOf("catalogue","consumption_mode")else emptySet()
 require(p.keys().asSequence().all {it in allowed})
 require(p.optContractString("schema_version") in listOf("companion-vehicle-profile-v1","companion-vehicle-profile-v2"))
 listOf("profile_id","revision","updated_at").forEach {require(p.opt(it) is String&&p.getString(it).length in 1..160)}
 java.time.Instant.parse(p.getString("updated_at"))
 listOf("fuel_blend","homologation_protocol","consumption_basis","bicycle_type").forEach {require(!p.has(it)||p.opt(it) is String)}
 listOf("make","model","variant").forEach {require(!p.has(it)||p.opt(it) is String&&p.getString(it).length<=160)}
 if(!p.isNull("year"))require(p.opt("year") is Int&&p.getInt("year") in 1900..2100)
 require(p.optString("fuel") in listOf("unknown","petrol","diesel","electric","hybrid_petrol"))
 require(p.opt("occupants") is Int&&p.getInt("occupants") in 1..9)
 listOf("consumption_l_per_100km" to 100.0,"electricity_kwh_per_100km" to 200.0,"homologated_co2_g_per_km" to 2000.0).forEach {(key,max)->
  val min=if(p.optString("fuel")=="hybrid_petrol"&&key!="homologated_co2_g_per_km")0.0 else .01
  if(!p.isNull(key)){val value=p.number(key);require(p.opt(key) is Number&&value!=null&&value in min..max)}
 }
 require(p.optString("fuel_blend","unknown") in listOf("unknown","fossil","E5","E10","B7"))
 require(p.optString("homologation_protocol","unknown") in listOf("unknown","WLTP","NEDC"))
 require(p.optString("consumption_basis","user_average") in listOf("user_average","homologated"))
 require(p.optString("bicycle_type","assumed_conventional") in listOf("assumed_conventional","conventional","electric"))
 val fuel=p.getString("fuel");val blend=p.optString("fuel_blend","unknown")
 require(blend !in listOf("E5","E10")||fuel in listOf("petrol","hybrid_petrol"))
 require(blend!="B7"||fuel=="diesel")
 require(fuel !in listOf("unknown","electric")||p.isNull("consumption_l_per_100km"))
 require(fuel !in listOf("unknown","petrol","diesel")||p.isNull("electricity_kwh_per_100km"))
 if(v2){
  val mode=p.getString("consumption_mode");val catalogue=p.optJSONObject("catalogue")
  require(mode in listOf("catalogue","personal","uba","legacy_reference"))
  if(!p.isNull("catalogue")){
   require(catalogue!=null&&catalogue.optContractString("schema_version")=="companion-vehicle-catalogue-entry-v1")
   listOf("entry_id","catalogue_version","source","source_url","license","make","model","variant").forEach {key->require(catalogue.opt(key) is String&&catalogue.getString(key).length in 1..2000)}
   val values=catalogue.getJSONObject("profile_values");require(values.getString("fuel")==fuel)
   validateVehicleProfile(values.copyJson().put("schema_version","companion-vehicle-profile-v1").put("profile_id","catalogue-reference").put("revision","reference").put("updated_at",p.getString("updated_at")))
   if(mode=="catalogue")require(p.optString("consumption_basis")=="homologated")
   if(mode=="catalogue")listOf("consumption_l_per_100km","electricity_kwh_per_100km","homologated_co2_g_per_km").forEach {key->require(p.number(key)==values.number(key))}
   if(mode=="catalogue")require(p.optString("homologation_protocol")==values.optString("homologation_protocol"))
  }
  if(mode=="catalogue")require(catalogue!=null)
  if(mode=="legacy_reference")require(catalogue==null)
  if(mode=="uba")require(catalogue==null&&listOf("consumption_l_per_100km","electricity_kwh_per_100km","homologated_co2_g_per_km").all {p.isNull(it)})
  if(mode=="personal"){
   val litres=p.number("consumption_l_per_100km");val kwh=p.number("electricity_kwh_per_100km")
   require(fuel!="unknown"&&(fuel !in listOf("petrol","diesel")||litres!=null)&&(fuel!="electric"||kwh!=null)) {"Enter your consumption"}
   if(fuel=="hybrid_petrol")require(litres!=null&&kwh!=null&&litres+kwh>0) {"Enter fuel and electricity consumption"}
   require(p.optString("consumption_basis")=="user_average"&&p.isNull("homologated_co2_g_per_km"))
  }
 }
}

internal fun Store.saveVehicleProfile(profile:JSONObject,expectedRevision:String?) {
 validateVehicleProfile(profile)
 val db=writableDatabase;db.beginTransaction()
 try {
  val previous=json(VEHICLE_PROFILE)
  require(previous?.optString("revision")==expectedRevision) {"Vehicle profile changed; reopen the editor"}
  require(previous?.optString("revision")!=profile.getString("revision")) {"Use a new profile revision"}
  require(json("$VEHICLE_PROFILE.archive.${profile.getString("revision")}")==null) {"Profile revision already archived"}
  previous?.let {put("$VEHICLE_PROFILE.archive.${it.getString("revision")}",it)}
  put("$VEHICLE_PROFILE.archive.${profile.getString("revision")}",profile)
  put(VEHICLE_PROFILE,profile);db.setTransactionSuccessful()
 }finally{db.endTransaction()}
}

internal fun validateVehicleStorage(values:JSONObject) {
 values.keys().asSequence().filter {it==VEHICLE_PROFILE||it.startsWith("$VEHICLE_PROFILE.archive.")}.forEach {key->
  val p=JSONObject(values.getString(key));validateVehicleProfile(p)
  if(key!=VEHICLE_PROFILE)require(key=="$VEHICLE_PROFILE.archive.${p.getString("revision")}") {"Vehicle archive identity mismatch"}
 }
 if(values.has(VEHICLE_PROFILE)){
  val p=JSONObject(values.getString(VEHICLE_PROFILE));val key="$VEHICLE_PROFILE.archive.${p.getString("revision")}"
  require(values.has(key)&&canonical(p)==canonical(JSONObject(values.getString(key)))) {"Current vehicle profile lacks its immutable archive"}
 }
}

internal fun climateOutcome(plan:JSONObject,completion:String,actual:List<String>):JSONObject {
 val known=completion=="as_planned"&&plan.optString("status")=="estimated"
 return obj("schema_version" to "companion-journey-climate-v1","method_version" to CLIMATE_METHOD,
  "status" to when(completion){"not_taken"->"not_taken";"as_planned"->if(known)"estimated_confirmed_itinerary"else"unknown_incomplete_itinerary";else->"unknown_changed_itinerary"},
  "plan_snapshot" to plan.copyJson(),"reported_modes" to JSONArray(actual),
  "g_co2e" to if(known)plan.number("g_co2e")else if(completion=="not_taken")0 else null,
  "comparisons" to if(known)plan.optJSONObject("comparisons")?.copyJson()else null,
  "basis" to if(known)"user_confirmed_planned_itinerary_not_GPS_measurement"else"no_completed_itinerary_measurement")
}

internal fun validateClimateSnapshot(value:JSONObject) {
 require(value.optContractString("schema_version")=="companion-route-climate-v1"&&value.optString("method_version")==CLIMATE_METHOD)
 value.optJSONObject("profile_snapshot")?.let(::validateVehicleProfile)
 val parts=value.getJSONArray("segments").objects()
 val known=parts.mapNotNull {s->s.number("g_co2e")?.also {g->
  val distance=s.number("distance_meters");val intensity=s.number("kg_per_passenger_km")
  require(g>=0&&distance!=null&&distance>=0&&intensity!=null&&intensity>=0&&kotlin.math.abs(g-distance*intensity)<=.00001) {"Climate segment arithmetic mismatch"}
  s.number("g_co2e_vehicle")?.let {vehicle->
   val n=s.optInt("vehicle_occupants");require(s.optString("mode")=="car"&&n in 1..9&&value.optJSONObject("profile_snapshot")?.optInt("occupants")==n&&vehicle>=0&&kotlin.math.abs(vehicle-g*n)<=.00002)
   listOf("estimated_fuel_litres","estimated_electricity_kwh").forEach {key->require(s.isNull(key)||s.number(key)?.let {it>=0}==true)}
  }
 }}
 val g=value.number("g_co2e")
 if(value.optString("status")=="estimated")require(parts.isNotEmpty()&&known.size==parts.size&&g!=null&&g>=0&&kotlin.math.abs(g-known.sum())<=.00001) {"Climate total mismatch"}
 else require(value.isNull("g_co2e")) {"Incomplete climate snapshot has a total"}
 value.optJSONObject("comparisons")?.let {comparisons->comparisons.keys().forEach {key->
  val c=comparisons.getJSONObject(key);val ref=c.optJSONObject("reference");val delta=c.number("difference_g_co2e")
  if(ref!=null){validateClimateSnapshot(ref);require(listOf("search_id","profile_revision","profile_snapshot","factor_version","factor_sha256","boundary").all {canonical(value.opt(it))==canonical(ref.opt(it))}) {"Climate reference provenance mismatch"}}
  if(c.optString("status")=="estimated_difference")require(ref!=null&&ref.optString("status")=="estimated"&&value.optString("status")=="estimated"&&g!=null&&delta!=null&&kotlin.math.abs(delta-(ref.getDouble("g_co2e")-g))<=.00001) {"Climate comparison arithmetic mismatch"}
  else require(c.isNull("difference_g_co2e"))
 }}
}

internal fun validateClimateOutcome(record:JSONObject,journey:JSONObject) {
 val plan=journey.getJSONObject("plan").getJSONObject("route").optJSONObject("climate_estimate")
 if(plan!=null){validateClimateSnapshot(plan);require(canonical(plan.opt("profile_snapshot"))==canonical(record.getJSONObject("request").opt("climate_profile"))) {"Climate profile differs from frozen request"}}
 val e=journey.optJSONObject("experience")?:return
 val actual=e.getJSONArray("actual_modes").let {a->(0 until a.length()).map {a.getString(it)}}
 val outcome=e.optJSONObject("climate_outcome")
 if(plan==null&&outcome==null)return
 require(outcome!=null) {"Climate outcome is required for this plan"}
 require(plan!=null) {"Climate outcome lacks frozen plan"}
 require(canonical(outcome.copyJson())==canonical(climateOutcome(plan,e.getString("completion"),actual).copyJson())) {"Climate outcome differs from plan/report"}
}
