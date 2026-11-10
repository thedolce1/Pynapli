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

public val Icons.Filled.VerifiedUser: ImageVector
    get() {
        if (_verifiedUser != null) {
            return _verifiedUser!!
        }
        _verifiedUser =
            materialIcon(name = "Filled.VerifiedUser") {
                materialPath {
                    moveTo(12.0f, 1.0f)
                    lineTo(3.0f, 5.0f)
                    verticalLineToRelative(6.0f)
                    curveToRelative(0.0f, 5.55f, 3.84f, 10.74f, 9.0f, 12.0f)
                    curveToRelative(5.16f, -1.26f, 9.0f, -6.45f, 9.0f, -12.0f)
                    lineTo(21.0f, 5.0f)
                    lineToRelative(-9.0f, -4.0f)
                    close()
                    moveTo(10.0f, 17.0f)
                    lineToRelative(-4.0f, -4.0f)
                    lineToRelative(1.41f, -1.41f)
                    lineTo(10.0f, 14.17f)
                    lineToRelative(6.59f, -6.59f)
                    lineTo(18.0f, 9.0f)
                    lineToRelative(-8.0f, 8.0f)
                    close()
                }
            }
        return _verifiedUser!!
    }

private var _verifiedUser: ImageVector? = null
