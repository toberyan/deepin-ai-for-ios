package org.deepin.uosai.companion.app

fun reduceConversationStatus(
    conversations: List<CompanionConversation>,
    conversationId: String,
    status: TaskStatus?,
    sequence: Long,
): List<CompanionConversation> {
    val existing = conversations.firstOrNull { it.id == conversationId } ?: return conversations
    if (sequence <= existing.sequence) return conversations

    return conversations.map { conversation ->
        if (conversation.id == conversationId) conversation.copy(taskStatus = status, sequence = sequence) else conversation
    }.sortedWith(
        compareBy<CompanionConversation> { taskPriority(it.taskStatus) }
            .thenByDescending(CompanionConversation::updatedAt),
    )
}

private fun taskPriority(status: TaskStatus?): Int = when (status) {
    TaskStatus.Running -> 0
    TaskStatus.AwaitingApproval -> 1
    TaskStatus.Completed, TaskStatus.Failed, null -> 2
}
