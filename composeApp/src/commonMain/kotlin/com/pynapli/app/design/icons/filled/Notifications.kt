/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Vendored from androidx.compose.material:material-icons (1.7.3); only the package changed.
 */

@file:Suppress("ktlint:standard:backing-property-naming")

package com.pynapli.app.design.icons.filled

import androidx.compose.ui.graphics.vector.ImageVector
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.materialIcon
import com.pynapli.app.design.icons.materialPath

public val Icons.Filled.Notifications: ImageVector
    get() {
        if (_notifications != null) {
            return _notifications!!
        }
        _notifications =
            materialIcon(name = "Filled.Notifications") {
                materialPath {
                    moveTo(12.0f, 22.0f)
                    curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                    horizontalLineToRelative(-4.0f)
                    curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 2.0f, 2.0f)
                    close()
                    moveTo(18.0f, 16.0f)
                    verticalLineToRelative(-5.0f)
                    curveToRelative(0.0f, -3.07f, -1.64f, -5.64f, -4.5f, -6.32f)
                    lineTo(13.5f, 4.0f)
                    curveToRelative(0.0f, -0.83f, -0.67f, -1.5f, -1.5f, -1.5f)
                    reflectiveCurveToRelative(-1.5f, 0.67f, -1.5f, 1.5f)
                    verticalLineToRelative(0.68f)
                    curveTo(7.63f, 5.36f, 6.0f, 7.92f, 6.0f, 11.0f)
                    verticalLineToRelative(5.0f)
                    lineToRelative(-2.0f, 2.0f)
                    verticalLineToRelative(1.0f)
                    horizontalLineToRelative(16.0f)
                    verticalLineToRelative(-1.0f)
                    lineToRelative(-2.0f, -2.0f)
                    close()
                }
            }
        return _notifications!!
    }

private var _notifications: ImageVector? = null
