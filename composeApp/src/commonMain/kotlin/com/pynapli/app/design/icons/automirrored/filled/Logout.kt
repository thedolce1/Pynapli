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

package com.pynapli.app.design.icons.automirrored.filled

import androidx.compose.ui.graphics.vector.ImageVector
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.materialIcon
import com.pynapli.app.design.icons.materialPath

public val Icons.AutoMirrored.Filled.Logout: ImageVector
    get() {
        if (_logout != null) {
            return _logout!!
        }
        _logout =
            materialIcon(name = "AutoMirrored.Filled.Logout", autoMirror = true) {
                materialPath {
                    moveTo(17.0f, 7.0f)
                    lineToRelative(-1.41f, 1.41f)
                    lineTo(18.17f, 11.0f)
                    horizontalLineTo(8.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(10.17f)
                    lineToRelative(-2.58f, 2.58f)
                    lineTo(17.0f, 17.0f)
                    lineToRelative(5.0f, -5.0f)
                    close()
                    moveTo(4.0f, 5.0f)
                    horizontalLineToRelative(8.0f)
                    verticalLineTo(3.0f)
                    horizontalLineTo(4.0f)
                    curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                    verticalLineToRelative(14.0f)
                    curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                    horizontalLineToRelative(8.0f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineTo(4.0f)
                    verticalLineTo(5.0f)
                    close()
                }
            }
        return _logout!!
    }

private var _logout: ImageVector? = null
