@file:OptIn(ExperimentalSerializationApi::class)

package openapi

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.Contextual
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlinx.serialization.serializer
import kotlinx.serialization.modules.contextual
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import openapi.schema.AnyOfDefinition
import openapi.schema.ArrayDefinition
import openapi.schema.EnumDefinition
import openapi.schema.IntEnumDefinition
import openapi.schema.JsonSchema
import openapi.schema.MapDefinition
import openapi.schema.OneOfDefinition
import io.kotest.matchers.nulls.shouldNotBeNull
import openapi.schema.NullableDefinition
import openapi.schema.ReferenceDefinition
import openapi.schema.ClassIdentity
import openapi.schema.SchemaGenerator
import openapi.schema.SchemaSlug
import openapi.schema.TypeDefinition
import openapi.schema.UnsupportedSchemaException
import openapi.schema.slug
import openapi.fixtures.pkgone.Hierarchy as PkgOneHierarchy
import openapi.fixtures.pkgone.Status as PkgOneStatus
import openapi.fixtures.pkgtwo.Hierarchy as PkgTwoHierarchy
import openapi.fixtures.pkgtwo.Status as PkgTwoStatus
import openapi.fixtures.pkgone.SharedName as SharedNameOne
import openapi.fixtures.pkgtwo.SharedName as SharedNameTwo

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
sealed interface DescOutcome {
    @Serializable
    @SerialName("succeeded")
    data class Succeeded(val at: String) : DescOutcome

    @Serializable
    @SerialName("cancelled")
    data object Cancelled : DescOutcome
}

@Serializable
@JsonClassDiscriminator("status")
sealed interface DescAnnotatedResult {
    @Serializable
    @SerialName("CREATED")
    data class Created(val id: String) : DescAnnotatedResult

    @Serializable
    @SerialName("FAILED")
    data class Failed(val reason: String) : DescAnnotatedResult

    @Serializable
    @SerialName("PENDING")
    data object Pending : DescAnnotatedResult {
        val label = "pending"
    }
}

// LEAF is a variant of both hierarchies: kotlinx tags it "type" as DescNestedOuter and "kind" as Inner.
@Serializable
sealed interface DescNestedOuter {
    @Serializable
    @JsonClassDiscriminator("kind")
    sealed interface Inner : DescNestedOuter {
        @Serializable
        @SerialName("LEAF")
        data class Leaf(val value: Int) : Inner
    }

    @Serializable
    @SerialName("OTHER")
    data class Other(val value: Int) : DescNestedOuter
}

@Serializable
@JsonClassDiscriminator("status")
sealed interface DescInheritingOuter {
    @Serializable
    sealed interface Inner : DescInheritingOuter {
        @Serializable
        @SerialName("INHERITED")
        data class Leaf(val value: Int) : Inner
    }
}

@Serializable
@SerialName("DescNestedOuterLEAF")
data class DescHierarchySlugSquatter(val other: String)

@Serializable
@SerialName("LEAF")
data class DescPlainSlugSquatter(val another: String)

@Serializable
sealed interface DescUnnamedVariants {
    @Serializable
    data class First(val value: Int) : DescUnnamedVariants
}

sealed class DescPlainSealed {
    data class First(val value: Int) : DescPlainSealed()

    data object Second : DescPlainSealed()
}

@Serializable
@JsonClassDiscriminator("status")
sealed interface DescClashingVariant {
    @Serializable
    @SerialName("CLASH")
    data class Clash(val status: String) : DescClashingVariant
}

object DescCustomVariantSerializer : KSerializer<DescCustomSealed.Custom> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("CUSTOM") { element<String>("v") }
    override fun serialize(encoder: Encoder, value: DescCustomSealed.Custom) =
        encoder.encodeStructure(descriptor) { encodeStringElement(descriptor, 0, value.v) }
    override fun deserialize(decoder: Decoder): DescCustomSealed.Custom = decoder.decodeStructure(descriptor) {
        decodeElementIndex(descriptor)
        DescCustomSealed.Custom(decodeStringElement(descriptor, 0))
    }
}

@Serializable
sealed interface DescCustomSealed {
    @Serializable(with = DescCustomVariantSerializer::class)
    data class Custom(val v: String) : DescCustomSealed
}

// ADD inherits "op" from Binary, but inside DescExpr kotlinx tags it "type"; ADD and NEG both refer back to DescExpr.
@Serializable
sealed interface DescExpr {
    @Serializable
    @JsonClassDiscriminator("op")
    sealed interface Binary : DescExpr {
        @Serializable
        @SerialName("ADD")
        data class Add(val left: DescExpr, val right: DescExpr) : Binary
    }

    @Serializable
    @SerialName("NEG")
    data class Neg(val operand: DescExpr) : DescExpr

    @Serializable
    @SerialName("LIT")
    data class Lit(val value: Int) : DescExpr
}

// Both NONE variants have the same serial name and shape, so slug resolution sees one class identity.
@Serializable
@JsonClassDiscriminator("status")
sealed interface DescStatusOutcome {
    @Serializable
    @SerialName("NONE")
    data object None : DescStatusOutcome

    @Serializable
    @SerialName("OK")
    data class Ok(val id: String) : DescStatusOutcome
}

@Serializable
@JsonClassDiscriminator("state")
sealed interface DescStateOutcome {
    @Serializable
    @SerialName("NONE")
    data object None : DescStateOutcome

    @Serializable
    @SerialName("DONE")
    data class Done(val id: String) : DescStateOutcome

    @Serializable
    @SerialName("OK")
    data class Ok(val id: String) : DescStateOutcome
}

@Serializable
data class DescOkHolder(val ok: DescStatusOutcome.Ok)

@Serializable
sealed interface DescDottedSecond

// Both Leaf variants shorten to "Leaf" under DescDottedOuter, which tags them with a name that is not their own.
@Serializable
sealed interface DescDottedOuter {
    @Serializable
    @JsonClassDiscriminator("kind")
    sealed interface Inner : DescDottedOuter, DescDottedSecond {
        @Serializable
        @SerialName("x.Leaf")
        data class XLeaf(val x: Int) : Inner

        @Serializable
        @SerialName("y.Leaf")
        data class YLeaf(val y: String) : Inner

