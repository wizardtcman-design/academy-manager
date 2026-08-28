package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "bank_accounts")
data class BankAccount(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val bankName: String = "",
    val type: String = "CARD", // CARD, ACCOUNT, SHABA
    val accountTitle: String = "",
    val cardNumber: String = "", // Full 16-digit card number
    val maskedNumber: String = "", // e.g. "6037-9971-****-1234"
    val shabaNumber: String = "", // e.g. "IR123456789012345678901234"
    val accountNumber: String = "", // e.g. "1234567890"
    val accountHolder: String = "",
    val isDefault: Boolean = false,
    val isActive: Boolean = true,
    val isSampleData: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    val displayCardNumber: String
        get() {
            val digits = cardNumber.filter { it.isDigit() }
            return if (digits.length == 16) {
                "${digits.substring(0, 4)} - ${digits.substring(4, 8)} - ${digits.substring(8, 12)} - ${digits.substring(12, 16)}"
            } else if (digits.isNotEmpty()) {
                digits.chunked(4).joinToString(" - ")
            } else {
                cardNumber
            }
        }

    val displayShaba: String
        get() {
            val clean = shabaNumber.trim().uppercase()
            return if (clean.startsWith("IR")) {
                val withoutPrefix = clean.removePrefix("IR").filter { it.isDigit() }
                "IR - " + withoutPrefix.chunked(4).joinToString(" ")
            } else if (clean.isNotEmpty()) {
                "IR - " + clean.filter { it.isDigit() }.chunked(4).joinToString(" ")
            } else {
                ""
            }
        }

    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "bankName" to bankName,
        "type" to type,
        "accountTitle" to accountTitle,
        "cardNumber" to cardNumber,
        "maskedNumber" to maskedNumber,
        "shabaNumber" to shabaNumber,
        "accountNumber" to accountNumber,
        "accountHolder" to accountHolder,
        "isDefault" to isDefault,
        "isActive" to isActive,
        "isSampleData" to isSampleData,
        "createdAt" to createdAt
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): BankAccount {
            val rawCard = map["cardNumber"] as? String ?: ""
            return BankAccount(
                id = map["id"] as? String ?: UUID.randomUUID().toString(),
                bankName = map["bankName"] as? String ?: "",
                type = map["type"] as? String ?: "CARD",
                accountTitle = map["accountTitle"] as? String ?: "",
                cardNumber = rawCard,
                maskedNumber = map["maskedNumber"] as? String ?: maskCardNumber(rawCard),
                shabaNumber = map["shabaNumber"] as? String ?: "",
                accountNumber = map["accountNumber"] as? String ?: "",
                accountHolder = map["accountHolder"] as? String ?: "",
                isDefault = map["isDefault"] as? Boolean ?: false,
                isActive = map["isActive"] as? Boolean ?: true,
                isSampleData = map["isSampleData"] as? Boolean ?: false,
                createdAt = (map["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
            )
        }

        fun maskCardNumber(cardNum: String): String {
            val digits = cardNum.filter { it.isDigit() }
            return if (digits.length >= 16) {
                "${digits.substring(0, 4)} - **** - **** - ${digits.takeLast(4)}"
            } else if (digits.length >= 4) {
                "**** **** **** ${digits.takeLast(4)}"
            } else {
                cardNum
            }
        }
    }
}
