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

package com.pynapli.app.design.icons.outlined

import androidx.compose.ui.graphics.vector.ImageVector
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.materialIcon
import com.pynapli.app.design.icons.materialPath

public val Icons.Outlined.ShoppingBag: ImageVector
    get() {
        if (_shoppingBag != null) {
            return _shoppingBag!!
        }
        _shoppingBag =
            materialIcon(name = "Outlined.ShoppingBag") {
                materialPath {
                    moveTo(18.0f, 6.0f)
                    horizontalLineToRelative(-2.0f)
                    curveToRelative(0.0f, -2.21f, -1.79f, -4.0f, -4.0f, -4.0f)
                    reflectiveCurveTo(8.0f, 3.79f, 8.0f, 6.0f)
                    horizontalLineTo(6.0f)
                    curveTo(4.9f, 6.0f, 4.0f, 6.9f, 4.0f, 8.0f)
                    verticalLineToRelative(12.0f)
                    curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                    horizontalLineToRelative(12.0f)
                    curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                    verticalLineTo(8.0f)
                    curveTo(20.0f, 6.9f, 19.1f, 6.0f, 18.0f, 6.0f)
                    close()
                    moveTo(12.0f, 4.0f)
                    curveToRelative(1.1f, 0.0f, 2.0f, 0.9f, 2.0f, 2.0f)
                    horizontalLineToRelative(-4.0f)
                    curveTo(10.0f, 4.9f, 10.9f, 4.0f, 12.0f, 4.0f)
                    close()
                    moveTo(18.0f, 20.0f)
                    horizontalLineTo(6.0f)
                    verticalLineTo(8.0f)
                    horizontalLineToRelative(2.0f)
                    verticalLineToRelative(2.0f)
                    curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                    reflectiveCurveToRelative(1.0f, -0.45f, 1.0f, -1.0f)
                    verticalLineTo(8.0f)
                    horizontalLineToRelative(4.0f)
                    verticalLineToRelative(2.0f)
                    curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                    reflectiveCurveToRelative(1.0f, -0.45f, 1.0f, -1.0f)
                    verticalLineTo(8.0f)
                    horizontalLineToRelative(2.0f)
                    verticalLineTo(20.0f)
                    close()
                }
            }
        return _shoppingBag!!
    }

private var _shoppingBag: ImageVector? = null
