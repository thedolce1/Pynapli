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

public val Icons.Filled.Link: ImageVector
    get() {
        if (_link != null) {
            return _link!!
        }
        _link =
            materialIcon(name = "Filled.Link") {
                materialPath {
                    moveTo(3.9f, 12.0f)
                    curveToRelative(0.0f, -1.71f, 1.39f, -3.1f, 3.1f, -3.1f)
                    horizontalLineToRelative(4.0f)
                    lineTo(11.0f, 7.0f)
                    lineTo(7.0f, 7.0f)
                    curveToRelative(-2.76f, 0.0f, -5.0f, 2.24f, -5.0f, 5.0f)
                    reflectiveCurveToRelative(2.24f, 5.0f, 5.0f, 5.0f)
                    horizontalLineToRelative(4.0f)
                    verticalLineToRelative(-1.9f)
                    lineTo(7.0f, 15.1f)
                    curveToRelative(-1.71f, 0.0f, -3.1f, -1.39f, -3.1f, -3.1f)
                    close()
                    moveTo(8.0f, 13.0f)
                    horizontalLineToRelative(8.0f)
                    verticalLineToRelative(-2.0f)
                    lineTo(8.0f, 11.0f)
                    verticalLineToRelative(2.0f)
                    close()
                    moveTo(17.0f, 7.0f)
                    horizontalLineToRelative(-4.0f)
                    verticalLineToRelative(1.9f)
                    horizontalLineToRelative(4.0f)
                    curveToRelative(1.71f, 0.0f, 3.1f, 1.39f, 3.1f, 3.1f)
                    reflectiveCurveToRelative(-1.39f, 3.1f, -3.1f, 3.1f)
                    horizontalLineToRelative(-4.0f)
                    lineTo(13.0f, 17.0f)
                    horizontalLineToRelative(4.0f)
                    curveToRelative(2.76f, 0.0f, 5.0f, -2.24f, 5.0f, -5.0f)
                    reflectiveCurveToRelative(-2.24f, -5.0f, -5.0f, -5.0f)
                    close()
                }
            }
        return _link!!
    }

private var _link: ImageVector? = null
