package com.sungyoon.helper.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class SetDefinition(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val items: List<SetItem> = emptyList(),
    val repeatEnabled: Boolean = false,
)
