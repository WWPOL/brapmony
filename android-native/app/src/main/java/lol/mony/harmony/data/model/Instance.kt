package lol.mony.harmony.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One row of `instance_config`: the instance's name, domain and feature switches. */
@Serializable
data class InstanceConfigRow(
    @SerialName("config_key") val configKey: String,
    @SerialName("config_value") val configValue: kotlinx.serialization.json.JsonElement? = null,
    val description: String? = null,
)

/** The instance settings the client reads, resolved from `instance_config`. */
data class InstanceInfo(
    val name: String = "Harmony",
    val domain: String? = null,
    val description: String? = null,
    val registrationOpen: Boolean = true,
    val iconUrl: String? = null,
    val raw: Map<String, String> = emptyMap(),
)
