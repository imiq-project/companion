package de.ovgu.imiq.companion

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal const val PANEL_AUTH_PATH="/api/companion/auth/panel"
internal const val PANEL_LOGIN_MODE="companion.panel-login.required.v1"
internal class PanelUploadPaused:IllegalStateException()

/** Separate from SQLite/export: AES-GCM key is non-exportable Android Keystore material. */
internal class PanelSessionStore(context:Context) {
 private val preferences=context.getSharedPreferences("companion-panel-session-v1",Context.MODE_PRIVATE)
 private fun key():SecretKey {
  val alias="companion-panel-session-v1"
  val keys=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
  return (keys.getKey(alias,null) as? SecretKey)?:KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
   init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
  }.generateKey()
 }
 fun read():JSONObject?=preferences.getString("encrypted",null)?.let {encoded->
  runCatching {
   val bytes=Base64.decode(encoded,Base64.NO_WRAP);require(bytes.size>28)
   val cipher=Cipher.getInstance("AES/GCM/NoPadding")
   cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
   JSONObject(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8))
  }.getOrElse {throw IllegalStateException("Unable to unlock panel session",it)}
 }
 fun write(value:JSONObject) {
  val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
  val encoded=Base64.encodeToString(cipher.iv+cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8)),Base64.NO_WRAP)
  check(preferences.edit().putString("encrypted",encoded).commit()) {"Unable to save panel session"}
 }
 fun clear(){check(preferences.edit().remove("encrypted").commit()) {"Unable to clear panel session"}}
}

internal fun safePanelBackend(base:String):Boolean=runCatching {
 val uri=URI(base)
 uri.userInfo==null&&uri.query==null&&uri.fragment==null&&
  (uri.scheme=="https"||uri.scheme=="http"&&uri.host in listOf("localhost","127.0.0.1","::1","10.0.2.2"))
}.getOrDefault(false)

internal fun validatePanelIdentity(value:JSONObject):JSONObject {
 require(value.optString("participant_id").matches(Regex("[0-9a-f]{64}")))
 require(value.optString("participant_code").matches(Regex("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{9}")))
 require(value.optString("study_id").matches(Regex("[A-Za-z0-9_-]{1,64}")))
 val origin=URI(value.getString("panel_origin"));require(origin.scheme=="https"&&origin.host!=null&&origin.userInfo==null&&origin.query==null&&origin.fragment==null&&origin.path.isEmpty())
 require(value.optString("study_membership")=="not_verified"&&value.optString("study_consent")=="not_verified"&&value.opt("profile_transfer_authorized")==false)
 if(value.optBoolean("local_test_profile_upload")) {
  require(value.optString("test_scope")=="local-panel-account-test"&&value.getString("study_id")=="app_imiq_test"&&value.getString("panel_origin")=="https://imiq-staging.et.uni-magdeburg.de")
  val receipt=value.getJSONObject("identity_storage")
  require(receipt.opt("identity_stored_on_server")==true&&receipt.optString("participant_id")==value.getString("participant_id")&&
   receipt.optString("database")=="companion_test"&&receipt.optString("collection")=="participants"&&receipt.optString("scope")=="local-panel-account-test")
 }
 return value.copyJson()
}

/** Frozen outbox ownership, without bearer credentials or the panel account's PII. */
internal fun panelUploadBinding(identity:JSONObject):JSONObject {
 val verified=validatePanelIdentity(identity)
 require(verified.optBoolean("local_test_profile_upload"))
 return obj(*listOf("participant_id","study_id","panel_origin","test_scope").map {it to verified.get(it)}.toTypedArray())
}

internal suspend fun Api.panelRequest(path:String,payload:JSONObject?=null,access:String?=null,expectedBase:String=base()):JSONObject=withContext(Dispatchers.IO) {
 require(safePanelBackend(expectedBase)&&base()==expectedBase) {"Use HTTPS or the local USB backend for login"}
 apiJsonRequest(expectedBase+PANEL_AUTH_PATH+path,payload,timeout=30000,bearer=access)
}
