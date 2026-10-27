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
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.serializer
import kotlinx.serialization.serializerOrNull
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
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaField

class UnsupportedSchemaException(message: String, cause: Throwable?) : RuntimeException(message, cause) {
    constructor(message: String) : this(message, null)
}

@OptIn(ExperimentalSerializationApi::class)
object SchemaGenerator {

    fun fromDescriptor(
        descriptor: SerialDescriptor,
        json: Json,
        cache: MutableMap<String, JsonSchema>,
        slugOwners: MutableMap<SchemaSlug, ClassIdentity>,
        // Set only for a sealed/polymorphic child — see disambiguateByShape in SchemaSlug.kt.
        outerSlug: String? = null,
    ): JsonSchema {
        if (descriptor.isInline) {
            val inner = descriptor.getElementDescriptor(0)
            return fromDescriptor(inner, json, cache, slugOwners)
        }
        // Only object/enum kinds cache themselves below; every other kind's lookup here only
        // ever picks up an externally-installed override (e.g. customTypes), so it's safe to
        // use the plain, non-disambiguated slug.
        val selfCaching = descriptor.kind == SerialKind.ENUM ||
            descriptor.kind == StructureKind.CLASS ||
            descriptor.kind == StructureKind.OBJECT
        val slug = if (selfCaching) descriptor.slug(slugOwners, outerSlug) else descriptor.plainSlug()
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
        val definition = descriptorObjectDefinition(descriptor, json, cache, slugOwners)
        cache[slug] = definition
        return definition
    }

    private fun descriptorObjectDefinition(
        descriptor: SerialDescriptor,
        json: Json,
        cache: MutableMap<String, JsonSchema>,
        slugOwners: MutableMap<SchemaSlug, ClassIdentity>,
    ): TypeDefinition {
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
        return TypeDefinition(
            type = "object",
            properties = props,
            required = required,
        )
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
        //   element 0 = discriminator (PrimitiveKind.STRING), always named "type" whatever the wire name is
        //   element 1 = "value" wrapper whose sub-elements are the actual subclass descriptors
        val subclasses = mutableSetOf<JsonSchema>()
        if (descriptor.elementsCount >= 2) {
            val outerSlug = descriptor.slug(slugOwners)
            val discriminator = descriptor.classDiscriminator(json)
            val valueDescriptor = descriptor.getElementDescriptor(1)
            for (i in 0 until valueDescriptor.elementsCount) {
                val subDescriptor = valueDescriptor.getElementDescriptor(i)
                subclasses.add(descriptorVariant(subDescriptor, discriminator, outerSlug, json, cache, slugOwners))
            }
        }
        if (subclasses.isNotEmpty()) {
            return AnyOfDefinition(subclasses)
        }
        return TypeDefinition(type = "object")
    }

    private fun descriptorVariant(
        descriptor: SerialDescriptor,
        discriminator: String,
        outerSlug: String,
        json: Json,
        cache: MutableMap<String, JsonSchema>,
        slugOwners: MutableMap<SchemaSlug, ClassIdentity>,
    ): JsonSchema {
        val isObject = !descriptor.isInline &&
            (descriptor.kind == StructureKind.CLASS || descriptor.kind == StructureKind.OBJECT)
        if (!isObject) return fromDescriptor(descriptor, json, cache, slugOwners, outerSlug)
        refuseUndescribable(descriptor.serialName, descriptor.elementNames, discriminator, json)
        val slug = when (descriptor.classDiscriminator(json) == discriminator) {
            true -> descriptor.slug(slugOwners, outerSlug = outerSlug, discriminator = discriminator)
            false -> descriptor.slug(
                slugOwners, outerSlug = outerSlug, discriminator = discriminator, parent = outerSlug,
            )
        }
        return taggedVariant(slug, discriminator, descriptor.serialName, cache) {
            descriptorObjectDefinition(descriptor, json, cache, slugOwners)
        }
    }

    private fun SerialDescriptor?.classDiscriminator(json: Json): String =
        this?.annotations?.filterIsInstance<JsonClassDiscriminator>()?.firstOrNull()?.discriminator
            ?: json.configuration.classDiscriminator

