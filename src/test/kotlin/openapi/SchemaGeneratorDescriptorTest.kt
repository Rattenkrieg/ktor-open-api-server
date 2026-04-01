package openapi

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import openapi.schema.AnyOfDefinition
import openapi.schema.ArrayDefinition
import openapi.schema.EnumDefinition
import openapi.schema.JsonSchema
import openapi.schema.MapDefinition
import openapi.schema.OneOfDefinition
import io.kotest.matchers.nulls.shouldNotBeNull
import openapi.schema.NullableDefinition
import openapi.schema.ReferenceDefinition
import openapi.schema.SchemaGenerator
import openapi.schema.TypeDefinition
import openapi.schema.slug

@Serializable
data class DescSimpleUser(val name: String, val age: Int)

@Serializable
data class DescAddress(val street: String, val city: String, val zip: String)

@Serializable
data class DescUserWithAddress(val user: DescSimpleUser, val address: DescAddress)

@Serializable
data class DescOptionalFields(val required: String, val optional: String = "default", val nullable: String?)

@Serializable
data class DescWithList(val items: List<String>, val nested: List<DescSimpleUser>)

@Serializable
data class DescWithMap(val tags: Map<String, String>, val nested: Map<String, DescSimpleUser>)

@Serializable
enum class DescColor { RED, GREEN, BLUE }

@Serializable
data class DescWithEnum(val color: DescColor)

@Serializable
sealed interface DescShape {
    @Serializable
    @SerialName("circle")
    data class Circle(val radius: Double) : DescShape

    @Serializable
    @SerialName("rectangle")
    data class Rectangle(val width: Double, val height: Double) : DescShape
}

@Serializable
data class DescWithNullableNested(val user: DescSimpleUser?)

@Serializable
@JvmInline
value class DescUserId(val id: String)

@Serializable
data class DescWithInlineClass(val userId: DescUserId, val name: String)

@Serializable
data class DescWithSerialName(@SerialName("user_name") val userName: String, val age: Int)

object UUIDAsStringSerializer : KSerializer<java.util.UUID> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: java.util.UUID) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): java.util.UUID = java.util.UUID.fromString(decoder.decodeString())
}

@Serializable
data class DescWithNullableList(val items: List<String>?)

@Serializable
data class DescAllPrimitives(
    val s: String,
    val i: Int,
    val l: Long,
    val d: Double,
    val f: Float,
    val b: Boolean,
)

@Serializable
data class DescWithContextualUuid(val id: @Contextual java.util.UUID, val name: String)

@Serializable
data class DescWithContextualInstant(val createdAt: @Contextual java.time.Instant)

@JvmInline
value class DescModelId(val id: @Contextual java.util.UUID)

@Serializable
data class DescWithValueClassId(val id: @Contextual DescModelId, val name: String)

@Serializable
data class DescWithNullableObject(val address: DescAddress?)

@Serializable
data class DescWithListOfContextualIds(val ids: List<@Contextual java.util.UUID>)

@Serializable
data class DescWithSetOfStrings(val emails: Set<String>)

object SlugCollisionA {
    @Serializable
    data class Status(val code: Int)
}

object SlugCollisionB {
    @Serializable
    data class Status(val message: String)
}

