// Originally derived from kompendium (https://github.com/bkbnio/kompendium), MIT License
package openapi.schema

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.descriptors.capturedKClass
import kotlinx.serialization.descriptors.getContextualDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.KTypeParameter
import kotlin.reflect.KTypeProjection
import kotlin.reflect.full.createType
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaField

@OptIn(ExperimentalSerializationApi::class)
object SchemaGenerator {

    fun fromDescriptor(
        descriptor: SerialDescriptor,
        json: Json,
        cache: MutableMap<String, JsonSchema>,
        slugOwners: MutableMap<SchemaSlug, ClassIdentity> = mutableMapOf(),
    ): JsonSchema {
        if (descriptor.isInline) {
            val inner = descriptor.getElementDescriptor(0)
            return fromDescriptor(inner, json, cache, slugOwners)
        }
        val slug = descriptor.slug(slugOwners)
        cache[slug]?.let { return it }
        return when (descriptor.kind) {
            PrimitiveKind.STRING -> TypeDefinition.STRING
            PrimitiveKind.INT -> TypeDefinition.INT
            PrimitiveKind.LONG -> TypeDefinition.LONG
            PrimitiveKind.DOUBLE -> TypeDefinition.DOUBLE
            PrimitiveKind.FLOAT -> TypeDefinition.FLOAT
            PrimitiveKind.BOOLEAN -> TypeDefinition.BOOLEAN
            PrimitiveKind.BYTE -> TypeDefinition.INT
            PrimitiveKind.SHORT -> TypeDefinition.INT
            PrimitiveKind.CHAR -> TypeDefinition.STRING
            SerialKind.ENUM -> handleDescriptorEnum(descriptor, cache, slugOwners)
            StructureKind.LIST -> handleDescriptorList(descriptor, json, cache, slugOwners)
            StructureKind.MAP -> handleDescriptorMap(descriptor, json, cache, slugOwners)
            StructureKind.CLASS, StructureKind.OBJECT -> handleDescriptorObject(descriptor, json, cache, slugOwners)
            SerialKind.CONTEXTUAL -> handleDescriptorContextual(descriptor, json, cache, slugOwners)
            is PolymorphicKind -> handleDescriptorPolymorphic(descriptor, json, cache, slugOwners)
        }
    }

    private fun handleDescriptorEnum(
        descriptor: SerialDescriptor,
        cache: MutableMap<String, JsonSchema>,
        slugOwners: MutableMap<SchemaSlug, ClassIdentity>,
    ): JsonSchema {
        val definition = EnumDefinition(enum = descriptor.elementNames.toSet())
        cache[descriptor.slug(slugOwners)] = definition
        return definition
    }

    private fun handleDescriptorList(
        descriptor: SerialDescriptor,
        json: Json,
        cache: MutableMap<String, JsonSchema>,
        slugOwners: MutableMap<SchemaSlug, ClassIdentity>,
    ): JsonSchema {
        val elementDescriptor = descriptor.getElementDescriptor(0)
        val elementSchema = fromDescriptor(elementDescriptor, json, cache, slugOwners).let {
            if (it.isObjectOrEnum()) {
                cache[elementDescriptor.slug(slugOwners)] = it
                ReferenceDefinition(elementDescriptor.referenceSlug(slugOwners))
            } else {
                it
            }
        }
        return ArrayDefinition(elementSchema)
    }

    private fun handleDescriptorMap(
        descriptor: SerialDescriptor,
        json: Json,
        cache: MutableMap<String, JsonSchema>,
        slugOwners: MutableMap<SchemaSlug, ClassIdentity>,
    ): JsonSchema {
        // Map descriptor element 0 = key, element 1 = value
        val valueDescriptor = descriptor.getElementDescriptor(1)
        val valueSchema = fromDescriptor(valueDescriptor, json, cache, slugOwners).let {
            if (it is TypeDefinition && it.type == "object") {
                cache[valueDescriptor.slug(slugOwners)] = it
                ReferenceDefinition(valueDescriptor.referenceSlug(slugOwners))
            } else {
                it
            }
        }
        return MapDefinition(valueSchema)
    }

