package org.deepin.uosai.companion.app

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class RecentConversationStateTest {
    @Test
    fun flattensWorkspaceSummariesIntoSortedRecentConversations() {
        val workspaces = listOf(
            CompanionWorkspace("workspace-a", "A", listOf(
                conversation("completed", TaskStatus.Completed, updatedAt = 900),
                conversation("running", TaskStatus.Running, updatedAt = 1),
            )),
            CompanionWorkspace("workspace-b", "B", listOf(
                conversation("waiting", TaskStatus.AwaitingApproval, updatedAt = 20),
                conversation("recent", null, updatedAt = 1_000),
            )),
        )

        assertEquals(
            listOf("running", "waiting", "recent", "completed"),
            flattenRecentConversations(workspaces).map(CompanionConversation::id),
        )
    }

    @Test
    fun addsAServerCreatedEmptyConversationWithoutInventingAStatus() {
        val existing = listOf(conversation("existing", TaskStatus.Completed, updatedAt = 5))
        val created = conversation("created", status = null, updatedAt = 10, sequence = 3)

        val recent = addRecentConversation(existing, created)

        assertEquals("created", recent.first().id)
        assertNull(recent.first().taskStatus)
        assertEquals(3, recent.first().sequence)
    }

    private fun conversation(
        id: String,
        status: TaskStatus?,
        updatedAt: Long,
        sequence: Long = 0,
    ) = CompanionConversation(
        id = id,
        title = id,
        workspaceId = "workspace",
        updatedAt = updatedAt,
        assistantId = "uos-ai-claw",
        modelId = "model-1",
        taskStatus = status,
        sequence = sequence,
    )
}
