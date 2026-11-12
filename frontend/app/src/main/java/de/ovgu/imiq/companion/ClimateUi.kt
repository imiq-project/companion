package de.ovgu.imiq.companion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

internal fun climateAmount(value:Double?):String=when {
 value==null->"—"
 value==0.0->"0 g CO₂e"
 value<1->"<1 g CO₂e"
 value<1000->"≈ ${kotlin.math.round(value).toInt()} g CO₂e"
 else->"≈ ${String.format(java.util.Locale.ROOT,"%.2f",value/1000)} kg CO₂e"
}

internal data class RouteClimatePresentation(val amount:String,val comparison:String?,val vehicle:String?,val note:String?,val needsRefresh:Boolean)

/** Read only the estimate frozen with this route; never substitute today's car in saved evidence. */
internal fun routeClimatePresentation(route:JSONObject,de:Boolean,currentProfile:JSONObject?=null,checkCurrentProfile:Boolean=false):RouteClimatePresentation {
 fun text(en:String,german:String)=if(de)german else en
 val climate=route.optJSONObject("climate_estimate")
 if(climate==null)return RouteClimatePresentation(text("CO₂e estimate unavailable for this search","CO₂e-Schätzung für diese Suche nicht verfügbar"),null,null,
  text("Update the route to calculate emissions.","Weg aktualisieren, um Emissionen zu berechnen."),true)
 val complete=climate.optString("status")=="estimated"&&climate.number("g_co2e")!=null
 val known=climate.number("known_portion_g_co2e")?.takeIf {climate.optString("status")=="partial"}
 val amount=when {
  complete->climateAmount(climate.number("g_co2e"))+text(" / person · estimated"," / Person · geschätzt")
  known!=null->text("Mapped portion: ","Kartierter Abschnitt: ")+climateAmount(known)+text(" / person"," / Person")
  else->text("CO₂e estimate unavailable","CO₂e-Schätzung nicht verfügbar")
 }
 val profile=climate.optJSONObject("profile_snapshot")
 val frozenRevision=climate.optString("profile_revision",profile?.optString("revision")?:"national-default-v1")
 val changed=checkCurrentProfile&&frozenRevision!=(currentProfile?.optString("revision")?:"national-default-v1")
 val car=climate.optJSONObject("comparisons")?.optJSONObject("car")
 val reference=car?.optJSONObject("reference")
 val difference=car?.number("difference_g_co2e")?.takeIf {complete&&car.optString("status")=="estimated_difference"}
 val comparison=if(difference!=null&&difference!=0.0&&reference?.optString("route_key")!=climate.optString("route_key")) {
  val label=if(profile==null)text("reference car","Referenzauto")else text("your car profile","deinem Autoprofil")
  climateAmount(kotlin.math.abs(difference))+(if(difference>0)text(" less than "," weniger als ")else text(" more than "," mehr als "))+label
 }else null
 val vehicle=profile?.takeIf {modelMode(route.optString("mode_key"))=="car"||comparison!=null}?.let {p->
  val name=listOf(p.optString("make"),p.optString("model")).filter {it.isNotBlank()}.joinToString(" ").ifBlank {text("Car profile","Autoprofil")}
  val energy=listOfNotNull(p.number("consumption_l_per_100km")?.let {"${metric(it,2)} L/100 km"},p.number("electricity_kwh_per_100km")?.let {"${metric(it,2)} kWh/100 km"}).joinToString(" + ")
  name+" · "+if(energy.isNotBlank())energy+if(usesPersonalVehicleConsumption(p))text(" · your average"," · dein Durchschnitt")else text(" · reference"," · Referenz")else text("UBA fleet estimate","UBA-Flottenschätzung")
 }
 val note=when {
  changed->text("Vehicle profile changed · update the route to use it.","Autoprofil geändert · Weg aktualisieren, um es zu verwenden.")
  !complete->text("Total unavailable · route information is incomplete.","Gesamtwert nicht verfügbar · Routenangaben unvollständig.")
  else->null
 }
 return RouteClimatePresentation(amount,comparison,vehicle,note,changed)
}

