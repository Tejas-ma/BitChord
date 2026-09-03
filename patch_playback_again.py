import re

with open('app/src/main/java/com/music/bitchord/playback/PlaybackService.kt', 'r') as f:
    content = f.read()

# Make player public
content = content.replace('    private var player: ExoPlayer? = null', '    var player: ExoPlayer? = null')

# Make the companion object public
content = re.sub(r'    private companion object \{', '    companion object {', content)

# Expose a global player instance
inject = """        const val DISCORD_TEARDOWN_TIMEOUT_MS = 3_000L

        /**
         * Global exo player instance for MediaLibraryService.
         */
        var globalPlayer: androidx.media3.exoplayer.ExoPlayer? = null"""

content = re.sub(r'        const val DISCORD_TEARDOWN_TIMEOUT_MS = 3_000L', inject, content, count=1)

# In applySettings or somewhere the player is set
content = re.sub(r'        player = exoPlayer', '        player = exoPlayer\n        globalPlayer = exoPlayer', content, count=1)

with open('app/src/main/java/com/music/bitchord/playback/PlaybackService.kt', 'w') as f:
    f.write(content)
