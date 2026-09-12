package com.lateral.agent

import org.junit.Assert.*
import org.junit.Test

class ManagedAgentProtocolTest {
    @Test fun projectNamesCannotEscapeOrBecomeShellText() {
        listOf("../outside", "/tmp/x", "x/y", "a;id", "a\nb", "", "a".repeat(65)).forEach { invalid ->
            assertTrue(invalid, runCatching { ManagedAgentProtocol.validateProjectName(invalid) }.isFailure)
        }
        assertEquals("my-app_2", ManagedAgentProtocol.validateProjectName(" my-app_2 "))
    }

    @Test fun returnedProjectMustBeStrictlyInsideManagedRoot() {
        val base = "/data/data/com.termux/files/home/lateral-agent/projects"
        listOf(base, "$base/../private", "$base-other/project", "$base//project", "$base/./project").forEach {
            assertTrue(it, runCatching { ManagedAgentProtocol.validateProjectRoot(it, base) }.isFailure)
        }
        assertEquals("$base/my-app", ManagedAgentProtocol.validateProjectRoot("$base/my-app", base))
    }

    @Test fun endpointPreservesCursorAndNeverMovesCredentialsToAnotherOrigin() {
        val uri = ManagedAgentProtocol.validatedEndpoint("/v1/jobs/job-1/events?after=42")
        assertEquals("after=42", uri.query)
        assertEquals("127.0.0.1", uri.host)
        assertEquals(8766, uri.port)
        listOf("https://example.com", "//example.com", "/health#fragment").forEach {
            assertTrue(runCatching { ManagedAgentProtocol.validatedEndpoint(it) }.isFailure)
        }
    }

    @Test fun cancellationMustReachAConfirmedTerminalState() {
        assertFalse(ManagedAgentProtocol.isTerminal("CANCELLING"))
        assertFalse(ManagedAgentProtocol.isTerminal("RUNNING"))
        assertTrue(ManagedAgentProtocol.isTerminal("CANCELLED"))
        assertTrue(ManagedAgentProtocol.isTerminal("FAILED"))
        assertTrue(ManagedAgentProtocol.isTerminal("COMPLETED"))
    }

    @Test fun jobIdsCannotInjectPathsOrQueries() {
        listOf("../job", "job/events", "job?after=3", "").forEach {
            assertTrue(runCatching { ManagedAgentProtocol.validateJobId(it) }.isFailure)
        }
    }
}
