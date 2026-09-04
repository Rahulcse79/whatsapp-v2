package com.whatsappv2.permission

/** What a caller can do with a permission, and where it currently stands. */
class PermissionRequestState internal constructor(
    val permission: AppPermission,
    val status: PermissionStatus,
    private val onRequest: () -> Unit,
) {
    /**
     * Starts the flow: shows the rationale first if the permission has never been asked
     * for, opens app settings if it is permanently denied, and otherwise asks directly.
     */
    fun request() = onRequest()
}
