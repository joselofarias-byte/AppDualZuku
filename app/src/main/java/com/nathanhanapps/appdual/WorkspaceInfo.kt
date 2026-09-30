package com.nathanhanapps.appdual

data class WorkspaceInfo(
    val userId: Int,
    val name: String,
    val flags: Int,
    val isRunning: Boolean
) {
    val isMainUser: Boolean get() = userId == 0
    // UserInfo.FLAG_FULL = 0x400. Full users can become the foreground
    // interactive user; profiles (work/clone/private) cannot be switched to.
    val isFullUser: Boolean get() = (flags and 0x400) != 0
    val displayName: String get() = name.ifBlank { "User $userId" }
}