    private fun handleDescriptorObject(
        descriptor: SerialDescriptor,
        json: Json,
        cache: MutableMap<String, JsonSchema>,
        slugOwners: MutableMap<SchemaSlug, ClassIdentity>,
    ): JsonSchema {
        if (descriptor.elementsCount == 0) {
            return TypeDefinition(type = "object")
        }
        val slug = descriptor.slug(slugOwners)
        val referenceSlug = descriptor.referenceSlug(slugOwners)
        cache[slug] = ReferenceDefinition(referenceSlug)
        val props = mutableMapOf<String, JsonSchema>()
        val required = mutableSetOf<String>()
        for (i in 0 until descriptor.elementsCount) {
            val name = descriptor.getElementName(i)
            val elementDescriptor = descriptor.getElementDescriptor(i)
            val elementSchema = fromDescriptor(elementDescriptor, json, cache, slugOwners).let {
                if (it.isObjectOrEnum()) {
                    cache[elementDescriptor.slug(slugOwners)] = it
                    ReferenceDefinition(elementDescriptor.referenceSlug(slugOwners))
                } else {
                    it
                }
            }
            val nullChecked = when (elementDescriptor.isNullable && !elementSchema.isNullable()) {
                true -> OneOfDefinition(NullableDefinition(), elementSchema)
                false -> elementSchema
            }
            props[name] = nullChecked
            if (!descriptor.isElementOptional(i) && !elementDescriptor.isNullable) {
                required.add(name)
            }
        }
        val definition = TypeDefinition(
            type = "object",
            properties = props,
            required = required,
        )
        cache[slug] = definition
        return definition
    }

    private val wellKnownContextual = mapOf<KClass<*>, JsonSchema>(
        UUID::class to TypeDefinition.UUID,
        Instant::class to TypeDefinition(type = "string", format = "date-time"),
        LocalDate::class to TypeDefinition(type = "string", format = "date"),
        LocalDateTime::class to TypeDefinition(type = "string", format = "date-time"),
        OffsetDateTime::class to TypeDefinition(type = "string", format = "date-time"),
    )

    private fun handleDescriptorContextual(
        descriptor: SerialDescriptor,
        json: Json,
        cache: MutableMap<String, JsonSchema>,
        slugOwners: MutableMap<SchemaSlug, ClassIdentity>,
    ): JsonSchema {
        val resolved = resolveContextual(descriptor, json)
        if (resolved != null) {
            return fromDescriptor(resolved, json, cache, slugOwners)
        }
        val captured = descriptor.capturedKClass
        wellKnownContextual[captured]?.let { return it }
        if (captured != null && captured.isValue) {
            val innerClass = captured.primaryConstructor
                ?.parameters?.firstOrNull()?.type?.classifier as? KClass<*>
            wellKnownContextual[innerClass]?.let { return it }
        }
        return TypeDefinition(type = "object")
    }

    private fun resolveContextual(descriptor: SerialDescriptor, json: Json): SerialDescriptor? {
        val module = json.serializersModule
        module.getContextualDescriptor(descriptor)?.let { return it }
        val captured = descriptor.capturedKClass
        // Well-known types produce better schemas than what Class.forName reflection would derive
        if (captured != null && captured in wellKnownContextual) return null
        return try {
            val clazz = captured ?: Class.forName(descriptor.serialName).kotlin
            module.getContextual(clazz)?.descriptor
        } catch (_: Exception) {
            null
        }
    }

    private fun handleDescriptorPolymorphic(
        descriptor: SerialDescriptor,
        json: Json,
        cache: MutableMap<String, JsonSchema>,
        slugOwners: MutableMap<SchemaSlug, ClassIdentity>,
    ): JsonSchema {
        // Sealed class descriptor structure:
        //   element 0 = "type" discriminator (PrimitiveKind.STRING)
        //   element 1 = "value" wrapper whose sub-elements are the actual subclass descriptors
        val subclasses = mutableSetOf<JsonSchema>()
        if (descriptor.elementsCount >= 2) {
            val valueDescriptor = descriptor.getElementDescriptor(1)
            for (i in 0 until valueDescriptor.elementsCount) {
                val subDescriptor = valueDescriptor.getElementDescriptor(i)
                val schema = fromDescriptor(subDescriptor, json, cache, slugOwners)
                val enriched = addDescriptorSealedDiscriminator(subDescriptor, schema)
                if (enriched is TypeDefinition && enriched.type == "object") {
                    cache[subDescriptor.slug(slugOwners)] = enriched
                    subclasses.add(ReferenceDefinition(subDescriptor.referenceSlug(slugOwners)))
                } else {
                    subclasses.add(enriched)
                }
            }
        }
        if (subclasses.isNotEmpty()) {
            return AnyOfDefinition(subclasses)
        }
        return TypeDefinition(type = "object")
    }

