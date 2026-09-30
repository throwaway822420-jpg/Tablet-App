package app.slate.tablet.ai

import com.anthropic.core.JsonValue
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaFallbacksParam
import com.anthropic.models.beta.messages.BetaImageBlockParam
import com.anthropic.models.beta.messages.BetaJsonOutputFormat

/** Building blocks shared by Slate's requests. */
object Requests {
    fun image(base64: String, png: Boolean = true): BetaContentBlockParam = BetaContentBlockParam.ofImage(
        BetaImageBlockParam.builder()
            .source(
                BetaBase64ImageSource.builder()
                    .mediaType(if (png) BetaBase64ImageSource.MediaType.IMAGE_PNG else BetaBase64ImageSource.MediaType.IMAGE_JPEG)
                    .data(base64)
                    .build(),
            )
            .build(),
    )

    val FALLBACK_BETA: AnthropicBeta = AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01
    fun fallbacks(): BetaFallbacksParam = BetaFallbacksParam.ofDefault()

    /** A JSON-schema output format from a plain map schema. */
    fun jsonFormat(schema: Map<String, Any>): BetaJsonOutputFormat {
        val b = BetaJsonOutputFormat.Schema.builder()
        for ((k, v) in schema) b.putAdditionalProperty(k, JsonValue.from(v))
        return BetaJsonOutputFormat.builder().schema(b.build()).build()
    }

    fun obj(vararg props: Pair<String, Any>): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(*props),
        "required" to props.map { it.first },
        "additionalProperties" to false,
    )

    val STRING: Map<String, Any> = mapOf("type" to "string")
}
