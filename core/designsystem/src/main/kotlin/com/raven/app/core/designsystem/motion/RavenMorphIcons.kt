package com.raven.app.core.designsystem.motion

/**
 * Canonical `24f x 24f` stroke-based [RavenMorphGlyph] catalog matching Material Rounded `2dp`
 * stroke geometry for Raven's top-level navigation, media player controls, and interactive actions.
 */
object RavenMorphIcons {

    // -------------------------------------------------------------------------
    // Top Navigation (Outline <-> Active)
    // -------------------------------------------------------------------------

    val HomeOutline: RavenMorphGlyph = RavenMorphGlyph(
        id = "home_outline",
        strokes = listOf(
            MorphStroke.build(closed = true, fill = false) {
                moveTo(4f, 10.5f)
                lineTo(11.3f, 4.1f)
                quadTo(12f, 3.5f, 12.7f, 4.1f)
                lineTo(20f, 10.5f)
                lineTo(20f, 19f)
                quadTo(20f, 20f, 19f, 20f)
                lineTo(5f, 20f)
                quadTo(4f, 20f, 4f, 19f)
                close()
            },
            MorphStroke.build(closed = false, fill = false) {
                moveTo(9.5f, 20f)
                lineTo(9.5f, 14.5f)
                quadTo(9.5f, 13.5f, 10.5f, 13.5f)
                lineTo(13.5f, 13.5f)
                quadTo(14.5f, 13.5f, 14.5f, 14.5f)
                lineTo(14.5f, 20f)
            },
        ),
    )

    val HomeActive: RavenMorphGlyph = RavenMorphGlyph(
        id = "home_active",
        strokes = listOf(
            MorphStroke.build(closed = true, fill = true) {
                moveTo(3.5f, 10.2f)
                lineTo(11.3f, 3.4f)
                quadTo(12f, 2.8f, 12.7f, 3.4f)
                lineTo(20.5f, 10.2f)
                lineTo(20.5f, 19.3f)
                quadTo(20.5f, 20.5f, 19.3f, 20.5f)
                lineTo(14.5f, 20.5f)
                lineTo(14.5f, 14.5f)
                quadTo(14.5f, 13.5f, 13.5f, 13.5f)
                lineTo(10.5f, 13.5f)
                quadTo(9.5f, 13.5f, 9.5f, 14.5f)
                lineTo(9.5f, 20.5f)
                lineTo(4.7f, 20.5f)
                quadTo(3.5f, 20.5f, 3.5f, 19.3f)
                close()
            },
        ),
    )

    val ExploreOutline: RavenMorphGlyph = RavenMorphGlyph(
        id = "explore_outline",
        strokes = listOf(
            MorphStroke.circle(12f, 12f, 8.75f, fill = false),
            MorphStroke.build(closed = true, fill = false) {
                moveTo(15.6f, 8.4f)
                lineTo(13.6f, 13.6f)
                lineTo(8.4f, 15.6f)
                lineTo(10.4f, 10.4f)
                close()
            },
        ),
    )

    val ExploreActive: RavenMorphGlyph = RavenMorphGlyph(
        id = "explore_active",
        strokes = listOf(
            MorphStroke.circle(12f, 12f, 8.75f, fill = false),
            MorphStroke.build(closed = true, fill = true) {
                moveTo(16.2f, 7.8f)
                lineTo(13.9f, 13.9f)
                lineTo(7.8f, 16.2f)
                lineTo(10.1f, 10.1f)
                close()
            },
        ),
    )

    val SearchOutline: RavenMorphGlyph = RavenMorphGlyph(
        id = "search_outline",
        strokes = listOf(
            MorphStroke.circle(10.75f, 10.75f, 6.25f, fill = false),
            MorphStroke.line(15.4f, 15.4f, 19.75f, 19.75f),
        ),
    )

    val SearchActive: RavenMorphGlyph = RavenMorphGlyph(
        id = "search_active",
        strokes = listOf(
            MorphStroke.circle(10.75f, 10.75f, 6.75f, fill = true),
            MorphStroke.line(15.4f, 15.4f, 20f, 20f),
        ),
    )

    val LibraryOutline: RavenMorphGlyph = RavenMorphGlyph(
        id = "library_outline",
        strokes = listOf(
            MorphStroke.roundRect(7.5f, 4f, 20f, 16.5f, rx = 2.2f, fill = false),
            MorphStroke.build(closed = false, fill = false) {
                moveTo(4f, 8.5f)
                lineTo(4f, 18.2f)
                quadTo(4f, 20f, 5.8f, 20f)
                lineTo(15.5f, 20f)
            },
        ),
    )