class SchemaGeneratorDescriptorTest : ShouldSpec({

    val json = Json.Default

    fun generate(descriptor: SerialDescriptor): Pair<JsonSchema, MutableMap<String, JsonSchema>> {
        val cache = mutableMapOf<String, JsonSchema>()
        val schema = SchemaGenerator.fromDescriptor(descriptor, json, cache)
        return schema to cache
    }

    should("generate schema for all primitive types") {
        val (schema, _) = generate(DescAllPrimitives.serializer().descriptor)
        schema.shouldBeInstanceOf<TypeDefinition>()
        schema.type shouldBe "object"
        val props = schema.properties!!
        props["s"] shouldBe TypeDefinition.STRING
        props["i"] shouldBe TypeDefinition.INT
        props["l"] shouldBe TypeDefinition.LONG
        props["d"] shouldBe TypeDefinition.DOUBLE
        props["f"] shouldBe TypeDefinition.FLOAT
        props["b"] shouldBe TypeDefinition.BOOLEAN
        schema.required shouldBe setOf("s", "i", "l", "d", "f", "b")
    }

    should("generate schema for simple data class") {
        val (schema, _) = generate(DescSimpleUser.serializer().descriptor)
        schema.shouldBeInstanceOf<TypeDefinition>()
        schema.type shouldBe "object"
        schema.properties!!.keys shouldBe setOf("name", "age")
        schema.properties!!["name"] shouldBe TypeDefinition.STRING
        schema.properties!!["age"] shouldBe TypeDefinition.INT
        schema.required shouldBe setOf("name", "age")
    }

    should("generate schema for nested object with references") {
        val (schema, cache) = generate(DescUserWithAddress.serializer().descriptor)
        schema.shouldBeInstanceOf<TypeDefinition>()
        schema.type shouldBe "object"
        schema.properties!!["user"].shouldBeInstanceOf<ReferenceDefinition>()
        schema.properties!!["address"].shouldBeInstanceOf<ReferenceDefinition>()
        // Cache should contain the nested types
        cache.values.any { it is TypeDefinition && (it as TypeDefinition).properties?.containsKey("street") == true } shouldBe true
    }

    should("handle optional and nullable fields") {
        val (schema, _) = generate(DescOptionalFields.serializer().descriptor)
        schema.shouldBeInstanceOf<TypeDefinition>()
        // "required" is required, "optional" has default, "nullable" is nullable
        schema.required shouldBe setOf("required")
        // nullable field should be OneOfDefinition with null
        val nullableProp = schema.properties!!["nullable"]
        nullableProp.shouldBeInstanceOf<OneOfDefinition>()
        nullableProp.oneOf.any { it is NullableDefinition } shouldBe true
    }

    should("generate schema for list of primitives") {
        val (schema, _) = generate(DescWithList.serializer().descriptor)
        schema.shouldBeInstanceOf<TypeDefinition>()
        val itemsProp = schema.properties!!["items"]
        itemsProp.shouldBeInstanceOf<ArrayDefinition>()
        itemsProp.items shouldBe TypeDefinition.STRING
    }

    should("generate schema for list of objects") {
        val (schema, _) = generate(DescWithList.serializer().descriptor)
        schema.shouldBeInstanceOf<TypeDefinition>()
        val nestedProp = schema.properties!!["nested"]
        nestedProp.shouldBeInstanceOf<ArrayDefinition>()
        nestedProp.items.shouldBeInstanceOf<ReferenceDefinition>()
    }

    should("generate schema for map of primitives") {
        val (schema, _) = generate(DescWithMap.serializer().descriptor)
        schema.shouldBeInstanceOf<TypeDefinition>()
        val tagsProp = schema.properties!!["tags"]
        tagsProp.shouldBeInstanceOf<MapDefinition>()
        tagsProp.additionalProperties shouldBe TypeDefinition.STRING
    }

    should("generate schema for map of objects") {
        val (schema, _) = generate(DescWithMap.serializer().descriptor)
        schema.shouldBeInstanceOf<TypeDefinition>()
        val nestedProp = schema.properties!!["nested"]
        nestedProp.shouldBeInstanceOf<MapDefinition>()
        nestedProp.additionalProperties.shouldBeInstanceOf<ReferenceDefinition>()
    }

    should("generate schema for enum") {
        val (schema, _) = generate(DescColor.serializer().descriptor)
        schema.shouldBeInstanceOf<EnumDefinition>()
        schema.enum shouldBe setOf("RED", "GREEN", "BLUE")
    }

    should("generate schema for class with enum property") {
        val (schema, cache) = generate(DescWithEnum.serializer().descriptor)
        schema.shouldBeInstanceOf<TypeDefinition>()
        val colorProp = schema.properties!!["color"]
        colorProp.shouldBeInstanceOf<ReferenceDefinition>()
        // Enum should be in cache
        cache.values.any { it is EnumDefinition } shouldBe true
    }

    should("generate schema for nullable nested object") {
        val (schema, _) = generate(DescWithNullableNested.serializer().descriptor)
        schema.shouldBeInstanceOf<TypeDefinition>()
        val userProp = schema.properties!!["user"]
        userProp.shouldBeInstanceOf<OneOfDefinition>()
        userProp.oneOf.any { it is NullableDefinition } shouldBe true
        userProp.oneOf.any { it is ReferenceDefinition } shouldBe true
    }

    should("generate schema for inline/value class (unwraps to inner type)") {
        val (schema, _) = generate(DescWithInlineClass.serializer().descriptor)
        schema.shouldBeInstanceOf<TypeDefinition>()
        // The userId field should be unwrapped to String since DescUserId is a value class wrapping String
        schema.properties!!["userId"] shouldBe TypeDefinition.STRING
        schema.properties!!["name"] shouldBe TypeDefinition.STRING
    }

    should("respect @SerialName on properties") {
        val (schema, _) = generate(DescWithSerialName.serializer().descriptor)
        schema.shouldBeInstanceOf<TypeDefinition>()
        schema.properties!!.keys shouldBe setOf("user_name", "age")
        schema.properties!!["user_name"] shouldBe TypeDefinition.STRING
    }

    should("generate schema for sealed interface with discriminator") {
        val descriptor = DescShape.serializer().descriptor
        val (schema, cache) = generate(descriptor)
        schema.shouldBeInstanceOf<AnyOfDefinition>()
        schema.anyOf.size shouldBe 2
        // Each variant should have a "type" discriminator in cache
        val objectSchemas = cache.values.filterIsInstance<TypeDefinition>().filter { it.type == "object" }
        objectSchemas.any { it.properties?.containsKey("type") == true } shouldBe true
    }

    should("generate schema for contextual type with custom serializer module") {
        val module = SerializersModule {
            contextual(java.util.UUID::class, UUIDAsStringSerializer)
        }
        val customJson = Json { serializersModule = module }

        @Serializable
        data class WithUuid(@Serializable(with = UUIDAsStringSerializer::class) val id: java.util.UUID)

        val cache = mutableMapOf<String, JsonSchema>()
        val schema = SchemaGenerator.fromDescriptor(WithUuid.serializer().descriptor, customJson, cache)
        schema.shouldBeInstanceOf<TypeDefinition>()
        // The id field should resolve to string (UUID serializer uses PrimitiveKind.STRING)
        schema.properties!!["id"] shouldBe TypeDefinition.STRING
    }

    should("handle empty object (no properties)") {
        @Serializable
        class EmptyObj

        val (schema, _) = generate(EmptyObj.serializer().descriptor)
        schema.shouldBeInstanceOf<TypeDefinition>()
        schema.type shouldBe "object"
    }

    should("use cache to avoid re-generating same type") {
        val cache = mutableMapOf<String, JsonSchema>()
        val descriptor = DescSimpleUser.serializer().descriptor
        val schema1 = SchemaGenerator.fromDescriptor(descriptor, json, cache)
        val schema2 = SchemaGenerator.fromDescriptor(descriptor, json, cache)
        // Second call should return cached value
        schema1.shouldBeInstanceOf<TypeDefinition>()
        schema2.shouldBeInstanceOf<TypeDefinition>()
    }

    should("generate string for standalone String descriptor") {
        val (schema, _) = generate(String.serializer().descriptor)
        schema shouldBe TypeDefinition.STRING
    }

    should("generate int for standalone Int descriptor") {
        val (schema, _) = generate(Int.serializer().descriptor)
        schema shouldBe TypeDefinition.INT
    }

    should("generate long for standalone Long descriptor") {
        val (schema, _) = generate(Long.serializer().descriptor)
        schema shouldBe TypeDefinition.LONG
    }

    should("generate boolean for standalone Boolean descriptor") {
        val (schema, _) = generate(Boolean.serializer().descriptor)
        schema shouldBe TypeDefinition.BOOLEAN
    }

    should("generate double for standalone Double descriptor") {
        val (schema, _) = generate(Double.serializer().descriptor)
        schema shouldBe TypeDefinition.DOUBLE
    }

    should("generate float for standalone Float descriptor") {
        val (schema, _) = generate(Float.serializer().descriptor)
        schema shouldBe TypeDefinition.FLOAT
    }

    should("resolve @Contextual UUID via serializersModule") {
        val module = SerializersModule {
            contextual(java.util.UUID::class, UUIDAsStringSerializer)
        }
        val customJson = Json { serializersModule = module }
        val cache = mutableMapOf<String, JsonSchema>()
        val schema = SchemaGenerator.fromDescriptor(
            DescWithContextualUuid.serializer().descriptor, customJson, cache,
        )
        schema.shouldBeInstanceOf<TypeDefinition>()
        schema.properties!!["id"] shouldBe TypeDefinition.STRING
        schema.properties!!["name"] shouldBe TypeDefinition.STRING
    }

    should("produce different slugs for same-named classes in different enclosing objects") {
        val slugA = SlugCollisionA.Status.serializer().descriptor.slug()
        val slugB = SlugCollisionB.Status.serializer().descriptor.slug()
        slugA shouldBe "SlugCollisionAStatus"
        slugB shouldBe "SlugCollisionBStatus"
    }

    should("produce different reference slugs for same-named classes") {
        val cache = mutableMapOf<String, JsonSchema>()
        val customJson = Json.Default
        SchemaGenerator.fromDescriptor(SlugCollisionA.Status.serializer().descriptor, customJson, cache)
        SchemaGenerator.fromDescriptor(SlugCollisionB.Status.serializer().descriptor, customJson, cache)
        cache.keys.filter { it.contains("Status") }.size shouldBe 2
    }

    should("fall back to well-known UUID schema when no module serializer registered") {
        val noContextJson = Json.Default
        val cache = mutableMapOf<String, JsonSchema>()
        val schema = SchemaGenerator.fromDescriptor(
            DescWithContextualUuid.serializer().descriptor, noContextJson, cache,
        )
        schema.shouldBeInstanceOf<TypeDefinition>()
        schema.properties!!["id"] shouldBe TypeDefinition.UUID
        schema.properties!!["name"] shouldBe TypeDefinition.STRING
    }

    should("fall back to well-known Instant schema when no module serializer registered") {
        val noContextJson = Json.Default
        val cache = mutableMapOf<String, JsonSchema>()
        val schema = SchemaGenerator.fromDescriptor(
            DescWithContextualInstant.serializer().descriptor, noContextJson, cache,
        )
        schema.shouldBeInstanceOf<TypeDefinition>()
        schema.properties!!["createdAt"] shouldBe TypeDefinition(type = "string", format = "date-time")
    }

    should("unwrap value class wrapping @Contextual UUID to string/uuid") {
        val cache = mutableMapOf<String, JsonSchema>()
        val schema = SchemaGenerator.fromDescriptor(
            DescWithValueClassId.serializer().descriptor, Json.Default, cache,
        )
        schema.shouldBeInstanceOf<TypeDefinition>()
        schema.properties!!["id"] shouldBe TypeDefinition.UUID
        schema.properties!!["name"] shouldBe TypeDefinition.STRING
    }

    should("generate array schema for List of @Contextual UUID") {
        val cache = mutableMapOf<String, JsonSchema>()
        val schema = SchemaGenerator.fromDescriptor(
            DescWithListOfContextualIds.serializer().descriptor, Json.Default, cache,
        )
        schema.shouldBeInstanceOf<TypeDefinition>()
        val idsProp = schema.properties!!["ids"]
        idsProp.shouldBeInstanceOf<ArrayDefinition>()
        idsProp.items shouldBe TypeDefinition.UUID
    }

    should("generate array schema for Set of String") {
        val cache = mutableMapOf<String, JsonSchema>()
        val schema = SchemaGenerator.fromDescriptor(
            DescWithSetOfStrings.serializer().descriptor, Json.Default, cache,
        )
        schema.shouldBeInstanceOf<TypeDefinition>()
        val emailsProp = schema.properties!!["emails"]
        emailsProp.shouldBeInstanceOf<ArrayDefinition>()
        emailsProp.items shouldBe TypeDefinition.STRING
    }

    should("produce slug without ? for nullable types") {
        val descriptor = DescWithNullableObject.serializer().descriptor
        val addressDescriptor = descriptor.getElementDescriptor(0)
        val addressSlug = addressDescriptor.slug()
        addressSlug.contains("?") shouldBe false
    }
})
