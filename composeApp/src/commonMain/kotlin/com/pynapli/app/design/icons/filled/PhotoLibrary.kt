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

public val Icons.Filled.PhotoLibrary: ImageVector
    get() {
        if (_photoLibrary != null) {
            return _photoLibrary!!
        }
        _photoLibrary =
            materialIcon(name = "Filled.PhotoLibrary") {
                materialPath {
                    moveTo(22.0f, 16.0f)
                    lineTo(22.0f, 4.0f)
                    curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                    lineTo(8.0f, 2.0f)
                    curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                    verticalLineToRelative(12.0f)
                    curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                    horizontalLineToRelative(12.0f)
                    curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                    close()
                    moveTo(11.0f, 12.0f)
                    lineToRelative(2.03f, 2.71f)
                    lineTo(16.0f, 11.0f)
                    lineToRelative(4.0f, 5.0f)
                    lineTo(8.0f, 16.0f)
                    lineToRelative(3.0f, -4.0f)
                    close()
                    moveTo(2.0f, 6.0f)
                    verticalLineToRelative(14.0f)
                    curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                    horizontalLineToRelative(14.0f)
                    verticalLineToRelative(-2.0f)
                    lineTo(4.0f, 20.0f)
                    lineTo(4.0f, 6.0f)
                    lineTo(2.0f, 6.0f)
                    close()
                }
            }
        return _photoLibrary!!
    }

private var _photoLibrary: ImageVector? = null
