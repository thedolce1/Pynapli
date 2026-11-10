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

public val Icons.Filled.Smartphone: ImageVector
    get() {
        if (_smartphone != null) {
            return _smartphone!!
        }
        _smartphone =
            materialIcon(name = "Filled.Smartphone") {
                materialPath {
                    moveTo(17.0f, 1.01f)
                    lineTo(7.0f, 1.0f)
                    curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                    verticalLineToRelative(18.0f)
                    curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                    horizontalLineToRelative(10.0f)
                    curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                    verticalLineTo(3.0f)
                    curveToRelative(0.0f, -1.1f, -0.9f, -1.99f, -2.0f, -1.99f)
                    close()
                    moveTo(17.0f, 19.0f)
                    horizontalLineTo(7.0f)
                    verticalLineTo(5.0f)
                    horizontalLineToRelative(10.0f)
                    verticalLineToRelative(14.0f)
                    close()
                }
            }
        return _smartphone!!
    }

private var _smartphone: ImageVector? = null