        @Serializable
        data class Unnamed(val z: Int) : Inner
    }

    @Serializable
    @SerialName("OTHER")
    data class Other(val value: Int) : DescDottedOuter
}

// Not @Serializable: kotlinx encodes it through a runtime PolymorphicSerializer, which ignores the annotation.
@JsonClassDiscriminator("status")
sealed interface DescModuleSealed {
    @Serializable
    @SerialName("MODULE")
    data class Variant(val value: Int) : DescModuleSealed
}

@Serializable
sealed interface DescOddVariants {
    @Serializable
    enum class Mode : DescOddVariants { ON }

    @Serializable
    data class Broken(val nothing: Unit) : DescOddVariants

    @Serializable
    data class Fine(val value: Int) : DescOddVariants
}

sealed interface DescBox<T> {
    data class Full<T>(val item: T) : DescBox<T>

    data class Empty<T>(val reason: String) : DescBox<T>
}

sealed interface DescGenericOuter<T> {
    sealed interface Inner<T> : DescGenericOuter<T> {
        data class Leaf(val s: String) : Inner<String>
    }

    data class Other<T>(val t: T) : DescGenericOuter<T>
}

// Two unrelated sealed hierarchies whose variants share an explicit @SerialName.
@Serializable
sealed interface DescSealedCollisionOne {
    @Serializable
    @SerialName("SHARED_VARIANT")
    data class SharedVariant(val fieldOne: String) : DescSealedCollisionOne
}

@Serializable
sealed interface DescSealedCollisionTwo {
    @Serializable
    @SerialName("SHARED_VARIANT")
    data class SharedVariant(val fieldTwo: Int) : DescSealedCollisionTwo
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

@Serializable
data class DescTwoRefsToSameType(val first: DescSimpleUser, val second: DescSimpleUser)

@Serializable
data class DescWithCollidingStatuses(val one: PkgOneStatus, val two: PkgTwoStatus)

@Serializable(with = DescCustomCountry.CountrySerializer::class)
data class DescCustomCountry(val code: String) {
    object CountrySerializer : KSerializer<DescCustomCountry> {
        override val descriptor: SerialDescriptor = buildClassSerialDescriptor("DescCustomCountry") {
            element<String>("code")
        }
        override fun serialize(encoder: Encoder, value: DescCustomCountry): Unit = TODO("not used in test")
        override fun deserialize(decoder: Decoder): DescCustomCountry = TODO("not used in test")
    }
}

@Serializable(with = DescCustomAddress.AddressSerializer::class)
data class DescCustomAddress(val city: String, val country: DescCustomCountry) {
    object AddressSerializer : KSerializer<DescCustomAddress> {
        override val descriptor: SerialDescriptor = buildClassSerialDescriptor("DescCustomAddress") {
            element<String>("city")
            element<DescCustomCountry>("country")
        }
        override fun serialize(encoder: Encoder, value: DescCustomAddress): Unit = TODO("not used in test")
        override fun deserialize(decoder: Decoder): DescCustomAddress = TODO("not used in test")
    }
}

@Serializable
data class DescHolderWithCustomAddressOne(val address: DescCustomAddress?)

@Serializable
data class DescHolderWithCustomAddressTwo(val address: DescCustomAddress?)

object DescCustomInstantSerializer : KSerializer<java.time.Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: java.time.Instant) = TODO("not used in test")
    override fun deserialize(decoder: Decoder): java.time.Instant = TODO("not used in test")
}

@Serializable
data class DescWithContextualInstantField(val createdAt: @Contextual java.time.Instant)