    val LibraryActive: RavenMorphGlyph = RavenMorphGlyph(
        id = "library_active",
        strokes = listOf(
            MorphStroke.roundRect(7f, 3.5f, 20.5f, 17f, rx = 2.4f, fill = true),
            MorphStroke.build(closed = false, fill = false) {
                moveTo(4f, 8.5f)
                lineTo(4f, 18.2f)
                quadTo(4f, 20f, 5.8f, 20f)
                lineTo(15.5f, 20f)
            },
        ),
    )

    val ProfileOutline: RavenMorphGlyph = RavenMorphGlyph(
        id = "profile_outline",
        strokes = listOf(
            MorphStroke.circle(12f, 8f, 3.5f, fill = false),
            MorphStroke.build(closed = true, fill = false) {
                moveTo(5.2f, 19f)
                cubicTo(5.2f, 15.8f, 8.2f, 14f, 12f, 14f)
                cubicTo(15.8f, 14f, 18.8f, 15.8f, 18.8f, 19f)
                quadTo(18.8f, 19.8f, 18f, 19.8f)
                lineTo(6f, 19.8f)
                quadTo(5.2f, 19.8f, 5.2f, 19f)
                close()
            },
        ),
    )

    val ProfileActive: RavenMorphGlyph = RavenMorphGlyph(
        id = "profile_active",
        strokes = listOf(
            MorphStroke.circle(12f, 7.8f, 4f, fill = true),
            MorphStroke.build(closed = true, fill = true) {
                moveTo(4.7f, 19.1f)
                cubicTo(4.7f, 15.4f, 7.9f, 13.5f, 12f, 13.5f)
                cubicTo(16.1f, 13.5f, 19.3f, 15.4f, 19.3f, 19.1f)
                quadTo(19.3f, 20.2f, 18.2f, 20.2f)
                lineTo(5.8f, 20.2f)
                quadTo(4.7f, 20.2f, 4.7f, 19.1f)
                close()
            },
        ),
    )

    // -------------------------------------------------------------------------
    // Media / Player Controls
    // -------------------------------------------------------------------------

    val Play: RavenMorphGlyph = RavenMorphGlyph(
        id = "play",
        strokes = listOf(
            MorphStroke.build(closed = true, fill = true) {
                moveTo(7.5f, 6.2f)
                quadTo(7.5f, 4.9f, 8.7f, 5.6f)
                lineTo(18.6f, 11.3f)
                quadTo(19.7f, 12f, 18.6f, 12.7f)
                lineTo(8.7f, 18.4f)
                quadTo(7.5f, 19.1f, 7.5f, 17.8f)
                close()
            },
        ),
    )

    val Pause: RavenMorphGlyph = RavenMorphGlyph(
        id = "pause",
        strokes = listOf(
            MorphStroke.roundRect(6.5f, 5.5f, 10.2f, 18.5f, rx = 1.2f, fill = true),
            MorphStroke.roundRect(13.8f, 5.5f, 17.5f, 18.5f, rx = 1.2f, fill = true),
        ),
    )

    val Replay: RavenMorphGlyph = RavenMorphGlyph(
        id = "replay",
        strokes = listOf(
            MorphStroke.arc(
                cx = 12f,
                cy = 13f,
                r = 6.5f,
                startAngleDeg = -90f,
                sweepAngleDeg = -270f,
                closed = false,
                fill = false,
            ),
            MorphStroke.polyline(
                14.5f to 4f,
                11.8f to 6.5f,
                14.5f to 9f,
                closed = false,
                fill = false,
            ),
        ),
    )

    val FullscreenEnter: RavenMorphGlyph = RavenMorphGlyph(
        id = "fullscreen_enter",
        strokes = listOf(
            MorphStroke.polyline(4.5f to 9f, 4.5f to 4.5f, 9f to 4.5f),
            MorphStroke.polyline(15f to 4.5f, 19.5f to 4.5f, 19.5f to 9f),
            MorphStroke.polyline(19.5f to 15f, 19.5f to 19.5f, 15f to 19.5f),
            MorphStroke.polyline(9f to 19.5f, 4.5f to 19.5f, 4.5f to 15f),
        ),
    )

