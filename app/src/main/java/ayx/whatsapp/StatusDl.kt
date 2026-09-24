package ayx.whatsapp

/**
 * Status delete pipeline. Deletes a status from BOTH the real WhatsApp
 * account (via Baileys) and this app's local cache (StatusData), so a
 * deleted status does not reappear on the next refresh.
 */
object StatusDl {
    suspend fun deleteEverywhere(id: String): Pair<Boolean, String> {
        if (id.isBlank()) return false to "No status id"
        val ok = GatewayClient.deleteStatus(id)   // real WhatsApp (status@broadcast revoke)
        StatusData.remove(id)                       // this app's cache
        return ok to (if (ok) "Status deleted" else "Delete request sent to WhatsApp")
    }
}
