package de.ovgu.imiq.companion

import org.json.JSONObject

internal fun departureBoardLive(board:JSONObject?,now:Long):Boolean {
 val status=board?.optJSONObject("realtime_status")?:return false
 val stamp=status.number("feed_timestamp")?:return false
 val age=now/1000.0-stamp
 return board.optBoolean("realtime_available")&&status.optBoolean("ready")&&status.optBoolean("dataset_compatible")&&age in 0.0..180.0
}
internal fun departureBoardPrediction(board:JSONObject?,row:JSONObject,now:Long):Double? =
 if(departureBoardLive(board,now)&&row.optBoolean("realtime"))row.number("predicted_departure_ms")else null
