// Originally derived from kompendium (https://github.com/bkbnio/kompendium), MIT License
package openapi.schema

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = JsonSchema.SchemaSerializer::class)
sealed interface JsonSchema {

    object SchemaSerializer : KSerializer<JsonSchema> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("JsonSchema", PrimitiveKind.STRING)
        override fun deserialize(decoder: Decoder): JsonSchema = error("JsonSchema deserialization not supported")
        override fun serialize(encoder: Encoder, value: JsonSchema) {
            when (value) {
                is ReferenceDefinition -> ReferenceDefinition.serializer().serialize(encoder, value)
                is TypeDefinition -> TypeDefinition.serializer().serialize(encoder, value)
                is EnumDefinition -> EnumDefinition.serializer().serialize(encoder, value)
                is ArrayDefinition -> ArrayDefinition.serializer().serialize(encoder, value)
                is MapDefinition -> MapDefinition.serializer().serialize(encoder, value)
                is NullableDefinition -> NullableDefinition.serializer().serialize(encoder, value)
                is OneOfDefinition -> OneOfDefinition.serializer().serialize(encoder, value)
                is AnyOfDefinition -> AnyOfDefinition.serializer().serialize(encoder, value)
            }
        }
    }
}

@Serializable
data class TypeDefinition(
    val type: String,
    val format: String? = null,
    val properties: Map<String, JsonSchema>? = null,
    val required: Set<String>? = null,
) : JsonSchema {
    companion object {
        val INT = TypeDefinition(type = "number", format = "int32")
        val LONG = TypeDefinition(type = "number", format = "int64")
        val DOUBLE = TypeDefinition(type = "number", format = "double")
        val FLOAT = TypeDefinition(type = "number", format = "float")
        val STRING = TypeDefinition(type = "string")
        val UUID = TypeDefinition(type = "string", format = "uuid")
        val BOOLEAN = TypeDefinition(type = "boolean")
    }
}

@Serializable
data class ArrayDefinition(
    val items: JsonSchema,
) : JsonSchema {
    val type: String = "array"
}

@Serializable
data class MapDefinition(
    val additionalProperties: JsonSchema,
) : JsonSchema {
    val type: String = "object"
}

@Serializable
data class EnumDefinition(
    val enum: Set<String>,
) : JsonSchema

@Serializable
data class ReferenceDefinition(
    val `$ref`: String,
) : JsonSchema

@Serializable
data class OneOfDefinition(
    val oneOf: Set<JsonSchema>,
) : JsonSchema {
    constructor(vararg types: JsonSchema) : this(types.toSet())
}

@Serializable
data class AnyOfDefinition(
    val anyOf: Set<JsonSchema>,
) : JsonSchema

@Serializable
data class NullableDefinition(
    val type: String = "null",
) : JsonSchema