@Composable internal fun ClimateRouteSummary(model:CompanionModel,route:JSONObject,onRefresh:(()->Unit)?=null) {
 CompactClimateSummary(model,route,onRefresh)
}

@Composable internal fun ClimateDetails(model:CompanionModel,climate:JSONObject?) {
 if(climate==null)return
 Disclosure(model.text("Emissions · sources and assumptions","Emissionen · Quellen und Annahmen")) {
  Copy(routeClimatePresentation(obj("climate_estimate" to climate),model.de).amount,Mint)
  Copy(model.text("Operation and energy supply. Vehicle manufacture, infrastructure and food are excluded. This is an attributed estimate, not measured or causally avoided emissions.","Betrieb und Energiebereitstellung. Fahrzeugherstellung, Infrastruktur und Ernährung sind ausgeschlossen. Zugerechnete Schätzung, keine gemessenen oder kausal vermiedenen Emissionen."),MutedText)
  if(climate.optString("status")!="estimated")Copy(model.text("Distance, access or vehicle information is incomplete; an unknown part is not zero.","Strecken-, Zugangs- oder Fahrzeuginformationen fehlen; unbekannte Anteile sind nicht null."),Gold)
  climate.optJSONObject("profile_snapshot")?.let {p->
   Copy(listOf(p.optString("make"),p.optString("model"),p.optString("variant"),p.number("year")?.toInt()?.toString().orEmpty()).filter {it.isNotBlank()}.joinToString(" · "))
   Copy(p.optString("fuel")+" · "+p.optInt("occupants")+model.text(" people incl. driver"," Personen inkl. Fahrer"))
   p.number("consumption_l_per_100km")?.let {Copy("$it L/100 km")}
   p.number("electricity_kwh_per_100km")?.let {Copy("$it kWh/100 km")}
   Copy(model.text("Saved vehicle revision: ","Gespeicherte Fahrzeugversion: ")+p.optString("revision"),MutedText)
  }?:Copy(model.text("German fleet averages · no personal vehicle configured","Deutsche Flottenmittelwerte · kein eigenes Fahrzeug konfiguriert"))
  climate.objects("segments").forEach {s->
   Copy("${s.optInt("index")+1}. "+modeName(s.optString("mode"),model.de)+" · ${metric(s.number("distance_meters")?.div(1000),2)} km · "+climateAmount(s.number("g_co2e")))
   if(s.optString("mode")=="car"){
    s.number("estimated_fuel_litres")?.let {Copy(model.text("Estimated fuel for this driving leg: ","Geschätzter Kraftstoff für diesen Fahrabschnitt: ")+metric(it,3)+" L",Mint)}
    s.number("estimated_electricity_kwh")?.let {Copy(model.text("Estimated electricity for this driving leg: ","Geschätzter Strom für diesen Fahrabschnitt: ")+metric(it,3)+" kWh",Mint)}
    s.number("g_co2e_vehicle")?.let {Copy(model.text("Vehicle emissions: ","Fahrzeugemissionen: ")+climateAmount(it),MutedText)}
   }
   Copy(climateBasis(model,s.optString("basis")),MutedText)
   s.optJSONArray("assumptions")?.let {a->(0 until a.length()).forEach {i->Copy(climateAssumption(model,a.getString(i)),MutedText)}}
  }
  val comparisons=climate.optJSONObject("comparisons")
  listOf("car","pt").forEach {mode->
   val c=comparisons?.optJSONObject(mode);val ref=c?.optJSONObject("reference");val delta=c?.number("difference_g_co2e")
   Copy(model.text("Compared with ","Verglichen mit ")+modeName(mode,model.de)+": "+if(delta==null)model.text("unknown","unbekannt")else (if(delta>=0)"− "else"+ ")+climateAmount(kotlin.math.abs(delta)))
   if(ref!=null)Copy(model.text("Reference route: ","Referenzroute: ")+ref.optString("route_key")+" · "+climateAmount(ref.number("g_co2e")),MutedText)
  }
  Copy(model.text("References use their own returned routes. PT uses the earliest-arriving returned connection; no alternative means no comparison.","Referenzen verwenden ihre eigenen berechneten Wege. ÖPNV nutzt die zuerst ankommende zurückgegebene Verbindung; ohne Alternative gibt es keinen Vergleich."),MutedText)
  Copy(model.text("No statistical confidence interval is available. A model/year label alone does not identify real consumption.","Es liegt kein statistisches Konfidenzintervall vor. Modell und Baujahr allein bestimmen keinen realen Verbrauch."),MutedText)
  Copy("UBA · "+climate.optString("factor_version")+" · "+climate.optString("factor_region")+" · CC0 1.0",MutedText)
  Copy(model.text("Method: ","Methode: ")+climate.optString("method_version")+"\n"+climate.optString("estimate_id")+"\n"+climate.optString("source_url"),MutedText)
 }
}

