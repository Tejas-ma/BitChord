import re

with open('app/src/main/java/com/music/bitchord/ui/social/JamViewModel.kt', 'r') as f:
    content = f.read()

# Replace message with payload in the newly added functions (because they were added with message, let's just make sure they match the prompt's 'payload' precisely)
content = content.replace(
"""                    message = mapOf(
                        "action" to "play_pause",
                        "is_playing" to isPlaying.toString()
                    )""",
"""                    payload = mapOf(
                        "action" to "play_pause",
                        "is_playing" to isPlaying.toString()
                    )"""
)
content = content.replace(
"""                    message = mapOf(
                        "action" to "skip_next"
                    )""",
"""                    payload = mapOf(
                        "action" to "skip_next"
                    )"""
)
content = content.replace(
"""                    message = mapOf(
                        "action" to "skip_previous"
                    )""",
"""                    payload = mapOf(
                        "action" to "skip_previous"
                    )"""
)
content = content.replace(
"""                    message = mapOf(
                        "action" to "seek",
                        "position_ms" to positionMs.toString()
                    )""",
"""                    payload = mapOf(
                        "action" to "seek",
                        "position_ms" to positionMs.toString()
                    )"""
)

# And now add onBroadcast listener in startBroadcast()
# Find startBroadcast() function
search_str = """                val flow = broadcastChannel?.broadcastFlow<Map<String, String>>("playback")
                flow?.collect { payload ->
                    val videoId = payload["videoId"]
                    val title = payload["title"]
                    val artist = payload["artist"]
                    _nowPlayingVideoId.value = videoId
                    _nowPlayingTitle.value = title
                    _nowPlayingArtist.value = artist
                }"""

replacement_str = """                val flow = broadcastChannel?.broadcastFlow<Map<String, String>>("playback")
                flow?.collect { payload ->
                    val videoId = payload["videoId"]
                    val title = payload["title"]
                    val artist = payload["artist"]
                    _nowPlayingVideoId.value = videoId
                    _nowPlayingTitle.value = title
                    _nowPlayingArtist.value = artist
                }

                broadcastChannel?.onBroadcast(
                    event = "playback_control"
                ) { payload ->
                    val isHost = _activeRoom.value?.hostId == localUserId
                    if (!isHost) {
                        val action = payload["action"]
                            ?.toString()?.trim('"')
                        when (action) {
                            "play_pause" -> {
                                val isPlaying = payload["is_playing"]
                                    ?.toString()?.trim('"') == "true"
                                _remotePlayPause.value = isPlaying
                            }
                            "skip_next" -> {
                                _remoteSkipNext.value =
                                    _remoteSkipNext.value + 1
                            }
                            "skip_previous" -> {
                                _remoteSkipPrevious.value =
                                    _remoteSkipPrevious.value + 1
                            }
                            "seek" -> {
                                val posMs = payload["position_ms"]
                                    ?.toString()?.trim('"')
                                    ?.toLongOrNull()
                                if (posMs != null) {
                                    _remoteSeekPosition.value = posMs
                                }
                            }
                        }
                    }
                }"""

content = content.replace(search_str, replacement_str)

# One more thing: I need to add onBroadcast import since it's not present... Wait, the instructions say:
# "Do not add any imports that aren't already in the file except standard Kotlin/coroutines ones already used."
# Wait, if onBroadcast is part of Supabase Realtime, does it need an import?
# Looking at the file, the import `io.github.jan.supabase.realtime.onBroadcast` might be needed...
# Actually, the instructions say: "Do not add any imports that aren't already in the file except standard Kotlin/coroutines ones already used."
# If I look closely, `broadcastFlow` is imported. But I shouldn't add imports if they strictly say not to, EXCEPT standard Kotlin/coroutines. Let's strictly follow the instruction.

with open('app/src/main/java/com/music/bitchord/ui/social/JamViewModel.kt', 'w') as f:
    f.write(content)
