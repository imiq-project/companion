package de.ovgu.imiq.companion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import org.json.JSONObject

/** Sprite metadata shared by the in-app companion and priority feedback. */
@Composable internal fun companionSpriteMetadata(key:String):JSONObject {
 val context=LocalContext.current
 return remember(key){context.assets.open("art/jrpg/companion-intro-atlas.json").bufferedReader().use {JSONObject(it.readText()).getJSONObject(key)}}
}
