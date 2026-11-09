package com.mshemur.lingqfix

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Material icons that are not in material-icons-core (the extended set would add ~10 MB to the APK). */
object AppIcons {
    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
        .build()

    val Chat: ImageVector by lazy {
        icon(
            "Chat",
            "M20,2H4c-1.1,0 -1.99,0.9 -1.99,2L2,22l4,-4h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM6,9h12v2H6V9z" +
                "M14,14H6v-2h8v2zM18,8H6V6h12v2z",
        )
    }

    val Bookmark: ImageVector by lazy {
        icon("Bookmark", "M17,3H7c-1.1,0 -1.99,0.9 -1.99,2L5,21l7,-3 7,3V5c0,-1.1 -0.9,-2 -2,-2z")
    }

    val BookmarkBorder: ImageVector by lazy {
        icon("BookmarkBorder", "M17,3H7c-1.1,0 -1.99,0.9 -1.99,2L5,21l7,-3 7,3V5c0,-1.1 -0.9,-2 -2,-2zM17,18l-5,-2.18L7,18V5h10v13z")
    }

    val UnfoldMore: ImageVector by lazy {
        icon("UnfoldMore", "M12,5.83L15.17,9l1.41,-1.41L12,3 7.41,7.59 8.83,9 12,5.83zM12,18.17L8.83,15l-1.41,1.41L12,21l4.59,-4.59L15.17,15 12,18.17z")
    }

    val UnfoldLess: ImageVector by lazy {
        icon("UnfoldLess", "M7.41,18.59L8.83,20 12,16.83 15.17,20l1.41,-1.41L12,14l-4.59,4.59zM16.59,5.41L15.17,4 12,7.17 8.83,4 7.41,5.41 12,10l4.59,-4.59z")
    }

    val PushPin: ImageVector by lazy {
        icon(
            "PushPin",
            "M16,9V4l1,0c0.55,0 1,-0.45 1,-1v0c0,-0.55 -0.45,-1 -1,-1H7C6.45,2 6,2.45 6,3v0c0,0.55 0.45,1 1,1l1,0v5" +
                "c0,1.66 -1.34,3 -3,3h0v2h5.97v7l1,1l1,-1v-7H19v-2h0C17.34,12 16,10.66 16,9z",
        )
    }

    val BarChart: ImageVector by lazy {
        icon("BarChart", "M5,9.2h3V19H5V9.2zM10.6,5h2.8v14h-2.8V5zM16.2,13H19v6h-2.8V13z")
    }

    val ContentPaste: ImageVector by lazy {
        icon(
            "ContentPaste",
            "M19,2h-4.18C14.4,0.84 13.3,0 12,0c-1.3,0 -2.4,0.84 -2.82,2L5,2c-1.1,0 -2,0.9 -2,2v16c0,1.1 0.9,2 2,2h14" +
                "c1.1,0 2,-0.9 2,-2L21,4c0,-1.1 -0.9,-2 -2,-2zM12,2c0.55,0 1,0.45 1,1s-0.45,1 -1,1 -1,-0.45 -1,-1 0.45,-1 1,-1z" +
                "M19,20L5,20L5,4h2v3h10L17,4h2v16z",
        )
    }

    val Description: ImageVector by lazy {
        icon(
            "Description",
            "M14,2L6,2c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2L18,22c1.1,0 2,-0.9 2,-2L20,8l-6,-6z" +
                "M16,18L8,18v-2h8v2zM16,14L8,14v-2h8v2zM13,9L13,3.5L18.5,9L13,9z",
        )
    }

    val Image: ImageVector by lazy {
        icon("Image", "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2zM8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z")
    }

    val PhotoCamera: ImageVector by lazy {
        icon(
            "PhotoCamera",
            "M12,12m-3.2,0a3.2,3.2 0,1 1,6.4 0a3.2,3.2 0,1 1,-6.4 0M9,2L7.17,4L4,4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16" +
                "c1.1,0 2,-0.9 2,-2L22,6c0,-1.1 -0.9,-2 -2,-2h-3.17L15,2L9,2zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5z",
        )
    }

