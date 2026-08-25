package openapi.fixtures.pkgtwo

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
@SerialName("SharedName")
data class SharedName(val message: String)