    // The slug's identity limits a cached entry to this variant's placeholder, plain use, or same tag.
    private fun taggedVariant(
        slug: String,
        discriminator: String,
        serialName: String,
        cache: MutableMap<String, JsonSchema>,
        build: () -> TypeDefinition,
    ): JsonSchema {
        val reference = ReferenceDefinition("$COMPONENT_SLUG/$slug")
        val tag = EnumDefinition(enum = setOf(serialName))
        val cached = cache[slug]
        cached?.let { if (it !is TypeDefinition || it.type != "object") return it }
        val existing = cached as? TypeDefinition
        existing?.properties?.get(discriminator)?.takeIf { it != tag }?.let {
            throw UnsupportedSchemaException(
                "Cannot describe sealed variant '$serialName': its cached schema already sets '$discriminator' to $it",
            )
        }
        cache[slug] = reference
        val base = existing ?: build()
        cache[slug] = base.copy(
            required = base.required.orEmpty() + discriminator,
            properties = base.properties.orEmpty() + (discriminator to tag),
        )
        return reference
    }

    private fun refuseUndescribable(variant: String, names: Iterable<String>, discriminator: String, json: Json) {
        val reason = when {
            json.configuration.classDiscriminatorMode == ClassDiscriminatorMode.NONE ->
                "ClassDiscriminatorMode.NONE leaves the discriminator out on encode, but decoding requires it"
            json.configuration.useArrayPolymorphism -> "useArrayPolymorphism encodes it as an array"
            discriminator in names -> "kotlinx refuses to encode its property '$discriminator' next to the tag"
            else -> return
        }
        throw UnsupportedSchemaException("Cannot describe sealed variant '$variant': $reason")
    }

    // --- KType-based entry points (bridge to descriptor-based implementation) ---