class SchemaGeneratorDescriptorTest : ShouldSpec({

    val json = Json.Default

    fun generate(descriptor: SerialDescriptor): Pair<JsonSchema, MutableMap<String, JsonSchema>> {
        val cache = mutableMapOf<String, JsonSchema>()
        val schema = SchemaGenerator.fromDescriptor(descriptor, json, cache, mutableMapOf())
        return schema to cache
    }

    fun JsonSchema.objectVariants(cache: Map<String, JsonSchema>): List<TypeDefinition> = when (this) {
        is ReferenceDefinition -> cache.getValue(`$ref`.removePrefix("#/components/schemas/")).objectVariants(cache)
        is AnyOfDefinition -> anyOf.flatMap { it.objectVariants(cache) }
        is TypeDefinition -> listOf(this)
        is ArrayDefinition, is MapDefinition, is EnumDefinition, is IntEnumDefinition, is NullableDefinition,
        is OneOfDefinition -> listOf()
    }

    fun TypeDefinition.accepts(encoded: JsonObject): Boolean {
        val declared = properties.orEmpty()
        return declared.keys.containsAll(encoded.keys) &&
            encoded.keys.containsAll(required.orEmpty()) &&
            encoded.all { (name, value) ->
                (declared[name] as? EnumDefinition)?.let { value.jsonPrimitive.content in it.enum } ?: true
            }
    }

    fun <T> shouldMatchWire(
        schema: JsonSchema,
        cache: Map<String, JsonSchema>,
        wireJson: Json,
        serializer: KSerializer<T>,
        values: List<T>,
    ) {
        val variants = schema.objectVariants(cache)
        values.forEach { value ->
            val encoded = wireJson.encodeToJsonElement(serializer, value).jsonObject
            withClue("$encoded against $variants") {
                val accepting = variants.filter { it.accepts(encoded) }
                accepting shouldHaveSize 1
                val documentedMinimum = JsonObject(encoded.filterKeys { it in accepting.single().required.orEmpty() })
                wireJson.decodeFromJsonElement(serializer, documentedMinimum) shouldBe value
            }
        }
    }

    fun variant(discriminator: String, serialName: String, fields: Map<String, JsonSchema>) = TypeDefinition(
        type = "object",
        properties = fields + (discriminator to EnumDefinition(enum = setOf(serialName))),
        required = fields.keys + discriminator,
    )

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

    should("give every sealed variant a discriminator, including one with no properties") {
        val (schema, cache) = generate(DescOutcome.serializer().descriptor)
        schema.shouldBeInstanceOf<AnyOfDefinition>()
        schema.anyOf.size shouldBe 2

        val variants = schema.anyOf.map { member ->
            member.shouldBeInstanceOf<ReferenceDefinition>()
            val name = member.`$ref`.removePrefix("#/components/schemas/")
            val cached = cache[name]
            cached.shouldBeInstanceOf<TypeDefinition>()
            name to cached
        }

        variants.forEach { (name, definition) ->
            withClue(name) {
                definition.properties?.get("type") shouldBe EnumDefinition(enum = setOf(name))
                definition.required?.contains("type") shouldBe true
            }
        }
        variants.map { it.first }.toSet() shouldBe setOf("succeeded", "cancelled")
    }

    should("generate schema for contextual type with custom serializer module") {
        val module = SerializersModule {
            contextual(java.util.UUID::class, UUIDAsStringSerializer)
        }
        val customJson = Json { serializersModule = module }

        @Serializable
        data class WithUuid(@Serializable(with = UUIDAsStringSerializer::class) val id: java.util.UUID)

        val cache = mutableMapOf<String, JsonSchema>()
        val schema = SchemaGenerator.fromDescriptor(WithUuid.serializer().descriptor, customJson, cache, mutableMapOf())
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
        val slugOwners = mutableMapOf<SchemaSlug, ClassIdentity>()
        val descriptor = DescSimpleUser.serializer().descriptor
        val schema1 = SchemaGenerator.fromDescriptor(descriptor, json, cache, slugOwners)
        val schema2 = SchemaGenerator.fromDescriptor(descriptor, json, cache, slugOwners)
        // Second call should return cached value
        schema1.shouldBeInstanceOf<TypeDefinition>()
        schema2.shouldBeInstanceOf<TypeDefinition>()
        schema1 shouldBe schema2
        cache.keys.filter { it.contains("DescSimpleUser") }.size shouldBe 1
    }

    should("not fragment a type revisited via two different properties into separate schemas") {
        val (schema, cache) = generate(DescTwoRefsToSameType.serializer().descriptor)
        schema.shouldBeInstanceOf<TypeDefinition>()
        val firstRef = schema.properties!!["first"]
        val secondRef = schema.properties!!["second"]
        firstRef.shouldBeInstanceOf<ReferenceDefinition>()
        secondRef.shouldBeInstanceOf<ReferenceDefinition>()
        firstRef.`$ref` shouldBe secondRef.`$ref`
        cache.keys.filter { it.contains("DescSimpleUser") }.size shouldBe 1
    }

    should("disambiguate colliding types discovered via nested properties, not just top-level calls") {
        val (schema, cache) = generate(DescWithCollidingStatuses.serializer().descriptor)
        schema.shouldBeInstanceOf<TypeDefinition>()
        val oneRef = schema.properties!!["one"]
        val twoRef = schema.properties!!["two"]
        oneRef.shouldBeInstanceOf<ReferenceDefinition>()
        twoRef.shouldBeInstanceOf<ReferenceDefinition>()
        val oneSlug = oneRef.`$ref`.removePrefix("#/components/schemas/")
        val twoSlug = twoRef.`$ref`.removePrefix("#/components/schemas/")
        oneSlug shouldNotBe twoSlug
        val oneSchema = cache[oneSlug]
        val twoSchema = cache[twoSlug]
        oneSchema.shouldBeInstanceOf<TypeDefinition>()
        twoSchema.shouldBeInstanceOf<TypeDefinition>()
        oneSchema.properties!!.keys shouldBe setOf("code")
        twoSchema.properties!!.keys shouldBe setOf("message")
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
            DescWithContextualUuid.serializer().descriptor, customJson, cache, mutableMapOf(),
        )
        schema.shouldBeInstanceOf<TypeDefinition>()
        schema.properties!!["id"] shouldBe TypeDefinition.STRING
        schema.properties!!["name"] shouldBe TypeDefinition.STRING
    }

    should("produce different slugs for same-named classes in different enclosing objects") {
        val slugOwners = mutableMapOf<SchemaSlug, ClassIdentity>()
        val slugA = SlugCollisionA.Status.serializer().descriptor.slug(slugOwners)
        val slugB = SlugCollisionB.Status.serializer().descriptor.slug(slugOwners)
        slugA shouldBe "SlugCollisionAStatus"
        slugB shouldBe "SlugCollisionBStatus"
    }

    should("produce different reference slugs for same-named classes") {
        val cache = mutableMapOf<String, JsonSchema>()
        val slugOwners = mutableMapOf<SchemaSlug, ClassIdentity>()
        val customJson = Json.Default
        SchemaGenerator.fromDescriptor(SlugCollisionA.Status.serializer().descriptor, customJson, cache, slugOwners)
        SchemaGenerator.fromDescriptor(SlugCollisionB.Status.serializer().descriptor, customJson, cache, slugOwners)
        cache.keys.filter { it.contains("Status") }.size shouldBe 2
    }

    should("disambiguate top-level classes with the same simple name in different packages") {
        val cache = mutableMapOf<String, JsonSchema>()
        val slugOwners = mutableMapOf<SchemaSlug, ClassIdentity>()
        val customJson = Json.Default
        val schemaOne = SchemaGenerator.fromDescriptor(
            PkgOneStatus.serializer().descriptor, customJson, cache, slugOwners,
        )
        val schemaTwo = SchemaGenerator.fromDescriptor(
            PkgTwoStatus.serializer().descriptor, customJson, cache, slugOwners,
        )
        cache.keys.filter { it.contains("Status") }.size shouldBe 2
        schemaOne.shouldBeInstanceOf<TypeDefinition>()
        schemaTwo.shouldBeInstanceOf<TypeDefinition>()
        schemaOne.properties!!.keys shouldBe setOf("code")
        schemaTwo.properties!!.keys shouldBe setOf("message")
    }

    should("disambiguate same-named classes across packages via the reflection-based schema path") {
        val cache = mutableMapOf<String, JsonSchema>()
        val slugOwners = mutableMapOf<SchemaSlug, ClassIdentity>()
        val schemaOne = SchemaGenerator.fromTypeToSchema(typeOf<PkgOneStatus>(), Json.Default, cache, slugOwners)
        val schemaTwo = SchemaGenerator.fromTypeToSchema(typeOf<PkgTwoStatus>(), Json.Default, cache, slugOwners)
        schemaOne.shouldBeInstanceOf<TypeDefinition>()
        schemaTwo.shouldBeInstanceOf<TypeDefinition>()
        schemaOne.properties!!.keys shouldBe setOf("code")
        schemaTwo.properties!!.keys shouldBe setOf("message")
        val oneSlug = typeOf<PkgOneStatus>().slug(slugOwners)
        val twoSlug = typeOf<PkgTwoStatus>().slug(slugOwners)
        oneSlug shouldNotBe twoSlug
        cache[oneSlug].shouldNotBeNull()
        cache[twoSlug].shouldNotBeNull()
    }

    should("disambiguate different classes that share an identical explicit @SerialName") {
        val cache = mutableMapOf<String, JsonSchema>()
        val slugOwners = mutableMapOf<SchemaSlug, ClassIdentity>()
        val customJson = Json.Default
        val schemaOne = SchemaGenerator.fromDescriptor(
            SharedNameOne.serializer().descriptor, customJson, cache, slugOwners,
        )
        val schemaTwo = SchemaGenerator.fromDescriptor(
            SharedNameTwo.serializer().descriptor, customJson, cache, slugOwners,
        )
        cache.keys.filter { it.contains("SharedName") }.size shouldBe 2
        schemaOne.shouldBeInstanceOf<TypeDefinition>()
        schemaTwo.shouldBeInstanceOf<TypeDefinition>()
        schemaOne.properties!!.keys shouldBe setOf("code")
        schemaTwo.properties!!.keys shouldBe setOf("message")
    }

    should("disambiguate sealed-variant siblings from different hierarchies that share an explicit @SerialName") {
        val cache = mutableMapOf<String, JsonSchema>()
        val slugOwners = mutableMapOf<SchemaSlug, ClassIdentity>()
        val json = Json.Default
        val schemaOne = SchemaGenerator.fromDescriptor(
            DescSealedCollisionOne.serializer().descriptor, json, cache, slugOwners,
        )
        val schemaTwo = SchemaGenerator.fromDescriptor(
            DescSealedCollisionTwo.serializer().descriptor, json, cache, slugOwners,
        )
        schemaOne.shouldBeInstanceOf<AnyOfDefinition>()
        schemaTwo.shouldBeInstanceOf<AnyOfDefinition>()
        val refOne = schemaOne.anyOf.single().shouldBeInstanceOf<ReferenceDefinition>().`$ref`
        val refTwo = schemaTwo.anyOf.single().shouldBeInstanceOf<ReferenceDefinition>().`$ref`
        refOne shouldNotBe refTwo
        // First registrant keeps the plain slug; the second is disambiguated by its hierarchy.
        refOne shouldBe "#/components/schemas/SHARED_VARIANT"
        refTwo shouldBe "#/components/schemas/DescSealedCollisionTwoSHARED_VARIANT"
        val oneSchema = cache[refOne.removePrefix("#/components/schemas/")]
        val twoSchema = cache[refTwo.removePrefix("#/components/schemas/")]
        oneSchema.shouldBeInstanceOf<TypeDefinition>().properties!!.keys shouldBe setOf("type", "fieldOne")
        twoSchema.shouldBeInstanceOf<TypeDefinition>().properties!!.keys shouldBe setOf("type", "fieldTwo")
    }

    should("keep a sealed-variant collision slug independent of how many other schemas registered first") {
        val json = Json.Default

        val fewPriorRegistrations = mutableMapOf<SchemaSlug, ClassIdentity>()
        SchemaGenerator.fromDescriptor(
            DescSealedCollisionOne.serializer().descriptor, json, mutableMapOf(), fewPriorRegistrations,
        )
        val slugWithFewPriorRegistrations = SchemaGenerator.fromDescriptor(
            DescSealedCollisionTwo.serializer().descriptor, json, mutableMapOf(), fewPriorRegistrations,
        ).let { (it as AnyOfDefinition).anyOf.single() as ReferenceDefinition }.`$ref`

        val manyPriorRegistrations = mutableMapOf<SchemaSlug, ClassIdentity>()
        listOf(
            DescSimpleUser.serializer().descriptor,
            DescAddress.serializer().descriptor,
            DescColor.serializer().descriptor,
            DescShape.serializer().descriptor,
            DescOutcome.serializer().descriptor,
        ).forEach { SchemaGenerator.fromDescriptor(it, json, mutableMapOf(), manyPriorRegistrations) }
        SchemaGenerator.fromDescriptor(
            DescSealedCollisionOne.serializer().descriptor, json, mutableMapOf(), manyPriorRegistrations,
        )
        val slugWithManyPriorRegistrations = SchemaGenerator.fromDescriptor(
            DescSealedCollisionTwo.serializer().descriptor, json, mutableMapOf(), manyPriorRegistrations,
        ).let { (it as AnyOfDefinition).anyOf.single() as ReferenceDefinition }.`$ref`

        slugWithManyPriorRegistrations shouldBe slugWithFewPriorRegistrations
    }

    should("reuse the same slug for a custom-serializer-backed type reached from two different holders") {
        val cache = mutableMapOf<String, JsonSchema>()
        val slugOwners = mutableMapOf<SchemaSlug, ClassIdentity>()
        val customJson = Json.Default
        SchemaGenerator.fromDescriptor(
            DescHolderWithCustomAddressOne.serializer().descriptor, customJson, cache, slugOwners,
        )
        SchemaGenerator.fromDescriptor(
            DescHolderWithCustomAddressTwo.serializer().descriptor, customJson, cache, slugOwners,
        )
        // DescCustomAddress's hand-built descriptor has no capturedKClass; this previously
        // fragmented into a new schema entry per holder that referenced it.
        cache.keys.filter { it.contains("DescCustomAddress") }.size shouldBe 1
        cache.keys.filter { it.contains("DescCustomCountry") }.size shouldBe 1
    }

    should("keep a stable disambiguated slug for a losing identity across repeated encounters") {
        val cache = mutableMapOf<String, JsonSchema>()
        val slugOwners = mutableMapOf<SchemaSlug, ClassIdentity>()
        val json = Json.Default
        SchemaGenerator.fromDescriptor(SharedNameOne.serializer().descriptor, json, cache, slugOwners)
        val firstSlug = SharedNameTwo.serializer().descriptor.slug(slugOwners)
        listOf(
            DescSimpleUser.serializer().descriptor,
            DescAddress.serializer().descriptor,
            DescColor.serializer().descriptor,
        ).forEach { SchemaGenerator.fromDescriptor(it, json, cache, slugOwners) }
        // Previously recomputed a fresh (and different) slug each time, based on the
        // ever-growing slugOwners size rather than the losing identity itself.
        val secondSlug = SharedNameTwo.serializer().descriptor.slug(slugOwners)
        secondSlug shouldBe firstSlug
    }

    should("pick up a directly pre-cached override for a non-self-caching kind, mirroring customTypes") {
        // Mirrors OpenApi.kt's customTypes: an override installed directly into `cache` under a
        // type's plain slug before any route processing.
        val cache = mutableMapOf<String, JsonSchema>(
            "Instant" to TypeDefinition(type = "string", format = "date-time"),
        )
        val slugOwners = mutableMapOf<SchemaSlug, ClassIdentity>()
        val module = SerializersModule { contextual(DescCustomInstantSerializer) }
        val customJson = Json { serializersModule = module }
        val schema = SchemaGenerator.fromDescriptor(
            DescWithContextualInstantField.serializer().descriptor, customJson, cache, slugOwners,
        )
        (schema as TypeDefinition).properties!!["createdAt"] shouldBe
            TypeDefinition(type = "string", format = "date-time")
    }

    should("fall back to well-known UUID schema when no module serializer registered") {
        val noContextJson = Json.Default
        val cache = mutableMapOf<String, JsonSchema>()
        val schema = SchemaGenerator.fromDescriptor(
            DescWithContextualUuid.serializer().descriptor, noContextJson, cache, mutableMapOf(),
        )
        schema.shouldBeInstanceOf<TypeDefinition>()
        schema.properties!!["id"] shouldBe TypeDefinition.UUID
        schema.properties!!["name"] shouldBe TypeDefinition.STRING
    }

    should("fall back to well-known Instant schema when no module serializer registered") {
        val noContextJson = Json.Default
        val cache = mutableMapOf<String, JsonSchema>()
        val schema = SchemaGenerator.fromDescriptor(
            DescWithContextualInstant.serializer().descriptor, noContextJson, cache, mutableMapOf(),
        )
        schema.shouldBeInstanceOf<TypeDefinition>()
        schema.properties!!["createdAt"] shouldBe TypeDefinition(type = "string", format = "date-time")
    }

    should("unwrap value class wrapping @Contextual UUID to string/uuid") {
        val cache = mutableMapOf<String, JsonSchema>()
        val schema = SchemaGenerator.fromDescriptor(
            DescWithValueClassId.serializer().descriptor, Json.Default, cache, mutableMapOf(),
        )
        schema.shouldBeInstanceOf<TypeDefinition>()
        schema.properties!!["id"] shouldBe TypeDefinition.UUID
        schema.properties!!["name"] shouldBe TypeDefinition.STRING
    }

    should("generate array schema for List of @Contextual UUID") {
        val cache = mutableMapOf<String, JsonSchema>()
        val schema = SchemaGenerator.fromDescriptor(
            DescWithListOfContextualIds.serializer().descriptor, Json.Default, cache, mutableMapOf(),
        )
        schema.shouldBeInstanceOf<TypeDefinition>()
        val idsProp = schema.properties!!["ids"]
        idsProp.shouldBeInstanceOf<ArrayDefinition>()
        idsProp.items shouldBe TypeDefinition.UUID
    }

    should("generate array schema for Set of String") {
        val cache = mutableMapOf<String, JsonSchema>()
        val schema = SchemaGenerator.fromDescriptor(
            DescWithSetOfStrings.serializer().descriptor, Json.Default, cache, mutableMapOf(),
        )
        schema.shouldBeInstanceOf<TypeDefinition>()
        val emailsProp = schema.properties!!["emails"]
        emailsProp.shouldBeInstanceOf<ArrayDefinition>()
        emailsProp.items shouldBe TypeDefinition.STRING
    }

    should("produce slug without ? for nullable types") {
        val descriptor = DescWithNullableObject.serializer().descriptor
        val addressDescriptor = descriptor.getElementDescriptor(0)
        val addressSlug = addressDescriptor.slug(mutableMapOf())
        addressSlug.contains("?") shouldBe false
    }

    should("name the sealed discriminator after @JsonClassDiscriminator on the parent") {
        val (schema, cache) = generate(DescAnnotatedResult.serializer().descriptor)
        schema.objectVariants(cache).toSet() shouldBe setOf(
            variant("status", "CREATED", mapOf("id" to TypeDefinition.STRING)),
            variant("status", "FAILED", mapOf("reason" to TypeDefinition.STRING)),
            variant("status", "PENDING", mapOf()),
        )
    }

    should("name the sealed discriminator after the configured classDiscriminator when the parent has no annotation") {
        val kindJson = Json { classDiscriminator = "kind" }
        val cache = mutableMapOf<String, JsonSchema>()
        val schema = SchemaGenerator.fromDescriptor(
            DescOutcome.serializer().descriptor, kindJson, cache, mutableMapOf(),
        )
        schema.objectVariants(cache).toSet() shouldBe setOf(
            variant("kind", "succeeded", mapOf("at" to TypeDefinition.STRING)),
            variant("kind", "cancelled", mapOf()),
        )
    }

    should("document exactly the keys kotlinx encodes for every sealed variant") {
        fun <T> check(wireJson: Json, serializer: KSerializer<T>, values: List<T>) {
            val cache = mutableMapOf<String, JsonSchema>()
            val schema = SchemaGenerator.fromDescriptor(serializer.descriptor, wireJson, cache, mutableMapOf())
            shouldMatchWire(schema, cache, wireJson, serializer, values)
        }
        val kindJson = Json { classDiscriminator = "kind" }
        val outcomes = listOf(DescOutcome.Succeeded("2026-01-01"), DescOutcome.Cancelled)
        check(
            Json.Default,
            DescAnnotatedResult.serializer(),
            listOf(DescAnnotatedResult.Created("1"), DescAnnotatedResult.Failed("x"), DescAnnotatedResult.Pending),
        )
        check(kindJson, DescOutcome.serializer(), outcomes)
        check(
            Json { classDiscriminatorMode = ClassDiscriminatorMode.ALL_JSON_OBJECTS },
            DescAnnotatedResult.serializer(),
            listOf(DescAnnotatedResult.Created("1"), DescAnnotatedResult.Pending),
        )
        check(
            Json.Default,
            DescDottedOuter.serializer(),
            listOf(
                DescDottedOuter.Inner.XLeaf(1),
                DescDottedOuter.Inner.YLeaf("y"),
                DescDottedOuter.Inner.Unnamed(2),
                DescDottedOuter.Other(3),
            ),
        )
        check(Json.Default, DescOutcome.serializer(), outcomes)
        check(
            Json.Default,
            DescNestedOuter.serializer(),
            listOf(DescNestedOuter.Inner.Leaf(1), DescNestedOuter.Other(2)),
        )
        check(Json.Default, DescInheritingOuter.Inner.serializer(), listOf(DescInheritingOuter.Inner.Leaf(1)))
        check(
            Json.Default,
            DescExpr.serializer(),
            listOf(
                DescExpr.Binary.Add(DescExpr.Lit(1), DescExpr.Lit(2)),
                DescExpr.Neg(DescExpr.Lit(3)),
                DescExpr.Lit(4),
            ),
        )
    }

    should("document exactly the keys kotlinx encodes for every sealed variant via the reflection-based schema path") {
        fun <T> check(wireJson: Json, type: KType, serializer: KSerializer<T>, values: List<T>) {
            val cache = mutableMapOf<String, JsonSchema>()
            val schema = SchemaGenerator.fromTypeToSchema(type, wireJson, cache, mutableMapOf())
            shouldMatchWire(schema, cache, wireJson, serializer, values)
        }
        val kindJson = Json { classDiscriminator = "kind" }
        val outcomes = listOf(DescOutcome.Succeeded("2026-01-01"), DescOutcome.Cancelled)
        check(
            Json.Default,
            typeOf<DescAnnotatedResult>(),
            DescAnnotatedResult.serializer(),
            listOf(DescAnnotatedResult.Created("1"), DescAnnotatedResult.Failed("x"), DescAnnotatedResult.Pending),
        )
        check(kindJson, typeOf<DescOutcome>(), DescOutcome.serializer(), outcomes)
        check(
            Json.Default,
            typeOf<DescDottedOuter>(),
            DescDottedOuter.serializer(),
            listOf(
                DescDottedOuter.Inner.XLeaf(1),
                DescDottedOuter.Inner.YLeaf("y"),
                DescDottedOuter.Inner.Unnamed(2),
                DescDottedOuter.Other(3),
            ),
        )
        check(Json.Default, typeOf<DescOutcome>(), DescOutcome.serializer(), outcomes)
        check(
            Json.Default,
            typeOf<DescNestedOuter>(),
            DescNestedOuter.serializer(),
            listOf(DescNestedOuter.Inner.Leaf(1), DescNestedOuter.Other(2)),
        )
        check(
            Json.Default,
            typeOf<DescInheritingOuter.Inner>(),
            DescInheritingOuter.Inner.serializer(),
            listOf(DescInheritingOuter.Inner.Leaf(1)),
        )
        check(
            Json.Default,
            typeOf<DescExpr>(),
            DescExpr.serializer(),
            listOf(
                DescExpr.Binary.Add(DescExpr.Lit(1), DescExpr.Lit(2)),
                DescExpr.Neg(DescExpr.Lit(3)),
                DescExpr.Lit(4),
            ),
        )
    }

    should("give a variant shared by two sealed parents one component per discriminator in either order") {
        val outer = DescNestedOuter.serializer()
        val inner = DescNestedOuter.Inner.serializer()
        val leaf = DescNestedOuter.Inner.Leaf(1)
        val runs = listOf(listOf(outer, inner), listOf(inner, outer)).map { order ->
            val cache = mutableMapOf<String, JsonSchema>()
            val slugOwners = mutableMapOf<SchemaSlug, ClassIdentity>()
            val schemas = order.associate {
                it.descriptor.serialName to SchemaGenerator.fromDescriptor(it.descriptor, json, cache, slugOwners)
            }
            shouldMatchWire(schemas.getValue(outer.descriptor.serialName), cache, json, outer, listOf(leaf))
            shouldMatchWire(schemas.getValue(inner.descriptor.serialName), cache, json, inner, listOf(leaf))
            schemas to cache
        }
        runs[1] shouldBe runs[0]
        val (schemas, cache) = runs[0]
        schemas.getValue(outer.descriptor.serialName) shouldBe AnyOfDefinition(
            setOf(
                ReferenceDefinition("#/components/schemas/DescNestedOuter.LEAF"),
                ReferenceDefinition("#/components/schemas/OTHER"),
            ),
        )
        schemas.getValue(inner.descriptor.serialName) shouldBe
            AnyOfDefinition(setOf(ReferenceDefinition("#/components/schemas/LEAF")))
        cache["LEAF"] shouldBe variant("kind", "LEAF", mapOf("value" to TypeDefinition.INT))
        cache["DescNestedOuter.LEAF"] shouldBe variant("type", "LEAF", mapOf("value" to TypeDefinition.INT))
    }

    should("keep same-named same-shaped variants of parents with different discriminators in separate components") {
        val status = DescStatusOutcome.serializer()
        val state = DescStateOutcome.serializer()
        listOf(true, false).forEach { statusFirst ->
            withClue("status hierarchy first: $statusFirst") {
                val cache = mutableMapOf<String, JsonSchema>()
                val slugOwners = mutableMapOf<SchemaSlug, ClassIdentity>()
                val holder = SchemaGenerator.fromDescriptor(
                    DescOkHolder.serializer().descriptor, json, cache, slugOwners,
                )
                val order = listOf(status.descriptor, state.descriptor).let { if (statusFirst) it else it.reversed() }
                val schemas = order.associateWith { SchemaGenerator.fromDescriptor(it, json, cache, slugOwners) }
                val statusValues = listOf(DescStatusOutcome.None, DescStatusOutcome.Ok("1"))
                shouldMatchWire(schemas.getValue(status.descriptor), cache, json, status, statusValues)
                val stateValues = listOf(DescStateOutcome.None, DescStateOutcome.Done("1"), DescStateOutcome.Ok("1"))
                shouldMatchWire(schemas.getValue(state.descriptor), cache, json, state, stateValues)
                val first = if (statusFirst) "status" else "state"
                cache["NONE"] shouldBe variant(first, "NONE", mapOf())
                cache["OK"] shouldBe variant(first, "OK", mapOf("id" to TypeDefinition.STRING))
                holder.shouldBeInstanceOf<TypeDefinition>().properties.orEmpty()["ok"] shouldBe
                    ReferenceDefinition("#/components/schemas/OK")
            }
        }
    }

    should("name foreign-tagged variants per parent and keep colliding short names apart") {
        val cache = mutableMapOf<String, JsonSchema>()
        val slugOwners = mutableMapOf<SchemaSlug, ClassIdentity>()
        val dotted = SchemaGenerator.fromDescriptor(DescDottedOuter.serializer().descriptor, json, cache, slugOwners)
        val one = SchemaGenerator.fromDescriptor(PkgOneHierarchy.serializer().descriptor, json, cache, slugOwners)
        val two = SchemaGenerator.fromDescriptor(PkgTwoHierarchy.serializer().descriptor, json, cache, slugOwners)
        dotted shouldBe AnyOfDefinition(
            listOf(
                "DescDottedOuter.DescDottedOuterInnerUnnamed",
                "DescDottedOuter.Leaf",
                "DescDottedOuter.yLeaf",
                "OTHER",
            ).map { ReferenceDefinition("#/components/schemas/$it") }.toSet(),
        )
        cache["DescDottedOuter.Leaf"] shouldBe variant("type", "x.Leaf", mapOf("x" to TypeDefinition.INT))
        cache["DescDottedOuter.yLeaf"] shouldBe variant("type", "y.Leaf", mapOf("y" to TypeDefinition.STRING))
        val second = SchemaGenerator.fromDescriptor(DescDottedSecond.serializer().descriptor, json, cache, slugOwners)
        second shouldBe AnyOfDefinition(
            listOf("DescDottedSecond.DescDottedOuterInnerUnnamed", "DescDottedSecond.Leaf", "DescDottedSecond.yLeaf")
                .map { ReferenceDefinition("#/components/schemas/$it") }.toSet(),
        )
        one shouldBe AnyOfDefinition(setOf(ReferenceDefinition("#/components/schemas/Hierarchy.NESTED")))
        two shouldBe AnyOfDefinition(
            setOf(ReferenceDefinition("#/components/schemas/openapifixturespkgtwoHierarchy.NESTED")),
        )
        shouldMatchWire(one, cache, json, PkgOneHierarchy.serializer(), listOf(PkgOneHierarchy.Inner.Nested(1)))
        shouldMatchWire(two, cache, json, PkgTwoHierarchy.serializer(), listOf(PkgTwoHierarchy.Inner.Nested("2")))
    }

    should("give a shared variant one component per discriminator via the reflection-based schema path") {
        val outer = typeOf<DescNestedOuter>()
        val inner = typeOf<DescNestedOuter.Inner>()
        val leaf = DescNestedOuter.Inner.Leaf(1)
        val runs = listOf(listOf(outer, inner), listOf(inner, outer)).map { order ->
            val cache = mutableMapOf<String, JsonSchema>()
            val slugOwners = mutableMapOf<SchemaSlug, ClassIdentity>()
            val schemas = order.associateWith { SchemaGenerator.fromTypeToSchema(it, json, cache, slugOwners) }
            shouldMatchWire(schemas.getValue(outer), cache, json, DescNestedOuter.serializer(), listOf(leaf))
            shouldMatchWire(schemas.getValue(inner), cache, json, DescNestedOuter.Inner.serializer(), listOf(leaf))
            schemas to cache
        }
        runs[1] shouldBe runs[0]
        val (schemas, cache) = runs[0]
        schemas.getValue(outer) shouldBe AnyOfDefinition(
            setOf(
                AnyOfDefinition(
                    setOf(ReferenceDefinition("#/components/schemas/DescNestedOuter.DescNestedOuterInnerLeaf")),
                ),
                ReferenceDefinition("#/components/schemas/DescNestedOuterOther"),
            ),
        )
        schemas.getValue(inner) shouldBe
            AnyOfDefinition(setOf(ReferenceDefinition("#/components/schemas/DescNestedOuterInnerLeaf")))
        cache["DescNestedOuterInnerLeaf"] shouldBe variant("kind", "LEAF", mapOf("value" to TypeDefinition.INT))
        cache["DescNestedOuter.DescNestedOuterInnerLeaf"] shouldBe
            variant("type", "LEAF", mapOf("value" to TypeDefinition.INT))
    }

    should("tag variants without @SerialName with their default serial name via the reflection-based schema path") {
        val unnamedCache = mutableMapOf<String, JsonSchema>()
        val unnamed = SchemaGenerator.fromTypeToSchema(
            typeOf<DescUnnamedVariants>(), json, unnamedCache, mutableMapOf(),
        )
        unnamed.objectVariants(unnamedCache) shouldBe listOf(
            variant("type", "openapi.DescUnnamedVariants.First", mapOf("value" to TypeDefinition.INT)),
        )
        val first = DescUnnamedVariants.First(1)
        shouldMatchWire(unnamed, unnamedCache, json, DescUnnamedVariants.serializer(), listOf(first))
        val plainCache = mutableMapOf<String, JsonSchema>()
        val plain = SchemaGenerator.fromTypeToSchema(typeOf<DescPlainSealed>(), json, plainCache, mutableMapOf())
        plain shouldBe AnyOfDefinition(
            setOf(
                ReferenceDefinition("#/components/schemas/DescPlainSealedFirst"),
                ReferenceDefinition("#/components/schemas/DescPlainSealedSecond"),
            ),
        )
        plain.objectVariants(plainCache).toSet() shouldBe setOf(
            variant("type", "openapi.DescPlainSealed.First", mapOf("value" to TypeDefinition.INT)),
            variant("type", "openapi.DescPlainSealed.Second", mapOf()),
        )
    }

    should("tag a custom-serializer variant with its serializer's serial name via the reflection-based schema path") {
        val cache = mutableMapOf<String, JsonSchema>()
        val schema = SchemaGenerator.fromTypeToSchema(typeOf<DescCustomSealed>(), json, cache, mutableMapOf())
        shouldMatchWire(schema, cache, json, DescCustomSealed.serializer(), listOf(DescCustomSealed.Custom("x")))
    }

    should("keep the other variants when a nested sealed child fails via the reflection-based schema path") {
        val cache = mutableMapOf<String, JsonSchema>()
        val schema = SchemaGenerator.fromTypeToSchema(typeOf<DescGenericOuter<String>>(), json, cache, mutableMapOf())
        schema.objectVariants(cache) shouldBe listOf(
            TypeDefinition(type = "object"),
            variant("type", "openapi.DescGenericOuter.Other", mapOf("t" to TypeDefinition.STRING)),
        )
    }

    should("leave an enum variant alone and degrade only a failing variant via the reflection-based schema path") {
        val cache = mutableMapOf<String, JsonSchema>()
        val schema = SchemaGenerator.fromTypeToSchema(typeOf<DescOddVariants>(), json, cache, mutableMapOf())
        schema.shouldBeInstanceOf<AnyOfDefinition>().anyOf shouldContain EnumDefinition(enum = setOf("ON"))
        schema.objectVariants(cache).toSet() shouldBe setOf(
            variant("type", "openapi.DescOddVariants.Broken", mapOf()),
            variant("type", "openapi.DescOddVariants.Fine", mapOf("value" to TypeDefinition.INT)),
        )
    }

    should("document a star-projected sealed type via the reflection-based schema path") {
        val cache = mutableMapOf<String, JsonSchema>()
        val schema = SchemaGenerator.fromTypeToSchema(typeOf<DescBox<*>>(), json, cache, mutableMapOf())
        schema.objectVariants(cache).toSet() shouldBe setOf(
            variant("type", "openapi.DescBox.Full", mapOf()),
            variant("type", "openapi.DescBox.Empty", mapOf("reason" to TypeDefinition.STRING)),
        )
    }

    should("keep a cached override for a sealed variant, tagging it only when it is an object") {
        val stringCache = mutableMapOf<String, JsonSchema>("LIT" to TypeDefinition.STRING)
        val schema = SchemaGenerator.fromDescriptor(DescExpr.serializer().descriptor, json, stringCache, mutableMapOf())
        schema.shouldBeInstanceOf<AnyOfDefinition>().anyOf shouldContain TypeDefinition.STRING
        stringCache["LIT"] shouldBe TypeDefinition.STRING
        val custom = mapOf("custom" to TypeDefinition.STRING)
        val objectCache = mutableMapOf<String, JsonSchema>(
            "LIT" to TypeDefinition(type = "object", properties = custom, required = custom.keys),
        )
        SchemaGenerator.fromDescriptor(DescExpr.serializer().descriptor, json, objectCache, mutableMapOf())
        objectCache["LIT"] shouldBe variant("type", "LIT", custom)
    }

    should("refuse to tag a cached variant that already carries another variant's tag") {
        val foreignTag = mapOf("type" to EnumDefinition(enum = setOf("OTHER")))
        val cache = mutableMapOf<String, JsonSchema>(
            "LIT" to TypeDefinition(type = "object", properties = foreignTag, required = foreignTag.keys),
        )
        shouldThrow<IllegalStateException> {
            SchemaGenerator.fromDescriptor(DescExpr.serializer().descriptor, json, cache, mutableMapOf())
        }
    }

    should("take the discriminator from the serializer kotlinx uses via the reflection-based schema path") {
        val moduleJson = Json {
            serializersModule = SerializersModule {
                polymorphic(DescModuleSealed::class) { subclass(DescModuleSealed.Variant::class) }
            }
        }
        val cache = mutableMapOf<String, JsonSchema>()
        val schema = SchemaGenerator.fromTypeToSchema(typeOf<DescModuleSealed>(), moduleJson, cache, mutableMapOf())
        val serializer = moduleJson.serializersModule.serializer(typeOf<DescModuleSealed>())
        shouldMatchWire(schema, cache, moduleJson, serializer, listOf(DescModuleSealed.Variant(1)))
    }

    should("leave an enum variant untagged") {
        val (schema, _) = generate(DescOddVariants.serializer().descriptor)
        schema.shouldBeInstanceOf<AnyOfDefinition>().anyOf shouldContain EnumDefinition(enum = setOf("ON"))
    }

    should("honour a cached schema for a nested sealed child via the reflection-based schema path") {
        val cache = mutableMapOf<String, JsonSchema>()
        val slugOwners = mutableMapOf<SchemaSlug, ClassIdentity>()
        val override = TypeDefinition(type = "string", format = "inner-override")
        cache[typeOf<DescNestedOuter.Inner>().slug(slugOwners)] = override
        val schema = SchemaGenerator.fromTypeToSchema(typeOf<DescNestedOuter>(), json, cache, slugOwners)
        schema.shouldBeInstanceOf<AnyOfDefinition>().anyOf shouldBe setOf(
            override,
            ReferenceDefinition("#/components/schemas/DescNestedOuterOther"),
        )
    }

    should("refuse a sealed variant with a property named like its discriminator") {
        shouldThrow<UnsupportedSchemaException> { generate(DescClashingVariant.serializer().descriptor) }
        shouldThrow<UnsupportedSchemaException> {
            SchemaGenerator.fromTypeToSchema(typeOf<DescClashingVariant>(), json, mutableMapOf(), mutableMapOf())
        }
    }

    should("refuse Json configurations whose discriminators the schema cannot describe") {
        listOf(
            Json { classDiscriminatorMode = ClassDiscriminatorMode.NONE },
            Json { useArrayPolymorphism = true },
        ).forEach { configured ->
            shouldThrow<UnsupportedSchemaException> {
                SchemaGenerator.fromDescriptor(
                    DescAnnotatedResult.serializer().descriptor, configured, mutableMapOf(), mutableMapOf(),
                )
            }
            shouldThrow<UnsupportedSchemaException> {
                SchemaGenerator.fromTypeToSchema(
                    typeOf<DescAnnotatedResult>(), configured, mutableMapOf(), mutableMapOf(),
                )
            }
        }
    }
})
