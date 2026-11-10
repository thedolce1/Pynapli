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

public val Icons.Filled.Check: ImageVector
    get() {
        if (_check != null) {
            return _check!!
        }
        _check =
            materialIcon(name = "Filled.Check") {
                materialPath {
                    moveTo(9.0f, 16.17f)
                    lineTo(4.83f, 12.0f)
                    lineToRelative(-1.42f, 1.41f)
                    lineTo(9.0f, 19.0f)
                    lineTo(21.0f, 7.0f)
                    lineToRelative(-1.41f, -1.41f)
                    close()
                }
            }
        return _check!!
    }

private var _check: ImageVector? = null