@Composable private fun climateBasis(model:CompanionModel,key:String)=when(key){
 "no_motor_energy"->model.text("No motor energy within this boundary","Keine Motorenergie innerhalb dieser Bilanzgrenze")
 "national_passenger_average"->model.text("National factor per passenger-km; occupancy already included","Nationaler Faktor pro Personenkilometer; Auslastung bereits enthalten")
 "national_vehicle_average_with_declared_occupancy"->model.text("National vehicle average divided by declared occupants","Nationaler Fahrzeugmittelwert geteilt durch angegebene Insassen")
 "homologation_inferred_fossil_consumption"->model.text("Inferred from homologated CO₂; fossil-fuel reference","Aus homologiertem CO₂ abgeleitet; fossile Kraftstoffreferenz")
 "declared_user_average"->model.text("Your declared average consumption","Dein angegebener Durchschnittsverbrauch")
 "declared_homologated"->model.text("Declared laboratory consumption","Angegebener Laborverbrauch")
 "declared_electric_consumption"->model.text("Declared electricity consumption","Angegebener Stromverbrauch")
 "declared_dual_energy_consumption"->model.text("Declared fuel plus electricity consumption","Angegebener Kraftstoff- plus Stromverbrauch")
 "national_ebike_average"->model.text("National electric bicycle average","Nationaler E-Bike-Mittelwert")
 else->model.text("Unknown input","Unbekannte Eingabe")
}

