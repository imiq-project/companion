package de.ovgu.imiq.companion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable private fun VehicleUiIcon(kind:String,modifier:Modifier=Modifier,color:Color=Mint) {
 Canvas(modifier.size(24.dp)) {
  val w=size.width;val h=size.height;val stroke=Stroke(1.8.dp.toPx(),cap=StrokeCap.Round)
  when(kind){
   "search"->{drawCircle(color,w*.28f,Offset(w*.4f,h*.4f),style=stroke);drawLine(color,Offset(w*.61f,h*.61f),Offset(w*.88f,h*.88f),1.8.dp.toPx(),StrokeCap.Round)}
   "tune"->{for(i in 0..2){val y=h*(.22f+i*.28f);drawLine(color,Offset(w*.1f,y),Offset(w*.9f,y),1.8.dp.toPx());drawCircle(Panel,w*.10f,Offset(w*(if(i==1).65f else .35f),y));drawCircle(color,w*.10f,Offset(w*(if(i==1).65f else .35f),y),style=stroke)}}
   "fuel"->{val p=Path().apply {moveTo(w*.5f,h*.08f);cubicTo(w*.25f,h*.4f,w*.18f,h*.5f,w*.18f,h*.65f);cubicTo(w*.18f,h*1.02f,w*.82f,h*1.02f,w*.82f,h*.65f);cubicTo(w*.82f,h*.5f,w*.75f,h*.4f,w*.5f,h*.08f);close()};drawPath(p,color,style=stroke)}
   "leaf"->{drawOval(color,Offset(w*.18f,h*.08f),Size(w*.65f,h*.8f),style=stroke);drawLine(color,Offset(w*.2f,h*.92f),Offset(w*.68f,h*.28f),1.8.dp.toPx(),StrokeCap.Round)}
   else->{drawRoundRect(color,Offset(w*.1f,h*.4f),Size(w*.8f,h*.36f),CornerRadius(w*.1f),style=stroke);val p=Path().apply {moveTo(w*.22f,h*.4f);lineTo(w*.33f,h*.18f);lineTo(w*.7f,h*.18f);lineTo(w*.8f,h*.4f)};drawPath(p,color,style=stroke);drawCircle(color,w*.07f,Offset(w*.27f,h*.8f));drawCircle(color,w*.07f,Offset(w*.73f,h*.8f))}
  }
 }
}

@Composable private fun vehicleFuelName(model:CompanionModel,fuel:String)=when(fuel){
 "petrol"->model.text("Petrol","Benzin");"diesel"->"Diesel";"electric"->model.text("Electric","Elektrisch")
 "hybrid_petrol"->model.text("Plug-in petrol hybrid","Plug-in-Benzinhybrid");else->model.text("Other / unknown","Andere / unbekannt")
}

internal fun validPersonalVehicleEnergy(fuel:String,litres:Double?,electricity:Double?):Boolean=when(fuel){
 "petrol","diesel"->litres!=null&&litres.isFinite()&&litres in .01..100.0&&electricity==null
 "electric"->electricity!=null&&electricity.isFinite()&&electricity in .01..200.0&&litres==null
 "hybrid_petrol"->litres!=null&&electricity!=null&&litres.isFinite()&&electricity.isFinite()&&litres in 0.0..100.0&&electricity in 0.0..200.0&&litres+electricity>0
 else->false
}

