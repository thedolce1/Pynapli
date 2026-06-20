package com.pynapli.app.design

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Style
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.outlined.ChatBubble as ChatBubbleOutlined
import androidx.compose.material.icons.outlined.GridView as GridViewOutlined
import androidx.compose.material.icons.outlined.LocalFireDepartment as LocalFireDepartmentOutlined
import androidx.compose.material.icons.outlined.ShoppingBag as ShoppingBagOutlined
import androidx.compose.material.icons.outlined.Style as StyleOutlined

/**
 * One [unselected] / [selected] vector pair per bottom-nav tab. The nav renders the active tab with
 * the heavier [selected] glyph and the rest with the lighter [unselected] one — the standard premium
 * pattern — so selection reads through shape as well as the gold tint.
 */
data class PynapliTabIcon(
    val unselected: ImageVector,
    val selected: ImageVector,
)

/**
 * The single source for the five bottom-nav tab icons. **This is the only file to touch when a
 * designer delivers a bespoke brand icon set:** convert each SVG to a vector and replace the values
 * below (e.g. `vectorResource(Res.drawable.tab_grid_selected)`) — `AppNavigation` and every call site
 * stay unchanged. Until then these are the stock Material glyphs (outline = inactive, filled = active).
 *
 * Brand direction when that set is commissioned (founder, 2026-06-20): anchor on a "linked / connected"
 * motif + the pineapple-gold active tint rather than a literal pineapple on every tab; commission the
 * launcher icon first. See [[project_design_cohesion_initiative]].
 */
object PynapliTabIcons {
    val Grid = PynapliTabIcon(unselected = Icons.Outlined.GridViewOutlined, selected = Icons.Filled.GridView)
    val Swipe = PynapliTabIcon(unselected = Icons.Outlined.StyleOutlined, selected = Icons.Filled.Style)
    val Activity =
        PynapliTabIcon(
            unselected = Icons.Outlined.LocalFireDepartmentOutlined,
            selected = Icons.Filled.LocalFireDepartment,
        )
    val Messages = PynapliTabIcon(unselected = Icons.Outlined.ChatBubbleOutlined, selected = Icons.Filled.ChatBubble)
    val Store = PynapliTabIcon(unselected = Icons.Outlined.ShoppingBagOutlined, selected = Icons.Filled.ShoppingBag)
}