    private fun addDescriptorSealedDiscriminator(
        descriptor: SerialDescriptor,
        schema: JsonSchema,
    ): JsonSchema {
        if (schema is TypeDefinition && schema.type == "object") {
            return schema.copy(
                required = schema.required.orEmpty() + "type",
                properties = schema.properties.orEmpty() +
                    ("type" to EnumDefinition(enum = setOf(descriptor.serialName))),
            )
        }
        return schema
    }

    // --- KType-based entry points (bridge to descriptor-based implementation) ---

    fun fromTypeToSchema(
        type: KType,
        cache: MutableMap<String, JsonSchema>,
        slugOwners: MutableMap<SchemaSlug, ClassIdentity> = mutableMapOf(),
    ): JsonSchema {
        val slug = type.slug(slugOwners)
        cache[slug]?.let { return it }
        return when (val clazz = type.classifier as KClass<*>) {
            Unit::class -> error("Unit cannot be converted to JsonSchema")
            Int::class -> checkForNull(type, TypeDefinition.INT)
            Long::class -> checkForNull(type, TypeDefinition.LONG)
            Double::class -> checkForNull(type, TypeDefinition.DOUBLE)
            Float::class -> checkForNull(type, TypeDefinition.FLOAT)
            String::class -> checkForNull(type, TypeDefinition.STRING)
            Boolean::class -> checkForNull(type, TypeDefinition.BOOLEAN)
            UUID::class -> checkForNull(type, TypeDefinition.UUID)
            else -> complexTypeToSchema(clazz, type, cache, slugOwners)
        }
    }

    private fun checkForNull(type: KType, schema: JsonSchema): JsonSchema = when (type.isMarkedNullable) {
        true -> OneOfDefinition(NullableDefinition(), schema)
        false -> schema
    }

    private fun complexTypeToSchema(
        clazz: KClass<*>,
        type: KType,
        cache: MutableMap<String, JsonSchema>,
        slugOwners: MutableMap<SchemaSlug, ClassIdentity>,
    ): JsonSchema = try {
        when {
            clazz.isSubclassOf(Enum::class) -> handleEnum(type, clazz, cache, slugOwners)
            clazz.isSubclassOf(Collection::class) -> handleCollection(type, cache, slugOwners)
            clazz.isSubclassOf(Map::class) -> handleMap(type, cache, slugOwners)
            clazz.isSealed -> handleSealed(type, clazz, cache, slugOwners)
            clazz.primaryConstructor == null -> TypeDefinition(type = "object")
            else -> handleObject(type, clazz, cache, slugOwners)
        }
    } catch (_: Exception) {
        TypeDefinition(type = "object")
    }

    private fun handleEnum(
        type: KType,
        clazz: KClass<*>,
        cache: MutableMap<String, JsonSchema>,
        slugOwners: MutableMap<SchemaSlug, ClassIdentity>,
    ): JsonSchema {
        cache[type.slug(slugOwners)] = ReferenceDefinition(type.referenceSlug(slugOwners))
        val options = clazz.java.enumConstants.map { it.toString() }.toSet()
        return EnumDefinition(enum = options)
    }

    private fun handleCollection(
        type: KType,
        cache: MutableMap<String, JsonSchema>,
        slugOwners: MutableMap<SchemaSlug, ClassIdentity>,
    ): JsonSchema {
        val elementType = type.arguments.firstOrNull()?.type
        if (elementType == null) {
            val definition = ArrayDefinition(TypeDefinition(type = "object"))
            return when (type.isMarkedNullable) {
                true -> OneOfDefinition(NullableDefinition(), definition)
                false -> definition
            }
        }
        val elementSchema = fromTypeToSchema(elementType, cache, slugOwners).let {
            if (it.isObjectOrEnum()) {
                cache[elementType.slug(slugOwners)] = it
                ReferenceDefinition(elementType.referenceSlug(slugOwners))
            } else {
                it
            }
        }
        val definition = ArrayDefinition(elementSchema)
        return when (type.isMarkedNullable) {
            true -> OneOfDefinition(NullableDefinition(), definition)
            false -> definition
        }
    }

