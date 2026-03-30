# ktor-open-api-server

Type-safe routing for Ktor with automatic OpenAPI 3.1.0 spec generation.

Requests and responses are modeled as data classes. The library extracts path/query/header parameters, deserializes bodies, resolves authentication principals, and generates the OpenAPI spec — all from the type signatures.

## Setup

```kotlin
install(ContentNegotiation) { json(json) }
install(OpenApi) {
    spec = OpenApiSpec(info = Info(title = "My API", version = "1.0.0"))
    json = json                  // your kotlinx.serialization Json instance
    specPath = "/openapi.json"   // serves the spec here (null to disable)
}
```

## Defining routes

```kotlin
@Serializable
data class UserData(val email: String, val name: String)

@Serializable
data class UserResponse(val id: String, val email: String) : OkResponsePayload()

data class CreateUserPayload(
    val companyId: PathParam,
    val body: Body<UserData>,
) : RequestPayload

routing {
    route("/companies/{companyId}/users") {
        post<CreateUserPayload, UserResponse> {
            val data = payload.body.value()
            UserResponse(id = "1", email = data.email)
        }
    }
}
```

The handler receives `TypedContext<P>` where `payload` is fully extracted and typed. The return type determines the response status code and schema.

## Request payload items

| Type | Maps to | OpenAPI |
|---|---|---|
| `PathParam` | Path segment `{name}` | `in: path` |
| `QueryParam` | `?name=value` | `in: query` |
| `QueryParam?` | Optional query param | `in: query, required: false` |
| `QueryParamList` | `?name=a&name=b` | `in: query, type: array` |
| `HeaderParam` | Request header | `in: header` |
| `CookieParam` | Request cookie | *(not in spec)* |
| `Body<T>` | JSON request body | `requestBody` with schema of `T` |
| `Body<T>?` | Optional JSON body | `requestBody, required: false` |
| `Principal<T>` | Ktor auth principal | *(not in spec — security scheme instead)* |
| `AcceptHeader` | `Accept` header values | *(not in spec)* |
| `MultipartBody` | Multipart form data | `multipart/form-data` |
| `QueryParams` | All query params as map | *(not in spec)* |
| `RequestOrigin` | Connection info | *(not in spec)* |

Parameter names default to the Kotlin property name. Override with `@Name`:

```kotlin
data class MyPayload(
    @Name("X-Request-Id") val requestId: HeaderParam,
    @Name("page_size") val pageSize: QueryParam?,
) : RequestPayload
```

## Response types

### Direct serializable response

The simplest form — a `@Serializable` class extending a status code base:

```kotlin
@Serializable
data class UserResponse(val id: String, val email: String) : OkResponsePayload()

@Serializable
data class CreatedUser(val id: String) : CreatedResponsePayload()
```

### Wrapper responses with `Ok<T>` / `Created<T>`

For responses that are just a body with no headers or cookies:

```kotlin
get<ListPayload, Ok<List<UserResponse>>> {
    Ok(listOf(UserResponse("1", "alice@co.com")))
}
```

### Responses with headers or cookies

```kotlin
data class ExportResponse(
    val body: ResponseBody<UserResponse>,
    @Name("X-Request-Id") val requestId: ResponseHeader,
) : OkResponsePayload()
```

```kotlin
data class AuthResponse(
    val body: ResponseBody<TokenData>,
    @Name("session") val sessionCookie: ResponseCookie,
) : OkResponsePayload()
```

`ResponseCookie` supports `path`, `maxAge`, `expires`, `httpOnly`, `secure`, `encoding`.

### Sealed responses (multiple status codes)

```kotlin
sealed interface CreateResult : ResponsePayload {
    @Serializable
    data class Success(val id: String) : CreatedResponsePayload(), CreateResult
    data class Duplicate(val body: ResponseBody<ErrorInfo>) : BadRequestResponsePayload(), CreateResult
    object Gone : NoContentResponsePayload(), CreateResult
}

post<CreatePayload, CreateResult> {
    when {
        exists -> CreateResult.Duplicate(ResponseBody(ErrorInfo("already exists")))
        deleted -> CreateResult.Gone
        else -> CreateResult.Success(id = newId)
    }
}
```