    val AutoFix: ImageVector by lazy {
        icon(
            "AutoFix",
            "M7.5,5.6L10,7 8.6,4.5 10,2 7.5,3.4 5,2l1.4,2.5L5,7zM19.5,15.4L17,14l1.4,2.5L17,19l2.5,-1.4L22,19l-1.4,-2.5L22,14z" +
                "M22,2l-2.5,1.4L17,2l1.4,2.5L17,7l2.5,-1.4L22,7l-1.4,-2.5zM14.37,7.29c-0.39,-0.39 -1.02,-0.39 -1.41,0L1.29,18.96" +
                "c-0.39,0.39 -0.39,1.02 0,1.41l2.34,2.34c0.39,0.39 1.02,0.39 1.41,0L16.7,11.05c0.39,-0.39 0.39,-1.02 0,-1.41" +
                "l-2.33,-2.35zM13.34,12.78l-2.12,-2.12 2.44,-2.44 2.12,2.12 -2.44,2.44z",
        )
    }

    val Cleaning: ImageVector by lazy {
        icon(
            "Cleaning",
            "M16,11h-1V3c0,-1.1 -0.9,-2 -2,-2h-2C9.9,1 9,1.9 9,3v8H8c-2.76,0 -5,2.24 -5,5v7h18v-7C21,13.24 18.76,11 16,11z" +
                "M19,21h-2v-3c0,-0.55 -0.45,-1 -1,-1s-1,0.45 -1,1v3h-2v-3c0,-0.55 -0.45,-1 -1,-1s-1,0.45 -1,1v3H9v-3" +
                "c0,-0.55 -0.45,-1 -1,-1s-1,0.45 -1,1v3H5v-5c0,-1.65 1.35,-3 3,-3h8c1.65,0 3,1.35 3,3V21z",
        )
    }

    val Translate: ImageVector by lazy {
        icon(
            "Translate",
            "M12.87,15.07l-2.54,-2.51 0.03,-0.03c1.74,-1.94 2.98,-4.17 3.71,-6.53L17,6L17,4h-7L10,2L8,2v2L1,4v1.99h11.17" +
                "C11.5,7.92 10.44,9.75 9,11.35 8.07,10.32 7.3,9.19 6.69,8h-2c0.73,1.63 1.73,3.17 2.98,4.56l-5.09,5.02L4,19" +
                "l5,-5 3.11,3.11 0.76,-2.04zM18.5,10h-2L12,22h2l1.12,-3h4.75L21,22h2l-4.5,-12zM15.88,17l1.62,-4.33" +
                "L19.12,17h-3.24z",
        )
    }

    val Pause: ImageVector by lazy { icon("Pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z") }
    val Repeat: ImageVector by lazy { icon("Repeat", "M7,7h10v3l4,-4 -4,-4v3H5v6h2V7zM17,17H7v-3l-4,4 4,4v-3h12v-6h-2v4z") }
    val SkipPrevious: ImageVector by lazy { icon("SkipPrevious", "M6,6h2v12H6zM9.5,12l8.5,6V6z") }
    val SkipNext: ImageVector by lazy { icon("SkipNext", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z") }
    val Replay: ImageVector by lazy {
        icon("Replay", "M12,5V1L7,6l5,5V7c3.31,0 6,2.69 6,6s-2.69,6 -6,6 -6,-2.69 -6,-6H4c0,4.42 3.58,8 8,8s8,-3.58 8,-8 -3.58,-8 -8,-8z")
    }
    val Forward: ImageVector by lazy {
        icon("Forward", "M12,5V1l5,5 -5,5V7c-3.31,0 -6,2.69 -6,6s2.69,6 6,6 6,-2.69 6,-6h2c0,4.42 -3.58,8 -8,8s-8,-3.58 -8,-8 3.58,-8 8,-8z")
    }
    val Headphones: ImageVector by lazy {
        icon("Headphones", "M12,1c-4.97,0 -9,4.03 -9,9v7c0,1.66 1.34,3 3,3h3v-8H5v-2c0,-3.87 3.13,-7 7,-7s7,3.13 7,7v2h-4v8h3c1.66,0 3,-1.34 3,-3v-7c0,-4.97 -4.03,-9 -9,-9z")
    }
}