    val FullscreenExit: RavenMorphGlyph = RavenMorphGlyph(
        id = "fullscreen_exit",
        strokes = listOf(
            MorphStroke.polyline(4.5f to 9f, 9f to 9f, 9f to 4.5f),
            MorphStroke.polyline(15f to 4.5f, 15f to 9f, 19.5f to 9f),
            MorphStroke.polyline(19.5f to 15f, 15f to 15f, 15f to 19.5f),
            MorphStroke.polyline(9f to 19.5f, 9f to 15f, 4.5f to 15f),
        ),
    )

    val LockOpen: RavenMorphGlyph = RavenMorphGlyph(
        id = "lock_open",
        strokes = listOf(
            MorphStroke.roundRect(5f, 10.5f, 19f, 20f, rx = 2.2f, fill = false),
            MorphStroke.build(closed = false, fill = false) {
                moveTo(8.5f, 10.5f)
                lineTo(8.5f, 7.5f)
                cubicTo(8.5f, 5.2f, 10.1f, 3.8f, 12f, 3.8f)
                cubicTo(13.6f, 3.8f, 14.9f, 4.8f, 15.3f, 6.4f)
            },
            MorphStroke.line(12f, 14.2f, 12f, 16.5f),
        ),
    )

    val LockClosed: RavenMorphGlyph = RavenMorphGlyph(
        id = "lock_closed",
        strokes = listOf(
            MorphStroke.roundRect(5f, 10.5f, 19f, 20f, rx = 2.2f, fill = false),
            MorphStroke.build(closed = false, fill = false) {
                moveTo(8.5f, 10.5f)
                lineTo(8.5f, 7.5f)
                cubicTo(8.5f, 5.2f, 10.1f, 3.8f, 12f, 3.8f)
                cubicTo(13.9f, 3.8f, 15.5f, 5.2f, 15.5f, 7.5f)
                lineTo(15.5f, 10.5f)
            },
            MorphStroke.line(12f, 14.2f, 12f, 16.5f),
        ),
    )

    val VolumeOn: RavenMorphGlyph = RavenMorphGlyph(
        id = "volume_on",
        strokes = listOf(
            MorphStroke.build(closed = true, fill = false) {
                moveTo(4f, 9.5f)
                lineTo(8f, 9.5f)
                lineTo(12.5f, 5.5f)
                lineTo(12.5f, 18.5f)
                lineTo(8f, 14.5f)
                lineTo(4f, 14.5f)
                close()
            },
            MorphStroke.arc(13.2f, 12f, 3.4f, -45f, 90f),
            MorphStroke.arc(13.2f, 12f, 6.4f, -48f, 96f),
        ),
    )

    val VolumeMuted: RavenMorphGlyph = RavenMorphGlyph(
        id = "volume_muted",
        strokes = listOf(
            MorphStroke.build(closed = true, fill = false) {
                moveTo(4f, 9.5f)
                lineTo(8f, 9.5f)
                lineTo(12.5f, 5.5f)
                lineTo(12.5f, 18.5f)
                lineTo(8f, 14.5f)
                lineTo(4f, 14.5f)
                close()
            },
            MorphStroke.line(16f, 9.5f, 20.5f, 14.5f),
            MorphStroke.line(20.5f, 9.5f, 16f, 14.5f),
        ),
    )

    // -------------------------------------------------------------------------
    // Actions / Toggles
    // -------------------------------------------------------------------------

    val Plus: RavenMorphGlyph = RavenMorphGlyph(
        id = "plus",
        strokes = listOf(
            MorphStroke.line(5f, 12f, 19f, 12f),
            MorphStroke.line(12f, 5f, 12f, 19f),
        ),
    )

    val Check: RavenMorphGlyph = RavenMorphGlyph(
        id = "check",
        strokes = listOf(
            MorphStroke.polyline(5f to 12.5f, 9.6f to 17f, 19f to 7.5f),
        ),
    )

    val HeartOutline: RavenMorphGlyph = RavenMorphGlyph(
        id = "heart_outline",
        strokes = listOf(
            MorphStroke.build(closed = true, fill = false) {
                moveTo(12f, 19.5f)
                cubicTo(7.4f, 16.4f, 3.8f, 13.1f, 3.8f, 8.9f)
                cubicTo(3.8f, 6.1f, 6f, 4.2f, 8.5f, 4.2f)
                cubicTo(10.1f, 4.2f, 11.4f, 5.1f, 12f, 6.3f)
                cubicTo(12.6f, 5.1f, 13.9f, 4.2f, 15.5f, 4.2f)
                cubicTo(18f, 4.2f, 20.2f, 6.1f, 20.2f, 8.9f)
                cubicTo(20.2f, 13.1f, 16.6f, 16.4f, 12f, 19.5f)
                close()
            },
        ),
    )