internal fun vehiclePreviewRate(fuel:String,litres:Double?,electricity:Double?,factors:JSONObject):Double? {
 if(!validPersonalVehicleEnergy(fuel,litres,electricity))return null
 fun factor(key:String)=factors.getJSONObject("factors").getJSONObject(key).getDouble("kg_per_unit")
 return when(fuel){
  "petrol","diesel"->litres?.takeIf {it.isFinite()&&it>0&&it<=100}?.let {it*10*factor(fuel+"_litre")}
  "electric"->electricity?.takeIf {it.isFinite()&&it>0&&it<=200}?.let {it*10*factor("electricity_kwh")}
  "hybrid_petrol"->if(litres!=null&&electricity!=null&&litres.isFinite()&&electricity.isFinite()&&litres in 0.0..100.0&&electricity in 0.0..200.0&&litres+electricity>0)(litres*factor("petrol_litre")+electricity*factor("electricity_kwh"))*10 else null
  else->null
 }
}

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable internal fun VehicleProfileForm(model:CompanionModel,saved:JSONObject?,store:Store,onClose:()->Unit,onBusyChanged:(Boolean)->Unit={}) {
 val context=androidx.compose.ui.platform.LocalContext.current
 val scope=rememberCoroutineScope()
 val carScroll=rememberScrollState();val personalScroll=rememberScrollState()
 var tab by remember {mutableIntStateOf(0)}
 var selected by remember {mutableStateOf(saved?.optJSONObject("catalogue")?.copyJson())}
 var manualName by remember {mutableStateOf(listOf(saved?.optString("make").orEmpty(),saved?.optString("model").orEmpty()).filter {it.isNotBlank()}.joinToString(" "))}
 var fuel by remember {mutableStateOf(saved?.optString("fuel")?:"unknown")}
 var personal by remember {mutableStateOf(usesPersonalVehicleConsumption(saved))}
 var litres by remember {mutableStateOf(if(usesPersonalVehicleConsumption(saved))saved?.number("consumption_l_per_100km")?.toString().orEmpty()else"")}
 var electricity by remember {mutableStateOf(if(usesPersonalVehicleConsumption(saved))saved?.number("electricity_kwh_per_100km")?.toString().orEmpty()else"")}
 var occupants by remember {mutableIntStateOf(saved?.optInt("occupants",1)?:1)}
 var query by remember {mutableStateOf("")}
 var variantQuery by remember {mutableStateOf("")}
 var choosing by remember {mutableStateOf(saved==null||saved.optContractString("schema_version")=="companion-vehicle-profile-v2"&&saved.optString("fuel")=="unknown"&&saved.optString("make").isBlank()&&saved.optString("model").isBlank())}
 var group by remember {mutableStateOf<VehicleModelGroup?>(null)}
 var matches by remember {mutableStateOf<List<VehicleModelGroup>>(emptyList())}
 var limit by remember {mutableIntStateOf(12)}
 var catalogue by remember {mutableStateOf<VehicleCatalogue?>(null)}
 var factors by remember {mutableStateOf<JSONObject?>(null)}
 var failure by remember {mutableStateOf<String?>(null)}
 var loadingFailure by remember {mutableStateOf(false)}
 var busy by remember {mutableStateOf(false)}
 LaunchedEffect(Unit){
  try {factors=withContext(Dispatchers.IO){context.assets.open("climate-factors-v1.json").bufferedReader().use {JSONObject(it.readText())}}}catch(e:CancellationException){throw e}catch(_:Exception){}
  try {catalogue=withContext(Dispatchers.IO){VehicleCatalogue.load(context)}}catch(e:CancellationException){throw e}catch(_:Exception){loadingFailure=true}
 }
 LaunchedEffect(query,catalogue){limit=12;delay(180);matches=withContext(Dispatchers.Default){catalogue?.search(query).orEmpty()}}
 fun decimal(s:String)=if(s.isBlank())null else s.replace(',','.').toDoubleOrNull()
 fun choose(option:VehicleOption){if(busy)return;selected=option.snapshot();fuel=option.fuel;personal=false;litres="";electricity="";choosing=false;group=null;failure=null;scope.launch {carScroll.scrollTo(0)}}
 val vehicleName=selected?.let {vehicleMakeName(it.optString("make"))+" "+it.optString("model")}?:manualName.ifBlank {model.text("My car","Mein Auto")}
 val reference=selected?.optJSONObject("profile_values")?:saved?.takeIf {selected==null&&(it.optString("consumption_basis")=="homologated"||it.number("homologated_co2_g_per_km")!=null)&&fuel==it.optString("fuel")&&manualName.trim()==listOf(it.optString("make"),it.optString("model")).filter {v->v.isNotBlank()}.joinToString(" ")}
 val usedLitres=if(personal)decimal(litres)else reference?.number("consumption_l_per_100km")
 val usedElectricity=if(personal)decimal(electricity)else reference?.number("electricity_kwh_per_100km")
 val rate=if(personal)factors?.let {vehiclePreviewRate(fuel,usedLitres,usedElectricity,it)}else selected?.optJSONObject("reference")?.number("g_co2e_per_vehicle_km")?:factors?.let {f->
  vehiclePreviewRate(fuel,usedLitres,usedElectricity,f)?:reference?.number("homologated_co2_g_per_km")?.takeIf {fuel in listOf("petrol","diesel")}?.let {it*f.getJSONObject("factors").getJSONObject(fuel+"_litre").getDouble("kg_per_unit")/f.getJSONObject("factors").getJSONObject(fuel+"_tailpipe_co2_litre").getDouble("kg_per_unit")}
  ?:f.getJSONObject("factors").getJSONObject(when(fuel){"petrol"->"petrol_car_km";"diesel"->"diesel_car_km";"electric"->"electric_car_km";"hybrid_petrol"->"hybrid_car_km";else->"average_car_km"}).getDouble("kg_per_unit")*1000
 }
 Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).imePadding()) {
  Row(Modifier.padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
   VehicleUiIcon("car");Text(model.text("Car & emissions","Auto und Emissionen"),style=MaterialTheme.typography.titleLarge)
  }
  PrimaryTabRow(tab,containerColor=Ink,contentColor=Mint) {
   Tab(tab==0,{tab=0},enabled=!busy,text={Text(model.text("My car","Mein Auto"))},icon={VehicleUiIcon("car")})
   Tab(tab==1,{tab=1},enabled=!busy,text={Text(model.text("Personalize","Anpassen"))},icon={VehicleUiIcon("tune")})
  }
  Column(Modifier.weight(1f).verticalScroll(if(tab==0)carScroll else personalScroll).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
   if(tab==0) {
    if(choosing) {
     if(group==null){
      OutlinedTextField(query,{query=it.take(100)},Modifier.fillMaxWidth(),singleLine=true,enabled=!busy,
       label={Text(model.text("Search make or model","Marke oder Modell suchen"))},leadingIcon={VehicleUiIcon("search")})
      if(catalogue==null&&!loadingFailure)LinearProgressIndicator(Modifier.fillMaxWidth())
      if(loadingFailure)Text(model.text("Catalogue could not be loaded. You can enter your car under Personalize.","Katalog konnte nicht geladen werden. Unter Anpassen kannst du dein Auto eingeben."),color=MutedText)
      if(query.trim().length>=2&&catalogue!=null&&matches.isEmpty())Text(model.text("No matching car. Try the make or enter it manually.","Kein passendes Auto. Marke versuchen oder manuell eingeben."),color=MutedText)
      matches.take(limit).forEach {item->
       OutlinedButton({group=item;variantQuery="";limit=12;scope.launch {carScroll.scrollTo(0)};if(item.options.size==1)choose(item.options.single())},Modifier.fillMaxWidth(),enabled=!busy,shape=MaterialTheme.shapes.medium,contentPadding=PaddingValues(12.dp)) {
        VehicleUiIcon("car");Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f),horizontalAlignment=Alignment.Start){Text(item.make+" "+item.model,color=Pale);Text("${item.options.size} "+model.text("versions","Versionen"),style=MaterialTheme.typography.bodySmall,color=MutedText)};Text("›",color=Mint)
       }
      }
      if(matches.size>limit)TextButton({limit+=12}){Text(model.text("Show more models","Weitere Modelle anzeigen"))}
     }else {
      TextButton({group=null;limit=12}){Text(model.text("‹ All models","‹ Alle Modelle"))}
      Text(group!!.make+" "+group!!.model,style=MaterialTheme.typography.titleMedium,color=Pale)
      Text(model.text("Choose your version","Deine Version wählen"),color=MutedText)
      if(group!!.options.size>8)OutlinedTextField(variantQuery,{variantQuery=it.take(100);limit=12},Modifier.fillMaxWidth(),singleLine=true,label={Text(model.text("Filter engine or version","Motor oder Version filtern"))},leadingIcon={VehicleUiIcon("search")})
      val words=vehicleSearchKey(variantQuery).split(' ').filter {it.isNotBlank()}
      val versions=remember(group,variantQuery){group!!.options.filter {v->words.all {it in v.searchKey}}}
      versions.take(limit).forEach {option->OutlinedButton({choose(option)},Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.medium,contentPadding=PaddingValues(12.dp)) {
       Column(Modifier.weight(1f),horizontalAlignment=Alignment.Start){Text(option.variant,color=Pale);Text(vehicleFuelName(model,option.fuel),style=MaterialTheme.typography.bodySmall,color=MutedText)
        option.detail?.let {d->
         val gearbox=when(d.optString("transmission")){"MECANIQUE"->model.text("Manual","Manuell");"AUTOMATIQUE","VARIATION ELECTRIQUE","VARIATION CONTINUE"->model.text("Automatic","Automatik");else->""}
         val details=listOfNotNull(d.number("engine_cc")?.takeIf {it>0}?.let {"${metric(it/1000,1)} L"},d.number("power_kw")?.takeIf {it>0}?.let {"${metric(it,0)} kW"},gearbox.takeIf {it.isNotBlank()})
         if(details.isNotEmpty())Text(details.joinToString(" · "),style=MaterialTheme.typography.bodySmall,color=MutedText)
         val values=d.getJSONObject("profile_values")
         val consumption=listOfNotNull(values.number("consumption_l_per_100km")?.let {"≈ ${metric(it,2)} L/100 km"},values.number("electricity_kwh_per_100km")?.let {"≈ ${metric(it,2)} kWh/100 km"})
         if(consumption.isNotEmpty())Text(consumption.joinToString(" + "),style=MaterialTheme.typography.bodySmall,color=Pale)
        }
        val specific=option.detail?.optJSONObject("reference")?.optString("basis")?.startsWith("national_")==false
        Text(if(specific)model.text("Catalogue reference","Katalogreferenz")else model.text("UBA fleet average","UBA-Flottenmittelwert"),style=MaterialTheme.typography.labelSmall,color=Mint)};Text("›")
      }}
      if(versions.size>limit)TextButton({limit+=12}){Text(model.text("Show more versions","Weitere Versionen anzeigen"))}
      if(versions.isEmpty())Text(model.text("No matching version","Keine passende Version"),color=MutedText)
     }
     TextButton({selected=null;choosing=false;group=null;tab=1}){Text(model.text("My car is not listed · enter manually","Mein Auto fehlt · manuell eingeben"))}
    }else {
     Surface(color=Panel,shape=MaterialTheme.shapes.large,border=BorderStroke(1.dp,Edge)) {
      Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
       Text(vehicleName,style=MaterialTheme.typography.titleMedium,color=Pale)
       selected?.optString("variant")?.takeIf {it.isNotBlank()}?.let {Text(it,style=MaterialTheme.typography.bodyMedium,color=MutedText)}
       Text(vehicleFuelName(model,fuel),style=MaterialTheme.typography.bodySmall,color=Mint)
       TextButton({choosing=true;group=null;query=""}){Text(model.text("Change car","Auto ändern"))}
      }
     }
     Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
      VehicleUiIcon("fuel");Column {
       Text(if(personal)model.text("Your average consumption","Dein Durchschnittsverbrauch")else model.text("Reference consumption","Referenzverbrauch"),style=MaterialTheme.typography.labelLarge)
       Text(listOfNotNull(usedLitres?.let {"${metric(it,2)} L/100 km"},usedElectricity?.let {"${metric(it,2)} kWh/100 km"}).joinToString(" + ").ifBlank {if(personal)model.text("Enter your average under Personalize","Deinen Durchschnitt unter Anpassen eingeben")else if(selected?.optJSONObject("reference")?.optString("basis")=="homologation_inferred_fossil_consumption")model.text("Derived from official CO₂","Aus offiziellen CO₂-Angaben abgeleitet")else model.text("UBA fleet estimate","UBA-Flottenschätzung")},style=MaterialTheme.typography.bodyLarge,color=Pale)
      }
     }
     if(rate!=null)Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){VehicleUiIcon("leaf");Text(climateAmount(rate)+model.text(" / vehicle-km · estimated"," / Fahrzeug-km · geschätzt"),color=Mint)}
     Text(model.text("Applied only to driving legs. Walking to or from the car does not consume car fuel.","Gilt nur für Fahrabschnitte. Fußwege zum oder vom Auto verbrauchen keinen Autokraftstoff."),style=MaterialTheme.typography.bodySmall,color=MutedText)
     TextButton({tab=1}){Text(model.text("Adjust my consumption","Meinen Verbrauch anpassen"))}
     Disclosure(model.text("Source & calculation","Quelle und Berechnung")) {
      Text(selected?.optString("source")?:model.text("Saved vehicle / UBA reference","Gespeichertes Fahrzeug / UBA-Referenz"),style=MaterialTheme.typography.bodySmall)
      selected?.let {s->Text(s.optString("source_updated_at")+" · "+s.optString("license"),style=MaterialTheme.typography.bodySmall,color=MutedText);Text(s.optString("source_url"),style=MaterialTheme.typography.bodySmall,color=MutedText)
       if(s.optString("market")=="Great Britain")Text("Contains public sector information licensed under the Open Government Licence v3.0.",style=MaterialTheme.typography.bodySmall,color=MutedText)
       Text(if(s.optJSONObject("reference")?.optString("basis")?.startsWith("national_")==true)model.text("No usable consumption for this powertrain is supplied here. The estimate uses a UBA fleet average. For plug-in hybrids you can enter paired fuel and electricity use under Personalize.","Hier liegt kein nutzbarer Verbrauch für diesen Antrieb vor. Die Schätzung nutzt einen UBA-Flottenmittelwert. Für Plug-in-Hybride kannst du unter Anpassen zusammengehörigen Kraftstoff- und Stromverbrauch eingeben.")else model.text("Reference uses the middle of the published consumption range, or its only available value. These are laboratory figures; options and real use can differ.","Referenz nutzt die Mitte der veröffentlichten Verbrauchsspanne oder den einzigen verfügbaren Wert. Laborangaben; Ausstattung und reale Nutzung können abweichen."),style=MaterialTheme.typography.bodySmall,color=MutedText)
      }
      Text(model.text("UBA: operation and energy supply. Vehicle manufacture and infrastructure excluded. The catalogue update date is not the car's model year.","UBA: Betrieb und Energiebereitstellung. Fahrzeugherstellung und Infrastruktur ausgeschlossen. Das Katalogdatum ist kein Modelljahr."),style=MaterialTheme.typography.bodySmall,color=MutedText)
     }
    }
    if(catalogue!=null)Text(model.text("Official catalogues: ADEME France + DfT Great Britain. Some cars or versions may be absent.","Offizielle Kataloge: ADEME Frankreich + DfT Großbritannien. Einzelne Autos oder Versionen können fehlen."),style=MaterialTheme.typography.bodySmall,color=MutedText)
   }else {
    if(selected==null){
     OutlinedTextField(manualName,{manualName=it.take(160)},Modifier.fillMaxWidth(),singleLine=true,enabled=!busy,label={Text(model.text("Car name","Name des Autos"))},leadingIcon={VehicleUiIcon("car")})
     FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("petrol","diesel","electric","hybrid_petrol","unknown").forEach {key->FilterChip(fuel==key,{if(fuel!=key){fuel=key;litres="";electricity="";personal=false}},enabled=!busy,label={Text(vehicleFuelName(model,key))})}}
    }else Text(vehicleName,style=MaterialTheme.typography.titleMedium)
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
     Column(Modifier.weight(1f)){Text(model.text("Use my consumption","Meinen Verbrauch verwenden"),style=MaterialTheme.typography.titleSmall);Text(model.text("Replaces the catalogue estimate for new searches.","Ersetzt die Katalogschätzung bei neuen Suchen."),style=MaterialTheme.typography.bodySmall,color=MutedText)}
     Switch(personal,{personal=it},enabled=!busy&&fuel!="unknown")
    }
    if(personal){
     if(fuel in listOf("petrol","diesel","hybrid_petrol"))OutlinedTextField(litres,{litres=it.take(12)},Modifier.fillMaxWidth(),singleLine=true,enabled=!busy,label={Text(model.text("Average fuel consumption","Durchschnittlicher Kraftstoffverbrauch"))},suffix={Text("L/100 km")},leadingIcon={VehicleUiIcon("fuel")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
     if(fuel in listOf("electric","hybrid_petrol"))OutlinedTextField(electricity,{electricity=it.take(12)},Modifier.fillMaxWidth(),singleLine=true,enabled=!busy,label={Text(model.text("Electricity incl. charging losses","Strom inkl. Ladeverlusten"))},suffix={Text("kWh/100 km")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
     if(rate!=null)Text(climateAmount(rate)+model.text(" / vehicle-km · estimated"," / Fahrzeug-km · geschätzt"),color=Mint)
     if(fuel=="hybrid_petrol")Text(model.text("Enter fuel and electricity averages for the same journeys.","Kraftstoff- und Strommittelwerte für dieselben Fahrten eingeben."),style=MaterialTheme.typography.bodySmall,color=MutedText)
    }
    HorizontalDivider(color=Edge)
    Row(verticalAlignment=Alignment.CenterVertically) {
     Column(Modifier.weight(1f)){Text(model.text("People in the car","Personen im Auto"),style=MaterialTheme.typography.titleSmall);Text(model.text("Including driver · used for per-person comparisons","Inklusive Fahrer · für Vergleiche pro Person"),style=MaterialTheme.typography.bodySmall,color=MutedText)}
     IconButton({occupants--},enabled=!busy&&occupants>1){Text("−")};Text(occupants.toString());IconButton({occupants++},enabled=!busy&&occupants<9){Text("+")}
    }
    Text(model.text("Saved journeys keep their original car and calculation.","Gespeicherte Reisen behalten ihr ursprüngliches Auto und ihre Berechnung."),style=MaterialTheme.typography.bodySmall,color=MutedText)
   }
  }
  failure?.let {Text(it,Modifier.padding(horizontal=16.dp,vertical=6.dp),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
  Button({scope.launch {
   try {
    busy=true;onBusyChanged(true);failure=null
    if(personal&&((fuel in listOf("petrol","diesel","hybrid_petrol")&&decimal(litres)==null)||(fuel in listOf("electric","hybrid_petrol")&&decimal(electricity)==null)))error(model.text("Enter your average consumption","Deinen Durchschnittsverbrauch eingeben"))
    if(personal&&!validPersonalVehicleEnergy(fuel,decimal(litres),decimal(electricity)))error(model.text("Check your consumption. Fuel: up to 100 L/100 km. Electricity: up to 200 kWh/100 km.","Verbrauch prüfen. Kraftstoff: bis 100 L/100 km. Strom: bis 200 kWh/100 km."))
    val value=vehicleProfileDraft(saved,selected,manualName,fuel,personal,if(personal)decimal(litres)else null,if(personal)decimal(electricity)else null,occupants,now())
    withContext(Dispatchers.IO){store.saveVehicleProfile(value,saved?.optString("revision"))}
    model.revision++;onClose()
   }catch(e:CancellationException){throw e}catch(e:Exception){failure=model.text("Could not save: ","Speichern fehlgeschlagen: ")+(e.message?:"")}
   finally{busy=false;onBusyChanged(false)}
  }},enabled=!busy&&(selected!=null||manualName.isNotBlank()||saved!=null),modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp).heightIn(min=48.dp)) {
   if(busy)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)else VehicleUiIcon("car",color=Ink)
   Spacer(Modifier.width(8.dp));Text(if(busy)model.text("Saving…","Wird gespeichert…")else model.text("Save car","Auto speichern"))
  }
 }
}
