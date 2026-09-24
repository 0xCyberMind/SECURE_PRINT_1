package com.example.privprint.data.model

data class Shop(
    val id: String,
    val name: String,
    val address: String,
    val permanentQrPayload: String,
    val isVerified: Boolean = true,
    val isOnline: Boolean = true,
    val supportedColor: Boolean = true,
    val supportedDuplex: Boolean = true,
    val queueCount: Int = 0
) {
    companion object {
        fun createQrPayload(shopId: String, shopName: String): String {
            return "privprint://shop?id=$shopId&name=${java.net.URLEncoder.encode(shopName, "UTF-8")}&v=1"
        }
    }
}