    val HeartFilled: RavenMorphGlyph = RavenMorphGlyph(
        id = "heart_filled",
        strokes = listOf(
            MorphStroke.build(closed = true, fill = true) {
                moveTo(12f, 20.3f)
                cubicTo(6.9f, 16.9f, 3.1f, 13.3f, 3.1f, 8.8f)
                cubicTo(3.1f, 5.6f, 5.6f, 3.5f, 8.4f, 3.5f)
                cubicTo(10.1f, 3.5f, 11.4f, 4.5f, 12f, 5.8f)
                cubicTo(12.6f, 4.5f, 13.9f, 3.5f, 15.6f, 3.5f)
                cubicTo(18.4f, 3.5f, 20.9f, 5.6f, 20.9f, 8.8f)
                cubicTo(20.9f, 13.3f, 17.1f, 16.9f, 12f, 20.3f)
                close()
            },
        ),
    )

    val BookmarkOutline: RavenMorphGlyph = RavenMorphGlyph(
        id = "bookmark_outline",
        strokes = listOf(
            MorphStroke.build(closed = true, fill = false) {
                moveTo(6.5f, 5.2f)
                quadTo(6.5f, 4f, 7.7f, 4f)
                lineTo(16.3f, 4f)
                quadTo(17.5f, 4f, 17.5f, 5.2f)
                lineTo(17.5f, 19.6f)
                lineTo(12f, 16.3f)
                lineTo(6.5f, 19.6f)
                close()
            },
        ),
    )

    val BookmarkFilled: RavenMorphGlyph = RavenMorphGlyph(
        id = "bookmark_filled",
        strokes = listOf(
            MorphStroke.build(closed = true, fill = true) {
                moveTo(5.8f, 5.1f)
                quadTo(5.8f, 3.5f, 7.4f, 3.5f)
                lineTo(16.6f, 3.5f)
                quadTo(18.2f, 3.5f, 18.2f, 5.1f)
                lineTo(18.2f, 20.4f)
                lineTo(12f, 16.7f)
                lineTo(5.8f, 20.4f)
                close()
            },
        ),
    )

    val PinOutline: RavenMorphGlyph = RavenMorphGlyph(
        id = "pin_outline",
        strokes = listOf(
            MorphStroke.build(closed = true, fill = false) {
                moveTo(8.5f, 4.5f)
                lineTo(15.5f, 4.5f)
                lineTo(14.5f, 10.2f)
                lineTo(17.2f, 13.5f)
                lineTo(6.8f, 13.5f)
                lineTo(9.5f, 10.2f)
                close()
            },
            MorphStroke.line(12f, 13.5f, 12f, 20f),
        ),
    )

    val PinFilled: RavenMorphGlyph = RavenMorphGlyph(
        id = "pin_filled",
        strokes = listOf(
            MorphStroke.build(closed = true, fill = true) {
                moveTo(8.1f, 4f)
                lineTo(15.9f, 4f)
                lineTo(14.8f, 10.1f)
                lineTo(17.8f, 13.8f)
                lineTo(6.2f, 13.8f)
                lineTo(9.2f, 10.1f)
                close()
            },
            MorphStroke.line(12f, 13.8f, 12f, 20.3f),
        ),
    )

    val Download: RavenMorphGlyph = RavenMorphGlyph(
        id = "download",
        strokes = listOf(
            MorphStroke.line(12f, 4f, 12f, 15f),
            MorphStroke.polyline(7.5f to 11f, 12f to 15.5f, 16.5f to 11f),
            MorphStroke.line(5f, 19.5f, 19f, 19.5f),
        ),
    )

    val DownloadDone: RavenMorphGlyph = RavenMorphGlyph(
        id = "download_done",
        strokes = listOf(
            MorphStroke.polyline(6f to 11.2f, 10f to 15.2f, 18f to 7.2f),
            MorphStroke.line(5f, 19.5f, 19f, 19.5f),
        ),
    )

