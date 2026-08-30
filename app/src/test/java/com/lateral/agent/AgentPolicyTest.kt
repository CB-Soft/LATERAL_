package com.lateral.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPolicyTest {
    @Test
    fun projectApprovalIsExactAndScoped() {
        val root = "C:/workspace/LATERAL_"
        val grants = AgentPolicy.projectCapabilities.map {
            CapabilityGrant(it.id, PermissionScope.PROJECT, root)
        }

        assertTrue(AgentPolicy.grantsProjectAutomation(grants, root))
        assertFalse(AgentPolicy.grantsProjectAutomation(grants, "C:/workspace/other"))
        assertFalse(AgentPolicy.grantsProjectAutomation(grants, ""))
        assertEquals(AgentPolicy.projectCapabilities.map { it.id }, AgentPolicy.grantedIds(grants, root))
    }

    @Test
    fun deniedCapabilitiesNeverBecomeProjectGrants() {
        val root = "C:/workspace/LATERAL_"
        val grants = AgentPolicy.deniedCapabilities.map {
            CapabilityGrant(it, PermissionScope.PROJECT, root)
        }

        assertFalse(AgentPolicy.grantsProjectAutomation(grants, root))
        assertTrue(AgentPolicy.deniedCapabilities.contains("messages.send"))
        assertTrue(AgentPolicy.deniedCapabilities.contains("accessibility.unrestricted"))
    }
}
