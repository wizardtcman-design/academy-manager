package com.example.data.model

import java.util.UUID

data class User(
    val id: String = UUID.randomUUID().toString(),
    val email: String = "",
    val fullName: String = "مدیر آموزشگاه",
    val role: String = "ADMIN",
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "email" to email,
        "fullName" to fullName,
        "role" to role,
        "createdAt" to createdAt
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): User {
            return User(
                id = map["id"] as? String ?: UUID.randomUUID().toString(),
                email = map["email"] as? String ?: "",
                fullName = map["fullName"] as? String ?: "مدیر آموزشگاه",
                role = map["role"] as? String ?: "ADMIN",
                createdAt = (map["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
            )
        }
    }
}
