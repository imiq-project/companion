package de.ovgu.imiq.companion

import org.json.JSONObject

/** A presentation policy, not a migration of saved participant/profile evidence. */
internal fun imdilloPresentation(saved:JSONObject?):JSONObject = (saved?.copyJson()
 ?:obj("version" to 1,"palette" to "mint")).put("name","Imdillo").put("appearance","imdillo")
