with open('app/src/main/java/com/music/bitchord/ui/social/JamViewModel.kt', 'r') as f:
    content = f.read()

# CHANGE 1 — Add playback broadcast functions
broadcast_funcs = """    fun broadcastPlayPause(isPlaying: Boolean) {
        viewModelScope.launch {
            try {
                broadcastChannel?.broadcast(
                    event = "playback_control",
                    payload = mapOf(
                        "action" to "play_pause",
                        "is_playing" to isPlaying.toString()
                    )
                )
            } catch (e: Exception) { }
        }
    }

    fun broadcastSkipNext() {
        viewModelScope.launch {
            try {
                broadcastChannel?.broadcast(
                    event = "playback_control",
                    payload = mapOf(
                        "action" to "skip_next"
                    )
                )
            } catch (e: Exception) { }
        }
    }

    fun broadcastSkipPrevious() {
        viewModelScope.launch {
            try {
                broadcastChannel?.broadcast(
                    event = "playback_control",
                    payload = mapOf(
                        "action" to "skip_previous"
                    )
                )
            } catch (e: Exception) { }
        }
    }

    fun broadcastSeek(positionMs: Long) {
        viewModelScope.launch {
            try {
                broadcastChannel?.broadcast(
                    event = "playback_control",
                    payload = mapOf(
                        "action" to "seek",
                        "position_ms" to positionMs.toString()
                    )
                )
            } catch (e: Exception) { }
        }
    }"""
# Wait, looking at current_jam_view_model.txt, it seems I ALREADY had them added with `message` because the LLM did it in a previous run?
# Let's check the clean file. Ah, `current_jam_view_model.txt` was created AFTER I ran the first patch? No, wait.
