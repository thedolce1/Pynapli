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

public val Icons.Filled.StarBorder: ImageVector
    get() {
        if (_starBorder != null) {
            return _starBorder!!
        }
        _starBorder =
            materialIcon(name = "Filled.StarBorder") {
                materialPath {
                    moveTo(22.0f, 9.24f)
                    lineToRelative(-7.19f, -0.62f)
                    lineTo(12.0f, 2.0f)
                    lineTo(9.19f, 8.63f)
                    lineTo(2.0f, 9.24f)
                    lineToRelative(5.46f, 4.73f)
                    lineTo(5.82f, 21.0f)
                    lineTo(12.0f, 17.27f)
                    lineTo(18.18f, 21.0f)
                    lineToRelative(-1.63f, -7.03f)
                    lineTo(22.0f, 9.24f)
                    close()
                    moveTo(12.0f, 15.4f)
                    lineToRelative(-3.76f, 2.27f)
                    lineToRelative(1.0f, -4.28f)
                    lineToRelative(-3.32f, -2.88f)
                    lineToRelative(4.38f, -0.38f)
                    lineTo(12.0f, 6.1f)
                    lineToRelative(1.71f, 4.04f)
                    lineToRelative(4.38f, 0.38f)
                    lineToRelative(-3.32f, 2.88f)
                    lineToRelative(1.0f, 4.28f)
                    lineTo(12.0f, 15.4f)
                    close()
                }
            }
        return _starBorder!!
    }

private var _starBorder: ImageVector? = null
