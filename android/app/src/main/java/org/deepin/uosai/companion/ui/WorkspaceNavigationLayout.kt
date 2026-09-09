package org.deepin.uosai.companion.ui

enum class WorkspaceNavigationLayout {
    OverlayDrawer,
    SinglePane,
}

fun workspaceNavigationLayoutFor(availableWidthDp: Float): WorkspaceNavigationLayout =
    if (availableWidthDp >= 840f) {
        WorkspaceNavigationLayout.OverlayDrawer
    } else {
        WorkspaceNavigationLayout.SinglePane
    }
