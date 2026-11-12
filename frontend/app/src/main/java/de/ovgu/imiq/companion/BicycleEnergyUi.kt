package de.ovgu.imiq.companion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.util.UUID

/** Bicycle options belong to their transport mode, outside the car editor. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun BicycleEnergySettings(model:CompanionModel) {
 val previous=remember(model.revision){model.store.json(VEHICLE_PROFILE)}
 val selected=previous?.optString("bicycle_type","assumed_conventional")?:"assumed_conventional"
 val scope=rememberCoroutineScope();var saving by remember {mutableStateOf(false)}
 Disclosure(model.text("Bicycle assistance","Fahrradunterstützung")) {
  FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
   listOf("assumed_conventional","conventional","electric").forEach {key->
    FilterChip(selected==key,{
     scope.launch {
      try {
       saving=true
       val value=previous?.copyJson()?:obj("schema_version" to "companion-vehicle-profile-v2","profile_id" to "vehicle-${UUID.randomUUID()}","fuel" to "unknown","occupants" to 1,"consumption_mode" to "uba","catalogue" to null)
       value.put("revision",UUID.randomUUID().toString()).put("updated_at",now()).put("bicycle_type",key)
       withContext(Dispatchers.IO){model.store.saveVehicleProfile(value,previous?.optString("revision"))};model.revision++
      }catch(e:CancellationException){throw e}catch(e:Exception){model.error=e.message}finally{saving=false}
     }
    },enabled=!saving,label={Text(when(key){"conventional"->model.text("Conventional","Konventionell");"electric"->model.text("Assisted","Mit Unterstützung");else->model.text("Unspecified","Nicht angegeben")})})
   }
  }
 }
}
