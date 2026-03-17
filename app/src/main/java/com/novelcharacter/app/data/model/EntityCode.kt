package com.novelcharacter.app.data.model

import java.util.UUID

fun generateEntityCode(): String = UUID.randomUUID().toString().replace("-", "").take(16)
