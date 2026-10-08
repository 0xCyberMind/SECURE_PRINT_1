package com.example.privprint.ui.components

fun maskedPhoneNumber(phoneNumber: String?): String {
    val digits = phoneNumber.orEmpty().filter(Char::isDigit)
    return if (digits.length < 3) "Hidden" else "•••• ${digits.takeLast(2)}"
}

const val PRIVATE_DOCUMENT_LABEL = "Private document"
const val PRIVATE_AUDIT_DETAILS_LABEL = "Details hidden to protect private data."

fun displayShopAddress(address: String): String =
    if (address.startsWith("Station Operator:", ignoreCase = true)) "Verified print shop" else address