    private fun handleMap(
        type: KType,
        cache: MutableMap<String, JsonSchema>,
        slugOwners: MutableMap<SchemaSlug, ClassIdentity>,
    ): JsonSchema {
        val keyType = type.arguments.firstOrNull()?.type
        val keyClass = keyType?.classifier as? KClass<*>
        if (keyClass == null || type.arguments.size < 2) {
            val definition = MapDefinition(TypeDefinition(type = "object"))
            return when (type.isMarkedNullable) {
                true -> OneOfDefinition(NullableDefinition(), definition)
                false -> definition
            }
        }
        // Map keys must serialize as strings in JSON. String, Enum, value classes,
        // and any type with a string-based serializer are all valid.
        val valueType = type.arguments[1].type ?: error("Map value type argument missing")
        val valueSchema = fromTypeToSchema(valueType, cache, slugOwners).let {
            if (it is TypeDefinition && it.type == "object") {
                cache[valueType.slug(slugOwners)] = it
                ReferenceDefinition(valueType.referenceSlug(slugOwners))
            } else {
                it
            }
        }
        val definition = MapDefinition(valueSchema)
        return when (type.isMarkedNullable) {
            true -> OneOfDefinition(NullableDefinition(), definition)
            false -> definition
        }
    }

    private fun handleSealed(
        type: KType,
        clazz: KClass<*>,
        cache: MutableMap<String, JsonSchema>,
        slugOwners: MutableMap<SchemaSlug, ClassIdentity>,
    ): JsonSchema {
        val subclasses = clazz.sealedSubclasses
            .map { it.createType(type.arguments) }
            .map { t ->
                val schema = fromTypeToSchema(t, cache, slugOwners)
                val enriched = addSealedDiscriminator(t, schema)
                if (enriched is TypeDefinition && enriched.type == "object") {
                    cache[t.slug(slugOwners)] = enriched
                    ReferenceDefinition(t.referenceSlug(slugOwners))
                } else {
                    enriched
                }
            }
            .toSet()
        return AnyOfDefinition(subclasses)
    }

    private fun addSealedDiscriminator(type: KType, schema: JsonSchema): JsonSchema {
        if (schema is TypeDefinition && schema.type == "object") {
            val clazz = type.classifier as KClass<*>
            val qualifier = clazz.annotations.filterIsInstance<SerialName>().firstOrNull()?.value
                ?: clazz.qualifiedName!!
            return schema.copy(
                required = schema.required.orEmpty() + "type",
                properties = schema.properties.orEmpty() + ("type" to EnumDefinition(enum = setOf(qualifier))),
            )
        }
        return schema
    }

    private fun handleObject(
        type: KType,
        clazz: KClass<*>,
        cache: MutableMap<String, JsonSchema>,
        slugOwners: MutableMap<SchemaSlug, ClassIdentity>,
    ): JsonSchema {
        val slug = type.slug(slugOwners)
        val referenceSlug = type.referenceSlug(slugOwners)
        cache[slug] = ReferenceDefinition(referenceSlug)
        val typeMap = clazz.typeParameters.zip(type.arguments).toMap()
        val props = serializableProperties(clazz)
            .filterNot { it.javaField == null }
            .associate { prop ->
                val schema = when {
                    prop.needsGenericInjection(typeMap) -> handleNestedGenerics(typeMap, prop, cache, slugOwners)
                    typeMap.containsKey(prop.returnType.classifier) -> handleGenericProperty(prop, typeMap, cache, slugOwners)
                    else -> handleProperty(prop, cache, slugOwners)
                }
                val nullChecked = when (prop.returnType.isMarkedNullable && !schema.isNullable()) {
                    true -> OneOfDefinition(NullableDefinition(), schema)
                    false -> schema
                }
                serializableName(prop) to nullChecked
            }
        val required = serializableProperties(clazz)
            .asSequence()
            .filterNot { it.javaField == null }
            .filterNot { it.returnType.isMarkedNullable }
            .filterNot { prop ->
                clazz.primaryConstructor
                    ?.parameters
                    ?.find { it.name == prop.name }
                    ?.isOptional
                    ?: false
            }
            .map { serializableName(it) }
            .toSet()
        val definition = TypeDefinition(type = "object", properties = props, required = required)
        cache[slug] = definition
        return definition
    }

