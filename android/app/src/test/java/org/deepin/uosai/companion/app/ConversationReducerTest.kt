package org.deepin.uosai.companion.app

import kotlin.test.assertEquals
import org.junit.Test

class ConversationReducerTest {
    @Test
    fun ignoresAnOutOfOrderTaskStatusDelta() {
        val original = listOf(conversation(id = "conversation-1", status = TaskStatus.Completed, sequence = 4))

        assertEquals(
            original,
            reduceConversationStatus(original, "conversation-1", TaskStatus.Running, sequence = 4),
        )
    }

    @Test
    fun appliesStatusOnlyToTheMatchingConversationAndSortsActiveTasksFirst() {
        val conversations = listOf(
            conversation(id = "completed", status = TaskStatus.Completed, updatedAt = 900, sequence = 1),
            conversation(id = "waiting", updatedAt = 20, sequence = 1),
            conversation(id = "running", updatedAt = 10, sequence = 1),
            conversation(id = "no-task", updatedAt = 1_000, sequence = 1),
        )

        val waiting = reduceConversationStatus(conversations, "waiting", TaskStatus.AwaitingApproval, sequence = 2)
        val reduced = reduceConversationStatus(waiting, "running", TaskStatus.Running, sequence = 2)

        assertEquals(listOf("running", "waiting", "no-task", "completed"), reduced.map(CompanionConversation::id))
        assertEquals(TaskStatus.AwaitingApproval, reduced.first { it.id == "waiting" }.taskStatus)
        assertEquals(TaskStatus.Completed, reduced.first { it.id == "completed" }.taskStatus)
    }

    @Test
    fun nullStatusClearsTheStatusChip() {
        val conversations = listOf(conversation(id = "conversation-1", status = TaskStatus.Failed, sequence = 4))

        val reduced = reduceConversationStatus(conversations, "conversation-1", status = null, sequence = 5)

        assertEquals(null, reduced.single().taskStatus)
        assertEquals(5, reduced.single().sequence)
    }

    private fun conversation(
        id: String,
        status: TaskStatus? = null,
        updatedAt: Long = 0,
        sequence: Long = 0,
    ) = CompanionConversation(
        id = id,
        title = id,
        workspaceId = "workspace-1",
        updatedAt = updatedAt,
        assistantId = "uos-ai-claw",
        modelId = "model-1",
        taskStatus = status,
        sequence = sequence,
    )
}
