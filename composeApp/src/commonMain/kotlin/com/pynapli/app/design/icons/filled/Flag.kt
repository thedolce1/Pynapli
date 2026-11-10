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

public val Icons.Filled.Flag: ImageVector
    get() {
        if (_flag != null) {
            return _flag!!
        }
        _flag =
            materialIcon(name = "Filled.Flag") {
                materialPath {
                    moveTo(14.4f, 6.0f)
                    lineTo(14.0f, 4.0f)
                    horizontalLineTo(5.0f)
                    verticalLineToRelative(17.0f)
                    horizontalLineToRelative(2.0f)
                    verticalLineToRelative(-7.0f)
                    horizontalLineToRelative(5.6f)
                    lineToRelative(0.4f, 2.0f)
                    horizontalLineToRelative(7.0f)
                    verticalLineTo(6.0f)
                    close()
                }
            }
        return _flag!!
    }

private var _flag: ImageVector? = null
