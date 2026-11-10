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

public val Icons.Filled.CloudUpload: ImageVector
    get() {
        if (_cloudUpload != null) {
            return _cloudUpload!!
        }
        _cloudUpload =
            materialIcon(name = "Filled.CloudUpload") {
                materialPath {
                    moveTo(19.35f, 10.04f)
                    curveTo(18.67f, 6.59f, 15.64f, 4.0f, 12.0f, 4.0f)
                    curveTo(9.11f, 4.0f, 6.6f, 5.64f, 5.35f, 8.04f)
                    curveTo(2.34f, 8.36f, 0.0f, 10.91f, 0.0f, 14.0f)
                    curveToRelative(0.0f, 3.31f, 2.69f, 6.0f, 6.0f, 6.0f)
                    horizontalLineToRelative(13.0f)
                    curveToRelative(2.76f, 0.0f, 5.0f, -2.24f, 5.0f, -5.0f)
                    curveToRelative(0.0f, -2.64f, -2.05f, -4.78f, -4.65f, -4.96f)
                    close()
                    moveTo(14.0f, 13.0f)
                    verticalLineToRelative(4.0f)
                    horizontalLineToRelative(-4.0f)
                    verticalLineToRelative(-4.0f)
                    horizontalLineTo(7.0f)
                    lineToRelative(5.0f, -5.0f)
                    lineToRelative(5.0f, 5.0f)
                    horizontalLineToRelative(-3.0f)
                    close()
                }
            }
        return _cloudUpload!!
    }

private var _cloudUpload: ImageVector? = null