    private fun handleNestedGenerics(
        typeMap: Map<KTypeParameter, KTypeProjection>,
        prop: KProperty<*>,
        cache: MutableMap<String, JsonSchema>,
        slugOwners: MutableMap<SchemaSlug, ClassIdentity>,
    ): JsonSchema {
        val propClass = prop.returnType.classifier as KClass<*>
        val types = prop.returnType.arguments.map {
            val typeSymbol = it.type.toString()
            typeMap.filterKeys { k -> k.name == typeSymbol }.values.first()
        }
        val constructedType = propClass.createType(types)
        return fromTypeToSchema(constructedType, cache, slugOwners).let {
            if (it.isObjectOrEnum()) {
                cache[constructedType.slug(slugOwners)] = it
                ReferenceDefinition(constructedType.referenceSlug(slugOwners))
            } else {
                it
            }
        }
    }

    private fun handleGenericProperty(
        prop: KProperty<*>,
        typeMap: Map<KTypeParameter, KTypeProjection>,
        cache: MutableMap<String, JsonSchema>,
        slugOwners: MutableMap<SchemaSlug, ClassIdentity>,
    ): JsonSchema {
        val type = typeMap[prop.returnType.classifier]?.type
            ?: error("Failed to resolve generic type for ${prop.name}")
        return fromTypeToSchema(type, cache, slugOwners).let {
            if (it.isObjectOrEnum()) {
                cache[type.slug(slugOwners)] = it
                ReferenceDefinition(type.referenceSlug(slugOwners))
            } else {
                it
            }
        }
    }

    private fun handleProperty(
        prop: KProperty<*>,
        cache: MutableMap<String, JsonSchema>,
        slugOwners: MutableMap<SchemaSlug, ClassIdentity>,
    ): JsonSchema = fromTypeToSchema(prop.returnType, cache, slugOwners).let {
        if (it.isObjectOrEnum()) {
            cache[prop.returnType.slug(slugOwners)] = it
            ReferenceDefinition(prop.returnType.referenceSlug(slugOwners))
        } else {
            it
        }
    }

    private fun serializableProperties(clazz: KClass<*>): Collection<KProperty1<out Any, *>> {
        return clazz.memberProperties
            .filterNot { it.hasAnnotation<Transient>() }
            .filter { clazz.primaryConstructor?.parameters?.map { p -> p.name }?.contains(it.name) ?: true }
    }

    private fun serializableName(property: KProperty1<out Any, *>): String =
        property.annotations.filterIsInstance<SerialName>().firstOrNull()?.value ?: property.name

    private fun KProperty<*>.needsGenericInjection(typeMap: Map<KTypeParameter, KTypeProjection>): Boolean {
        val typeSymbols = returnType.arguments.map { it.type.toString() }
        return typeMap.any { (k, _) -> typeSymbols.contains(k.name) }
    }

    private fun JsonSchema.isObjectOrEnum(): Boolean {
        val isObj = this is TypeDefinition && type == "object"
        val isObjOneOf = this is OneOfDefinition && oneOf.any { it is TypeDefinition && (it as TypeDefinition).type == "object" }
        val isEnum = this is EnumDefinition
        val isEnumOneOf = this is OneOfDefinition && oneOf.any { it is EnumDefinition }
        return isObj || isObjOneOf || isEnum || isEnumOneOf
    }

    private fun JsonSchema.isNullable(): Boolean = this is OneOfDefinition && oneOf.any { it is NullableDefinition }
}

private const val COMPONENT_SLUG = "#/components/schemas"

/** A candidate schema component name, e.g. "Status", before collision resolution. */
@JvmInline
value class SchemaSlug(val value: String)

