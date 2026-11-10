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

public val Icons.Filled.Lock: ImageVector
    get() {
        if (_lock != null) {
            return _lock!!
        }
        _lock =
            materialIcon(name = "Filled.Lock") {
                materialPath {
                    moveTo(18.0f, 8.0f)
                    horizontalLineToRelative(-1.0f)
                    lineTo(17.0f, 6.0f)
                    curveToRelative(0.0f, -2.76f, -2.24f, -5.0f, -5.0f, -5.0f)
                    reflectiveCurveTo(7.0f, 3.24f, 7.0f, 6.0f)
                    verticalLineToRelative(2.0f)
                    lineTo(6.0f, 8.0f)
                    curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                    verticalLineToRelative(10.0f)
                    curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                    horizontalLineToRelative(12.0f)
                    curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                    lineTo(20.0f, 10.0f)
                    curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                    close()
                    moveTo(12.0f, 17.0f)
                    curveToRelative(-1.1f, 0.0f, -2.0f, -0.9f, -2.0f, -2.0f)
                    reflectiveCurveToRelative(0.9f, -2.0f, 2.0f, -2.0f)
                    reflectiveCurveToRelative(2.0f, 0.9f, 2.0f, 2.0f)
                    reflectiveCurveToRelative(-0.9f, 2.0f, -2.0f, 2.0f)
                    close()
                    moveTo(15.1f, 8.0f)
                    lineTo(8.9f, 8.0f)
                    lineTo(8.9f, 6.0f)
                    curveToRelative(0.0f, -1.71f, 1.39f, -3.1f, 3.1f, -3.1f)
                    curveToRelative(1.71f, 0.0f, 3.1f, 1.39f, 3.1f, 3.1f)
                    verticalLineToRelative(2.0f)
                    close()
                }
            }
        return _lock!!
    }

private var _lock: ImageVector? = null
