package org.deepin.uosai.companion.app

fun reduceConversationStatus(
    conversations: List<CompanionConversation>,
    conversationId: String,
    status: TaskStatus?,
    sequence: Long,
): List<CompanionConversation> {
    val existing = conversations.firstOrNull { it.id == conversationId } ?: return conversations
    if (sequence <= existing.sequence) return conversations

    return sortRecentConversations(conversations.map { conversation ->
        if (conversation.id == conversationId) conversation.copy(taskStatus = status, sequence = sequence) else conversation
    })
}

fun flattenRecentConversations(workspaces: List<CompanionWorkspace>): List<CompanionConversation> =
    sortRecentConversations(workspaces.flatMap(CompanionWorkspace::conversations))

fun addRecentConversation(
    conversations: List<CompanionConversation>,
    conversation: CompanionConversation,
): List<CompanionConversation> = sortRecentConversations(
    conversations.filterNot { it.id == conversation.id } + conversation,
)

private fun sortRecentConversations(conversations: List<CompanionConversation>): List<CompanionConversation> =
    conversations.sortedWith(
        compareBy<CompanionConversation> { taskPriority(it.taskStatus) }
            .thenByDescending(CompanionConversation::updatedAt),
    )

private fun taskPriority(status: TaskStatus?): Int = when (status) {
    TaskStatus.Running -> 0
    TaskStatus.AwaitingApproval -> 1
    TaskStatus.Completed, TaskStatus.Failed, null -> 2
}
