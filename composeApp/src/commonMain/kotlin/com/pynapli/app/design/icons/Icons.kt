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
 *
 * Adapted from androidx.compose.material:material-icons-core (1.7.3): the Icons holder and the
 * materialIcon / materialPath builders, trimmed to the styles Pynapli uses.
 */

package com.pynapli.app.design.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.DefaultFillType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The Material icons the app uses, vendored so the app no longer depends on
 * material-icons-extended (pinned at 1.7.3 while Compose Multiplatform moved on, and the largest
 * single piece of the web bundle). Same shape as the library's Icons object, so call sites only
 * changed their imports. To add an icon, copy its file from the material-icons 1.7.3 sources into
 * the matching style package here.
 */
object Icons {
    object Filled

    /** The library's alias for [Filled]. */
    val Default = Filled

    object Outlined

    object AutoMirrored {
        object Filled
    }
}

/** Builds a 24dp Material icon; [autoMirror] flips it for right-to-left layouts. */
inline fun materialIcon(
    name: String,
    autoMirror: Boolean = false,
    block: ImageVector.Builder.() -> ImageVector.Builder,
): ImageVector =
    ImageVector
        .Builder(
            name = name,
            defaultWidth = 24f.dp,
            defaultHeight = 24f.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = autoMirror,
        ).block()
        .build()

/** Adds a solid-filled path with Material's defaults; tinting happens at draw time. */
inline fun ImageVector.Builder.materialPath(
    fillAlpha: Float = 1f,
    strokeAlpha: Float = 1f,
    pathFillType: PathFillType = DefaultFillType,
    pathBuilder: PathBuilder.() -> Unit,
) = path(
    fill = SolidColor(Color.Black),
    fillAlpha = fillAlpha,
    stroke = null,
    strokeAlpha = strokeAlpha,
    strokeLineWidth = 1f,
    strokeLineCap = StrokeCap.Butt,
    strokeLineJoin = StrokeJoin.Bevel,
    strokeLineMiter = 1f,
    pathFillType = pathFillType,
    pathBuilder = pathBuilder,
)
