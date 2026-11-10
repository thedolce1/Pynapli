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

public val Icons.Filled.CardMembership: ImageVector
    get() {
        if (_cardMembership != null) {
            return _cardMembership!!
        }
        _cardMembership =
            materialIcon(name = "Filled.CardMembership") {
                materialPath {
                    moveTo(20.0f, 2.0f)
                    lineTo(4.0f, 2.0f)
                    curveToRelative(-1.11f, 0.0f, -2.0f, 0.89f, -2.0f, 2.0f)
                    verticalLineToRelative(11.0f)
                    curveToRelative(0.0f, 1.11f, 0.89f, 2.0f, 2.0f, 2.0f)
                    horizontalLineToRelative(4.0f)
                    verticalLineToRelative(5.0f)
                    lineToRelative(4.0f, -2.0f)
                    lineToRelative(4.0f, 2.0f)
                    verticalLineToRelative(-5.0f)
                    horizontalLineToRelative(4.0f)
                    curveToRelative(1.11f, 0.0f, 2.0f, -0.89f, 2.0f, -2.0f)
                    lineTo(22.0f, 4.0f)
                    curveToRelative(0.0f, -1.11f, -0.89f, -2.0f, -2.0f, -2.0f)
                    close()
                    moveTo(20.0f, 15.0f)
                    lineTo(4.0f, 15.0f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineToRelative(16.0f)
                    verticalLineToRelative(2.0f)
                    close()
                    moveTo(20.0f, 10.0f)
                    lineTo(4.0f, 10.0f)
                    lineTo(4.0f, 4.0f)
                    horizontalLineToRelative(16.0f)
                    verticalLineToRelative(6.0f)
                    close()
                }
            }
        return _cardMembership!!
    }

private var _cardMembership: ImageVector? = null