    val BellOutline: RavenMorphGlyph = RavenMorphGlyph(
        id = "bell_outline",
        strokes = listOf(
            MorphStroke.build(closed = true, fill = false) {
                moveTo(6.5f, 14.5f)
                lineTo(6.5f, 10.2f)
                cubicTo(6.5f, 6.8f, 8.9f, 4.5f, 12f, 4.5f)
                cubicTo(15.1f, 4.5f, 17.5f, 6.8f, 17.5f, 10.2f)
                lineTo(17.5f, 14.5f)
                lineTo(19.5f, 17f)
                lineTo(4.5f, 17f)
                close()
            },
            MorphStroke.build(closed = false, fill = false) {
                moveTo(9.8f, 19.2f)
                quadTo(12f, 21f, 14.2f, 19.2f)
            },
        ),
    )

    val BellActive: RavenMorphGlyph = RavenMorphGlyph(
        id = "bell_active",
        strokes = listOf(
            MorphStroke.build(closed = true, fill = true) {
                moveTo(6f, 14.6f)
                lineTo(6f, 10.1f)
                cubicTo(6f, 6.4f, 8.6f, 3.8f, 12f, 3.8f)
                cubicTo(15.4f, 3.8f, 18f, 6.4f, 18f, 10.1f)
                lineTo(18f, 14.6f)
                lineTo(20.2f, 17.4f)
                lineTo(3.8f, 17.4f)
                close()
            },
            MorphStroke.build(closed = false, fill = false) {
                moveTo(9.8f, 19.3f)
                quadTo(12f, 21.2f, 14.2f, 19.3f)
            },
        ),
    )

    val GridView: RavenMorphGlyph = RavenMorphGlyph(
        id = "grid_view",
        strokes = listOf(
            MorphStroke.roundRect(4.5f, 4.5f, 10f, 10f, rx = 1.4f, fill = false),
            MorphStroke.roundRect(14f, 4.5f, 19.5f, 10f, rx = 1.4f, fill = false),
            MorphStroke.roundRect(4.5f, 14f, 10f, 19.5f, rx = 1.4f, fill = false),
            MorphStroke.roundRect(14f, 14f, 19.5f, 19.5f, rx = 1.4f, fill = false),
        ),
    )

    val ListView: RavenMorphGlyph = RavenMorphGlyph(
        id = "list_view",
        strokes = listOf(
            MorphStroke.line(4.5f, 6.5f, 19.5f, 6.5f),
            MorphStroke.line(4.5f, 12f, 19.5f, 12f),
            MorphStroke.line(4.5f, 17.5f, 19.5f, 17.5f),
        ),
    )

    val VisibilityOn: RavenMorphGlyph = RavenMorphGlyph(
        id = "visibility_on",
        strokes = listOf(
            MorphStroke.build(closed = true, fill = false) {
                moveTo(2.8f, 12f)
                cubicTo(5.3f, 7.2f, 8.5f, 5.6f, 12f, 5.6f)
                cubicTo(15.5f, 5.6f, 18.7f, 7.2f, 21.2f, 12f)
                cubicTo(18.7f, 16.8f, 15.5f, 18.4f, 12f, 18.4f)
                cubicTo(8.5f, 18.4f, 5.3f, 16.8f, 2.8f, 12f)
                close()
            },
            MorphStroke.circle(12f, 12f, 2.8f, fill = false),
        ),
    )

    val VisibilityOff: RavenMorphGlyph = RavenMorphGlyph(
        id = "visibility_off",
        strokes = listOf(
            MorphStroke.build(closed = true, fill = false) {
                moveTo(2.8f, 12f)
                cubicTo(5.3f, 7.2f, 8.5f, 5.6f, 12f, 5.6f)
                cubicTo(15.5f, 5.6f, 18.7f, 7.2f, 21.2f, 12f)
                cubicTo(18.7f, 16.8f, 15.5f, 18.4f, 12f, 18.4f)
                cubicTo(8.5f, 18.4f, 5.3f, 16.8f, 2.8f, 12f)
                close()
            },
            MorphStroke.circle(12f, 12f, 2.8f, fill = false),
            MorphStroke.line(4.2f, 4.2f, 19.8f, 19.8f),
        ),
    )

    val ChevronDown: RavenMorphGlyph = RavenMorphGlyph(
        id = "chevron_down",
        strokes = listOf(
            MorphStroke.polyline(6f to 9f, 12f to 15f, 18f to 9f),
        ),
    )