    fun fromTypeToSchema(
        type: KType,
        json: Json,
        cache: MutableMap<String, JsonSchema>,
        slugOwners: MutableMap<SchemaSlug, ClassIdentity>,
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
            else -> complexTypeToSchema(clazz, type, null, json, cache, slugOwners)
        }
    }

    private fun checkForNull(type: KType, schema: JsonSchema): JsonSchema = when (type.isMarkedNullable) {
        true -> OneOfDefinition(NullableDefinition(), schema)
        false -> schema
    }

    private fun complexTypeToSchema(
        clazz: KClass<*>,
        type: KType,
        outer: KType?,
        json: Json,
        cache: MutableMap<String, JsonSchema>,
        slugOwners: MutableMap<SchemaSlug, ClassIdentity>,
    ): JsonSchema = degradeToObject(TypeDefinition(type = "object")) {
        when {
            clazz.isSubclassOf(Enum::class) -> handleEnum(type, clazz, cache, slugOwners)
            clazz.isSubclassOf(Collection::class) -> handleCollection(type, json, cache, slugOwners)
            clazz.isSubclassOf(Map::class) -> handleMap(type, json, cache, slugOwners)
            clazz.isSealed -> handleSealed(type, clazz, outer ?: type, json, cache, slugOwners)
            clazz.primaryConstructor == null -> TypeDefinition(type = "object")
            else -> handleObject(type, clazz, json, cache, slugOwners)
        }
    }

    private inline fun <S : JsonSchema> degradeToObject(fallback: S, schema: () -> S): S = try {
        schema()
    } catch (e: UnsupportedSchemaException) {
        throw e
    } catch (_: Exception) {
        fallback
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
        json: Json,
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
        val elementSchema = fromTypeToSchema(elementType, json, cache, slugOwners).let {
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
        json: Json,
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
        val valueSchema = fromTypeToSchema(valueType, json, cache, slugOwners).let {
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
        outer: KType,
        json: Json,
        cache: MutableMap<String, JsonSchema>,
        slugOwners: MutableMap<SchemaSlug, ClassIdentity>,
    ): JsonSchema {
        val discriminator = outer.kotlinxDescriptor(json).classDiscriminator(json)
        val subclasses = clazz.sealedSubclasses
            .map { it.createType(type.arguments) }
            .map { t ->
                val subclass = t.classifier as KClass<*>
                when (subclass.isSealed) {
                    // kotlinx tags a nested sealed subtype's variants with the outer parent's discriminator.
                    true -> cache[t.slug(slugOwners)]
                        ?: complexTypeToSchema(subclass, t, outer, json, cache, slugOwners)
                    false -> typeVariant(t, subclass, outer, discriminator, json, cache, slugOwners)
                }
            }
            .toSet()
        return AnyOfDefinition(subclasses)
    }

    private fun typeVariant(
        type: KType,
        clazz: KClass<*>,
        outer: KType,
        discriminator: String,
        json: Json,
        cache: MutableMap<String, JsonSchema>,
        slugOwners: MutableMap<SchemaSlug, ClassIdentity>,
    ): JsonSchema {
        if (clazz.isSubclassOf(Enum::class)) return fromTypeToSchema(type, json, cache, slugOwners)
        val descriptor = type.kotlinxDescriptor(json)
        val serialName = descriptor?.serialName
            ?: clazz.findAnnotation<SerialName>()?.value
            ?: checkNotNull(clazz.qualifiedName)
        refuseUndescribable(serialName, descriptor?.elementNames ?: listOf(), discriminator, json)
        val slug = when (descriptor.classDiscriminator(json) == discriminator) {
            true -> type.slug(slugOwners, discriminator = discriminator)
            false -> type.slug(slugOwners, discriminator = discriminator, parent = outer.slug(slugOwners))
        }
        return taggedVariant(slug, discriminator, serialName, cache) {
            degradeToObject(TypeDefinition(type = "object")) {
                when (clazz.primaryConstructor) {
                    null -> TypeDefinition(type = "object")
                    else -> objectDefinition(type, clazz, json, cache, slugOwners)
                }
            }
        }
    }

    // kotlinx throws on star projections instead of returning null.
    private fun KType.kotlinxDescriptor(json: Json): SerialDescriptor? = try {
        json.serializersModule.serializerOrNull(this)?.descriptor
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun handleObject(
        type: KType,
        clazz: KClass<*>,
        json: Json,
        cache: MutableMap<String, JsonSchema>,
        slugOwners: MutableMap<SchemaSlug, ClassIdentity>,
    ): JsonSchema {
        val slug = type.slug(slugOwners)
        val referenceSlug = type.referenceSlug(slugOwners)
        cache[slug] = ReferenceDefinition(referenceSlug)
        val definition = objectDefinition(type, clazz, json, cache, slugOwners)
        cache[slug] = definition
        return definition
    }

    private fun objectDefinition(
        type: KType,
        clazz: KClass<*>,
        json: Json,
        cache: MutableMap<String, JsonSchema>,
        slugOwners: MutableMap<SchemaSlug, ClassIdentity>,
    ): TypeDefinition {
        val typeMap = clazz.typeParameters.zip(type.arguments).toMap()
        val props = serializableProperties(clazz)
            .filterNot { it.javaField == null }
            .associate { prop ->
                val schema = when {
                    prop.needsGenericInjection(typeMap) -> handleNestedGenerics(typeMap, prop, json, cache, slugOwners)
                    typeMap.containsKey(prop.returnType.classifier) ->
                        handleGenericProperty(prop, typeMap, json, cache, slugOwners)
                    else -> handleProperty(prop, json, cache, slugOwners)
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
        return TypeDefinition(type = "object", properties = props, required = required)
    }

    private fun handleNestedGenerics(
        typeMap: Map<KTypeParameter, KTypeProjection>,
        prop: KProperty<*>,
        json: Json,
        cache: MutableMap<String, JsonSchema>,
        slugOwners: MutableMap<SchemaSlug, ClassIdentity>,
    ): JsonSchema {
        val propClass = prop.returnType.classifier as KClass<*>
        val types = prop.returnType.arguments.map {
            val typeSymbol = it.type.toString()
            typeMap.filterKeys { k -> k.name == typeSymbol }.values.first()
        }
        val constructedType = propClass.createType(types)
        return fromTypeToSchema(constructedType, json, cache, slugOwners).let {
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
        json: Json,
        cache: MutableMap<String, JsonSchema>,
        slugOwners: MutableMap<SchemaSlug, ClassIdentity>,
    ): JsonSchema {
        val type = typeMap[prop.returnType.classifier]?.type
            ?: error("Failed to resolve generic type for ${prop.name}")
        return fromTypeToSchema(type, json, cache, slugOwners).let {
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
        json: Json,
        cache: MutableMap<String, JsonSchema>,
        slugOwners: MutableMap<SchemaSlug, ClassIdentity>,
    ): JsonSchema = fromTypeToSchema(prop.returnType, json, cache, slugOwners).let {
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
