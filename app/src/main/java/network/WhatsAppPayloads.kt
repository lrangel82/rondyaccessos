package com.larangel.rondyaccesos.models.network

import kotlinx.serialization.Serializable

@Serializable
data class WhatsAppTextParam(
    val type: String = "text",
    val parameter_name: String,
    val text: String
)

@Serializable
data class WhatsAppComponent(
    val type: String, // "header" or "body"
    val parameters: List<WhatsAppTextParam>
)

@Serializable
data class WhatsAppLanguage(
    val code: String = "es_MX"
)

@Serializable
data class WhatsAppTemplate(
    val name: String,
    val language: WhatsAppLanguage = WhatsAppLanguage(),
    val components: List<WhatsAppComponent>
)

@Serializable
data class WhatsAppTemplateRequest(
    val messaging_product: String = "whatsapp",
    val to: String,
    val type: String = "template",
    val template: WhatsAppTemplate
)

@Serializable
data class WhatsAppImageRef(
    val id: String
)

@Serializable
data class WhatsAppImageRequest(
    val messaging_product: String = "whatsapp",
    val to: String,
    val type: String = "image",
    val image: WhatsAppImageRef
)

@Serializable
data class MetaMediaUploadResponse(
    val id: String
)