    val ChevronUp: RavenMorphGlyph = RavenMorphGlyph(
        id = "chevron_up",
        strokes = listOf(
            MorphStroke.polyline(6f to 15f, 12f to 9f, 18f to 15f),
        ),
    )

    val Close: RavenMorphGlyph = RavenMorphGlyph(
        id = "close",
        strokes = listOf(
            MorphStroke.line(6f, 6f, 18f, 18f),
            MorphStroke.line(18f, 6f, 6f, 18f),
        ),
    )

    val Refresh: RavenMorphGlyph = RavenMorphGlyph(
        id = "refresh",
        strokes = listOf(
            MorphStroke.arc(
                cx = 12f,
                cy = 12f,
                r = 6.8f,
                startAngleDeg = -35f,
                sweepAngleDeg = 295f,
                closed = false,
                fill = false,
            ),
            MorphStroke.polyline(14.8f to 7.8f, 18.2f to 8.2f, 18.6f to 4.8f),
        ),
    )

    val Movie: RavenMorphGlyph = RavenMorphGlyph(
        id = "movie",
        strokes = listOf(
            MorphStroke.roundRect(3.8f, 5.5f, 20.2f, 18.5f, rx = 2f, fill = false),
            MorphStroke.line(3.8f, 9.5f, 20.2f, 9.5f),
            MorphStroke.line(8f, 5.5f, 10f, 9.5f),
            MorphStroke.line(14f, 5.5f, 16f, 9.5f),
        ),
    )

    val Book: RavenMorphGlyph = RavenMorphGlyph(
        id = "book",
        strokes = listOf(
            MorphStroke.roundRect(5f, 4f, 19f, 20f, rx = 2f, fill = false),
            MorphStroke.line(8.5f, 4f, 8.5f, 20f),
            MorphStroke.line(11.5f, 8.5f, 16f, 8.5f),
        ),
    )

    val Photo: RavenMorphGlyph = RavenMorphGlyph(
        id = "photo",
        strokes = listOf(
            MorphStroke.roundRect(4f, 4.5f, 20f, 19.5f, rx = 2.2f, fill = false),
            MorphStroke.polyline(5.2f to 17.5f, 9.5f to 12.6f, 13f to 15.6f, 16f to 12.2f, 19.2f to 17.5f),
            MorphStroke.circle(8.6f, 8.8f, 1.3f, fill = false),
        ),
    )

    // -------------------------------------------------------------------------
    // Selection, Status, & Navigation Extras
    // -------------------------------------------------------------------------

    val CheckBoxEmpty: RavenMorphGlyph = RavenMorphGlyph(
        id = "check_box_empty",
        strokes = listOf(
            MorphStroke.roundRect(4.5f, 4.5f, 19.5f, 19.5f, rx = 2.6f, fill = false),
        ),
    )

    val CheckBoxChecked: RavenMorphGlyph = RavenMorphGlyph(
        id = "check_box_checked",
        strokes = listOf(
            MorphStroke.roundRect(4.5f, 4.5f, 19.5f, 19.5f, rx = 2.6f, fill = false),
            MorphStroke.polyline(8.2f to 12.2f, 10.8f to 14.8f, 15.8f to 9.4f),
        ),
    )

    val MailRead: RavenMorphGlyph = RavenMorphGlyph(
        id = "mail_read",
        strokes = listOf(
            MorphStroke.roundRect(3.5f, 5.5f, 20.5f, 18.5f, rx = 2.2f, fill = false),
            MorphStroke.polyline(4.3f to 7.2f, 12f to 12.8f, 19.7f to 7.2f),
        ),
    )

    val MailUnread: RavenMorphGlyph = RavenMorphGlyph(
        id = "mail_unread",
        strokes = listOf(
            MorphStroke.roundRect(3.5f, 5.5f, 20.5f, 18.5f, rx = 2.2f, fill = false),
            MorphStroke.polyline(4.3f to 7.2f, 12f to 12.8f, 19.7f to 7.2f),
            MorphStroke.circle(18.5f, 5.5f, 2.2f, fill = true),
        ),
    )

    val FilterOutline: RavenMorphGlyph = RavenMorphGlyph(
        id = "filter_outline",
        strokes = listOf(
            MorphStroke.build(closed = true, fill = false) {
                moveTo(4.2f, 5.5f)
                lineTo(19.8f, 5.5f)
                lineTo(13.8f, 12.6f)
                lineTo(13.8f, 18.6f)
                lineTo(10.2f, 20.2f)
                lineTo(10.2f, 12.6f)
                close()
            },
        ),
    )

