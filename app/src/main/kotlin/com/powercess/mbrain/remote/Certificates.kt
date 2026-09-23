package com.powercess.mbrain.remote

import kotlinx.serialization.Serializable

/** Read-only legacy payload. Retained during saves; certificates are no longer imported or used. */
@Serializable
data class TunnelCertificate(val id: String = newId(), val name: String = "", val pem: String = "", val keyPem: String = "")
