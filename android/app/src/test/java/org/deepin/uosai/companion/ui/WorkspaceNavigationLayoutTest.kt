package org.deepin.uosai.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceNavigationLayoutTest {
    @Test
    fun usesAnOverlayDrawerOnTabletWidths() {
        assertEquals(
            WorkspaceNavigationLayout.OverlayDrawer,
            workspaceNavigationLayoutFor(840f),
        )
    }

    @Test
    fun retainsTheSinglePaneFlowBelowTabletWidth() {
        assertEquals(
            WorkspaceNavigationLayout.SinglePane,
            workspaceNavigationLayoutFor(839f),
        )
    }
}
