package lol.mony.harmony.data.model

import kotlinx.serialization.json.Json

/** The JSON dialect the instance speaks: camelCase names, absent fields left at their defaults. */
val HarmonyJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = true
}
