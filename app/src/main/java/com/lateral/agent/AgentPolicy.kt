package com.lateral.agent

/**
 * LATERAL_ owns the permission boundary. Providers receive only the capabilities
 * explicitly granted for the selected project.
 */
object AgentPolicy {
    val projectCapabilities: List<CapabilityDescriptor> = listOf(
        CapabilityDescriptor("project.files.read", CapabilityRisk.READ, "Read files in the approved project"),
        CapabilityDescriptor("project.files.write", CapabilityRisk.WRITE, "Edit files in the approved project"),
        CapabilityDescriptor("project.git", CapabilityRisk.ACTION, "Run Git operations in the project"),
        CapabilityDescriptor("project.build", CapabilityRisk.ACTION, "Run the approved Android build worker"),
        CapabilityDescriptor("device.logs.read", CapabilityRisk.READ, "Read LATERAL_ development logs"),
        CapabilityDescriptor("workspace.screenshot", CapabilityRisk.READ, "Capture the LATERAL_ workspace"),
        CapabilityDescriptor("android.install.debug", CapabilityRisk.PRIVILEGED, "Install a debug APK after approval"),
    )

    /** Explicit denials are part of the contract, not an absent-capability accident. */
    val deniedCapabilities: Set<String> = setOf(
        "messages.send",
        "contacts.read",
        "accessibility.unrestricted",
        "android.automation.arbitrary",
    )

    fun grantsProjectAutomation(grants: List<CapabilityGrant>, projectRoot: String): Boolean {
        if (projectRoot.isBlank()) return false
        return projectCapabilities.all { capability ->
            grants.any {
                it.capability == capability.id &&
                    it.scope == PermissionScope.PROJECT &&
                    it.projectRoot == projectRoot
            }
        }
    }

    fun grantedIds(grants: List<CapabilityGrant>, projectRoot: String): List<String> =
        projectCapabilities.map { it.id }.filter { capability ->
            grants.any {
                it.capability == capability &&
                    it.scope == PermissionScope.PROJECT &&
                    it.projectRoot == projectRoot
            }
        }
}
