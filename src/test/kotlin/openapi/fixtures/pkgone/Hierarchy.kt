@file:OptIn(ExperimentalSerializationApi::class)

package openapi.fixtures.pkgone

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
sealed interface Hierarchy {
    @Serializable
    @JsonClassDiscriminator("kind")
    sealed interface Inner : Hierarchy {
        @Serializable
        @SerialName("NESTED")
        data class Nested(val one: Int) : Inner
    }
}