/**
 * Identifies "the same class" for slug collision detection: by `KClass.qualifiedName` when
 * available, otherwise by the descriptor's own shape (kind + element names). Shape, not the
 * `SerialDescriptor` itself, is the fallback because hand-built descriptors (e.g. a custom
 * `KSerializer` using `buildClassSerialDescriptor`) can fail `equals` against themselves once
 * nested element descriptors are involved, even for the same real class — shape sidesteps that
 * by only ever looking at this descriptor's own kind and element names.
 */
sealed class ClassIdentity {
    data class ByQualifiedName(val name: String) : ClassIdentity()
    data class ByShape(val kind: String, val elementNames: List<String>) : ClassIdentity()
}

private fun resolveSlug(
    shortSlug: String,
    identity: ClassIdentity,
    slugOwners: MutableMap<SchemaSlug, ClassIdentity>,
    longSlug: () -> String,
): String {
    val slug = SchemaSlug(shortSlug)
    val owner = slugOwners[slug]
    if (owner == null) {
        slugOwners[slug] = identity
        return shortSlug
    }
    if (owner == identity) return shortSlug
    val disambiguated = longSlug()
    slugOwners.putIfAbsent(SchemaSlug(disambiguated), identity)
    return disambiguated
}

fun KType.slug(slugOwners: MutableMap<SchemaSlug, ClassIdentity> = mutableMapOf()): String = when {
    arguments.isNotEmpty() -> {
        val clazz = classifier as KClass<*>
        val classNames = arguments.map { (it.type?.classifier as? KClass<*>)?.schemaSlug(slugOwners) ?: "Any" }
        classNames.joinToString(separator = "-", prefix = "${clazz.schemaSlug(slugOwners)}-")
    }
    else -> (classifier as KClass<*>).schemaSlug(slugOwners)
}

fun KType.referenceSlug(slugOwners: MutableMap<SchemaSlug, ClassIdentity> = mutableMapOf()): String =
    "$COMPONENT_SLUG/${slug(slugOwners)}"

@OptIn(ExperimentalSerializationApi::class)
fun SerialDescriptor.slug(slugOwners: MutableMap<SchemaSlug, ClassIdentity> = mutableMapOf()): String {
    val name = serialName.removeSuffix("?")
    val parts = name.split('.')
    val classStart = parts.indexOfFirst { it.firstOrNull()?.isUpperCase() == true }
    val shortSlug = if (classStart >= 0) {
        parts.subList(classStart, parts.size).joinToString("")
    } else {
        parts.last()
    }
    // capturedKClass is unreliable (empirically always null for plain classes with this
    // kotlinx.serialization version), but the un-shortened serialName is already a fully
    // qualified name unless @SerialName overrode it to exactly the colliding short form — in
    // which case there's no naming info left at all, and only then do we fall back to shape.
    // Preferring the string here also keeps this path's identity consistent with the reflection
    // path's (KClass.schemaSlug()) for the same real class.
    val qualifiedName = capturedKClass?.qualifiedName ?: name.takeIf { it != shortSlug }
    val identity = qualifiedName?.let { ClassIdentity.ByQualifiedName(it) }
        ?: ClassIdentity.ByShape(kind.toString(), (0 until elementsCount).map { getElementName(it) })
    return resolveSlug(shortSlug, identity, slugOwners) {
        qualifiedName?.replace(".", "") ?: "${shortSlug}_${slugOwners.size}"
    }
}

@OptIn(ExperimentalSerializationApi::class)
fun SerialDescriptor.referenceSlug(slugOwners: MutableMap<SchemaSlug, ClassIdentity> = mutableMapOf()): String =
    "$COMPONENT_SLUG/${slug(slugOwners)}"

private fun KClass<*>.schemaSlug(slugOwners: MutableMap<SchemaSlug, ClassIdentity>): String {
    if (java.packageName == "java.lang") return simpleName!!
    if (java.packageName == "java.util") return simpleName!!
    val qualifiedName = qualifiedName ?: return simpleName!!
    val shortSlug = qualifiedName.replace(java.packageName, "").replace(".", "")
    return resolveSlug(shortSlug, ClassIdentity.ByQualifiedName(qualifiedName), slugOwners) { qualifiedName.replace(".", "") }
}
