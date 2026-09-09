package com.danielealbano.androidremotecontrolmcp.services.storage

/**
 * Abstracts Android runtime permission checks for testability.
 */
interface PermissionChecker {
    /** Returns true if the given runtime permission is granted. */
    fun hasPermission(permission: String): Boolean

    /**
     * True when the user has granted all-files access (`MANAGE_EXTERNAL_STORAGE`).
     *
     * Separate from [hasPermission] because it is not a runtime permission: it is a special access
     * the user grants on a system settings screen, and it is read back from
     * `Environment.isExternalStorageManager()` rather than from the package manager.
     */
    fun hasAllFilesAccess(): Boolean
}