@Composable private fun climateAssumption(model:CompanionModel,key:String)=when {
 key.startsWith("nominal_blend_scenario_")->model.text("Nominal E5/E10/B7 blend scenario; actual blend was not measured","Nominelles E5/E10/B7-Mischszenario; tatsächliche Mischung nicht gemessen")
 key in listOf("fossil_fuel_reference_actual_blend_unknown","fossil_petrol_reference","fossil_fuel_conversion_reference")->model.text("Fossil fuel reference; retail fuel blend can differ","Fossile Kraftstoffreferenz; getankte Mischung kann abweichen")
 key=="not_specific_to_MVB_vehicle_or_departure"->model.text("Not measured for this MVB vehicle or departure","Nicht für dieses MVB-Fahrzeug oder diese Abfahrt gemessen")
 key=="national_average_occupancy"->model.text("Average occupancy; not live occupancy","Durchschnittliche Auslastung; keine Echtzeitbelegung")
 key=="cold_start_and_traffic_not_individually_modelled"->model.text("Traffic and cold starts are not individually modelled","Verkehr und Kaltstarts nicht individuell modelliert")
 key=="German_average_electricity"->model.text("German average electricity including energy supply","Deutscher Strommix einschließlich Energiebereitstellung")
 key=="consumption_must_include_charging_losses"->model.text("Declared electricity should include charging losses","Angegebener Stromverbrauch sollte Ladeverluste enthalten")
 key=="UBA_liquid_fuel_reference"->model.text("Published UBA liquid-fuel reference factor","Veröffentlichter UBA-Referenzfaktor für Kraftstoff")
 key=="declared_blend_metadata_only_not_recalculated"->model.text("Blend recorded only; no measured biogenic share is available to recalculate the factor","Mischung nur dokumentiert; kein gemessener Bioanteil zur Neuberechnung des Faktors verfügbar")
 key=="homologated_CO2_is_not_real_world_consumption"->model.text("Homologated CO₂ is a laboratory reference","Homologiertes CO₂ ist eine Laborreferenz")
 key=="conventional_bicycle_assumed"->model.text("Conventional bicycle assumed; configure assistance in Available transport","Konventionelles Fahrrad angenommen; Unterstützung unter Verkehrsmittel einstellen")
 key=="national_fleet_consumption"->model.text("Fleet-average consumption","Flotten-Durchschnittsverbrauch")
 key=="model_and_year_are_descriptive_not_a_consumption_lookup"->model.text("Vehicle identity does not establish measured consumption; a fleet reference is used","Fahrzeugidentität belegt keinen gemessenen Verbrauch; eine Flottenreferenz wird verwendet")
 key=="conventional_bicycle"->model.text("Conventional bicycle","Konventionelles Fahrrad")
 key=="food_vehicle_manufacture_and_infrastructure_excluded"->model.text("Food, vehicle manufacture and infrastructure excluded","Ernährung, Fahrzeugherstellung und Infrastruktur ausgeschlossen")
 else->model.text("Consumption is an estimate","Verbrauch ist eine Schätzung")
}

@Composable internal fun JourneyClimate(model:CompanionModel,trip:JSONObject) {
 val outcome=trip.optJSONObject("experience")?.optJSONObject("climate_outcome")
 val plan=trip.optJSONObject("plan")?.optJSONObject("route")?.optJSONObject("climate_estimate")?:return
 PixelCard {
  Heading(model.text("Saved emissions estimate","Gespeicherte Emissionsschätzung"))
  Copy(when(outcome?.optString("status")){
   "estimated_confirmed_itinerary"->climateAmount(outcome.number("g_co2e"))+model.text(" per person · confirmed planned itinerary"," pro Person · bestätigter geplanter Weg")
   "not_taken"->model.text("Trip not taken · no saving is claimed","Reise nicht durchgeführt · keine Einsparung behauptet")
   "unknown_changed_itinerary"->model.text("Actual emissions unknown · itinerary changed","Tatsächliche Emissionen unbekannt · Weg geändert")
   "unknown_incomplete_itinerary"->model.text("Actual emissions unknown · planned estimate incomplete","Tatsächliche Emissionen unbekannt · geplante Schätzung unvollständig")
   else->model.text("Planned estimate · actual journey not confirmed","Geplante Schätzung · tatsächliche Reise nicht bestätigt")
  })
  ClimateDetails(model,plan)
 }
}

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable internal fun VehicleProfileSettings(model:CompanionModel,store:Store=model.store) {
 val saved=remember(model.revision){store.json(VEHICLE_PROFILE)}
 var edit by remember {mutableStateOf(false)}
 var saving by remember {mutableStateOf(false)}
 TextButton({edit=true}){Text(model.text("Vehicle & emissions profile","Fahrzeug- und Emissionsprofil"))}
 if(saved!=null)Text(listOf(saved.optString("make"),saved.optString("model"),saved.optString("fuel")).filter {it.isNotBlank()}.joinToString(" · "),style=MaterialTheme.typography.bodySmall,color=MutedText)
 if(edit)ModalBottomSheet(onDismissRequest={if(!saving)edit=false},containerColor=Ink,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true,confirmValueChange={it!=SheetValue.Hidden||!saving})) {
  VehicleProfileForm(model,saved,store,onClose={edit=false},onBusyChanged={saving=it})
 }
}