    val FilterActive: RavenMorphGlyph = RavenMorphGlyph(
        id = "filter_active",
        strokes = listOf(
            MorphStroke.build(closed = true, fill = true) {
                moveTo(3.8f, 5.1f)
                lineTo(20.2f, 5.1f)
                lineTo(14f, 12.5f)
                lineTo(14f, 18.8f)
                lineTo(10f, 20.5f)
                lineTo(10f, 12.5f)
                close()
            },
        ),
    )

    val Verified: RavenMorphGlyph = RavenMorphGlyph(
        id = "verified",
        strokes = listOf(
            MorphStroke.build(closed = true, fill = false) {
                moveTo(12f, 3.6f)
                lineTo(18.8f, 6.4f)
                lineTo(18.8f, 11.6f)
                cubicTo(18.8f, 16.1f, 15.9f, 19.2f, 12f, 20.4f)
                cubicTo(8.1f, 19.2f, 5.2f, 16.1f, 5.2f, 11.6f)
                lineTo(5.2f, 6.4f)
                close()
            },
            MorphStroke.polyline(8.8f to 12.2f, 11.1f to 14.5f, 15.5f to 9.8f),
        ),
    )

    val Build: RavenMorphGlyph = RavenMorphGlyph(
        id = "build",
        strokes = listOf(
            MorphStroke.build(closed = true, fill = false) {
                moveTo(8.6f, 8.6f)
                lineTo(7.6f, 5.4f)
                quadTo(10.5f, 4.5f, 12.8f, 7.7f)
                lineTo(12.2f, 10.1f)
                lineTo(18.4f, 16.2f)
                quadTo(19.3f, 19.3f, 16.2f, 18.4f)
                lineTo(10.1f, 12.2f)
                lineTo(7.7f, 12.8f)
                quadTo(4.5f, 10.5f, 5.4f, 7.6f)
                lineTo(8.6f, 8.6f)
                close()
            },
        ),
    )

    val Link: RavenMorphGlyph = RavenMorphGlyph(
        id = "link",
        strokes = listOf(
            MorphStroke.build(closed = false, fill = false) {
                moveTo(10f, 8f)
                lineTo(7f, 8f)
                cubicTo(4.8f, 8f, 3.2f, 9.8f, 3.2f, 12f)
                cubicTo(3.2f, 14.2f, 4.8f, 16f, 7f, 16f)
                lineTo(10f, 16f)
            },
            MorphStroke.build(closed = false, fill = false) {
                moveTo(14f, 8f)
                lineTo(17f, 8f)
                cubicTo(19.2f, 8f, 20.8f, 9.8f, 20.8f, 12f)
                cubicTo(20.8f, 14.2f, 19.2f, 16f, 17f, 16f)
                lineTo(14f, 16f)
            },
            MorphStroke.line(8f, 12f, 16f, 12f),
        ),
    )

    val ChevronRight: RavenMorphGlyph = RavenMorphGlyph(
        id = "chevron_right",
        strokes = listOf(
            MorphStroke.polyline(9f to 6f, 15f to 12f, 9f to 18f),
        ),
    )

    val all: List<RavenMorphGlyph> = listOf(
        HomeOutline,
        HomeActive,
        ExploreOutline,
        ExploreActive,
        SearchOutline,
        SearchActive,
        LibraryOutline,
        LibraryActive,
        ProfileOutline,
        ProfileActive,
        Play,
        Pause,
        Replay,
        FullscreenEnter,
        FullscreenExit,
        LockOpen,
        LockClosed,
        VolumeOn,
        VolumeMuted,
        Plus,
        Check,
        HeartOutline,
        HeartFilled,
        BookmarkOutline,
        BookmarkFilled,
        PinOutline,
        PinFilled,
        Download,
        DownloadDone,
        BellOutline,
        BellActive,
        GridView,
        ListView,
        VisibilityOn,
        VisibilityOff,
        ChevronDown,
        ChevronUp,
        Close,
        Refresh,
        Movie,
        Book,
        Photo,
        CheckBoxEmpty,
        CheckBoxChecked,
        MailRead,
        MailUnread,
        FilterOutline,
        FilterActive,
        Verified,
        Build,
        Link,
        ChevronRight,
    )
}
