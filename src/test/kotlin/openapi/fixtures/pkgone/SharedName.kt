package openapi.fixtures.pkgone

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
@SerialName("SharedName")
data class SharedName(val code: Int)
