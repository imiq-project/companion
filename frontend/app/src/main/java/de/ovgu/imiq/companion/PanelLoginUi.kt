package de.ovgu.imiq.companion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable internal fun PanelLoginScreen(model:CompanionModel) {
 var email by remember {mutableStateOf("")}
 // Never save passwords in savedInstanceState, preferences or a ViewModel.
 var password by remember {mutableStateOf("")}
 var backend by remember {mutableStateOf(model.api.base())}
 ScreenColumn {
  PixelCard {
   Heading(model.text("Sign in to Companion","Bei Companion anmelden"))
   Copy(model.text("Use the email and password of your IMIQ panel account. Your participant code is retrieved automatically.","Verwende E-Mail und Passwort deines IMIQ-Panelkontos. Dein Teilnahmecode wird automatisch abgerufen."))
   OutlinedTextField(email,{email=it},Modifier.fillMaxWidth(),label={Text(model.text("Email","E-Mail"))},singleLine=true,enabled=!model.panelLoginBusy,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email))
   OutlinedTextField(password,{password=it},Modifier.fillMaxWidth(),label={Text(model.text("Password","Passwort"))},singleLine=true,enabled=!model.panelLoginBusy,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password))
   if(model.panelLoginBusy)CircularProgressIndicator(Modifier.size(24.dp),color=Mint)
   model.panelLoginError?.let {Copy(it,MaterialTheme.colorScheme.error)}
   PixelButton(model.text("Sign in","Anmelden"),{
    model.signInPanel(email,password);password=""
   },Modifier.fillMaxWidth(),enabled=!model.panelLoginBusy&&email.isNotBlank()&&password.isNotEmpty(),primary=true)
   Copy(model.text("Already registered in the panel? Use that account; you do not need a new participant code.","Bereits im Panel registriert? Nutze dieses Konto; du benötigst keinen neuen Teilnahmecode."))
  }
  Disclosure(model.text("Connection","Verbindung")) {
   OutlinedTextField(backend,{backend=it},Modifier.fillMaxWidth(),label={Text(model.text("Companion backend URL","Companion-Backend-URL"))},singleLine=true,enabled=!model.panelLoginBusy,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri))
   PixelButton(model.text("Save connection","Verbindung speichern"),{model.setPanelBackend(backend)},enabled=!model.panelLoginBusy)
  }
 }
}

@Composable internal fun PanelAccountCard(model:CompanionModel) {
 val identity=model.panelIdentity?:return
 PixelCard {
  Heading(model.text("Panel account","Panelkonto"))
  Copy(model.text("Participant code: ","Teilnahmecode: ")+identity.getString("participant_code"))
  Copy(model.text("Study: ","Studie: ")+identity.getString("study_id"))
  Copy(if(identity.optBoolean("local_test_profile_upload"))model.text("Test mode: your participant identity is saved on the server. New confirmed profiles upload automatically.","Testmodus: Deine Teilnehmerkennung ist auf dem Server gespeichert. Neue bestätigte Profile werden automatisch übertragen.")else model.text("Signed in. Study authorization is still being connected; profile uploads are paused.","Angemeldet. Die Studienfreigabe wird noch angebunden; Profilübertragungen sind pausiert."))
  PixelButton(model.text("Sign out","Abmelden"),model::signOutPanel,enabled=!model.panelLoginBusy&&model.store.json(ACTIVE_TRIP)==null&&model.store.json(ACTIVE_PLAYBACK)==null)
 }
}