The spec will contain entries for all variants (201, 400, 204).

### Stream and redirect responses

```kotlin
// File download
post<ExportPayload, ByteStreamResponse> {
    ByteStreamResponse(ContentType.Text.CSV, fileName = "report.csv") {
        write(csvBytes)
    }
}

// Redirect
get<LinkPayload, RedirectResponse> {
    RedirectResponse(url = "https://example.com", permanent = false)
}
```

Stream classes are `open`, so they work in sealed interfaces too:

```kotlin
sealed interface LinkResult : ResponsePayload {
    class Found(url: String) : RedirectResponse(url, permanent = false), LinkResult
    object Missing : NotFoundResponsePayload(), LinkResult
}
```

### Status code base classes

Extend one of the provided base classes to set the status code:

| Class | Status |
|---|---|
| `OkResponsePayload` | 200 |
| `CreatedResponsePayload` | 201 |
| `AcceptedResponsePayload` | 202 |
| `NoContentResponsePayload` | 204 |
| `BadRequestResponsePayload` | 400 |
| `NotFoundResponsePayload` | 404 |
| `InternalServerErrorResponsePayload` | 500 |

For other status codes, override `statusCode` directly on `ResponsePayload`:

```kotlin
data class CustomResponse(
    val body: ResponseBody<MyData>,
    override val statusCode: HttpStatusCode = HttpStatusCode.MultiStatus,
) : ResponsePayload
```

## Authentication and security

Security schemes are detected automatically from Ktor's `authenticate` blocks:

```kotlin
install(Authentication) {
    bearer("api-token") { /* ... */ }
}

routing {
    authenticate("api-token") {
        get<MyPayload, MyResponse> { /* ... */ }
    }
}
```

The generated spec will include `security: [{"api-token": []}]` on the operation. Register schemes in the spec:

```kotlin
install(OpenApi) {
    spec = OpenApiSpec(
        info = Info(title = "My API", version = "1.0.0"),
        components = Components(
            securitySchemes = mutableMapOf(
                "api-token" to SecurityScheme(type = "http", scheme = "bearer"),
            )
        ),
    )
}
```

## Raw call access

When you need the underlying `ApplicationCall` (e.g., for features not yet modeled as payload items):

```kotlin
@OptIn(RawCallAccess::class)
post<MyPayload, MyResponse> {
    val ip = call.request.origin.remoteHost
    MyResponse(ip)
}
```

This is an explicit opt-in — the compiler warns you.

## Schema generation

Schemas are generated from `@Serializable` types using `kotlinx.serialization` descriptors. This means the OpenAPI schema matches the actual JSON wire format, including:

- `@SerialName` — custom field/class names
- `@Transient` — excluded fields
- `@EncodeDefault(NEVER)` — optional fields
- `@Contextual` — resolved via `SerializersModule`, well-known fallbacks, or `customTypes`
- Inline value classes — unwrapped automatically
- Sealed classes — `anyOf` with `type` discriminator

### `@Contextual` types

Fields annotated with `@Contextual` (e.g., `UUID`, `Instant`) are resolved in this order:

1. **`SerializersModule`** — if a contextual serializer is registered, its descriptor drives the schema
2. **Well-known fallbacks** — `UUID` (string/uuid), `Instant` (string/date-time), `LocalDate` (string/date), `LocalDateTime` and `OffsetDateTime` (string/date-time)
3. **`customTypes`** — explicit overrides configured at install time
4. **Fallback** — `{type: "object"}` if none of the above match

For most projects, the well-known fallbacks are sufficient. Use `customTypes` for domain-specific contextual types:

```kotlin
install(OpenApi) {
    customTypes = mapOf(
        typeOf<Money>() to TypeDefinition(type = "integer", format = "int64"),
    )
}
```

## Auto-tagging

Wrap resources to auto-tag all their operations:

```kotlin
fun Application.configureRouting() {
    routing {
        tagged("Users") {
            route("/users") {
                get<ListPayload, Ok<List<UserResponse>>> { /* ... */ }
            }
        }
    }
}
```
