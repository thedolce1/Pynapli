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

public val Icons.Filled.Download: ImageVector
    get() {
        if (_download != null) {
            return _download!!
        }
        _download =
            materialIcon(name = "Filled.Download") {
                materialPath {
                    moveTo(5.0f, 20.0f)
                    horizontalLineToRelative(14.0f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineTo(5.0f)
                    verticalLineTo(20.0f)
                    close()
                    moveTo(19.0f, 9.0f)
                    horizontalLineToRelative(-4.0f)
                    verticalLineTo(3.0f)
                    horizontalLineTo(9.0f)
                    verticalLineToRelative(6.0f)
                    horizontalLineTo(5.0f)
                    lineToRelative(7.0f, 7.0f)
                    lineTo(19.0f, 9.0f)
                    close()
                }
            }
        return _download!!
    }

private var _download: ImageVector? = null
