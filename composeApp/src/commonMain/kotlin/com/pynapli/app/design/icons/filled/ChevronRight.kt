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

public val Icons.Filled.ChevronRight: ImageVector
    get() {
        if (_chevronRight != null) {
            return _chevronRight!!
        }
        _chevronRight =
            materialIcon(name = "Filled.ChevronRight") {
                materialPath {
                    moveTo(10.0f, 6.0f)
                    lineTo(8.59f, 7.41f)
                    lineTo(13.17f, 12.0f)
                    lineToRelative(-4.58f, 4.59f)
                    lineTo(10.0f, 18.0f)
                    lineToRelative(6.0f, -6.0f)
                    close()
                }
            }
        return _chevronRight!!
    }

private var _chevronRight: ImageVector? = null
