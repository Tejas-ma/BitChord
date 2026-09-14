import sys

filename = 'app/src/main/java/com/music/bitchord/ui/social/JamViewModel.kt'
with open(filename, 'r') as f:
    content = f.read()

if '_remoteVideoId' not in content:
    remote_video_id_code = """
    private val _remoteVideoId = MutableStateFlow<String?>(null)
    val remoteVideoId: StateFlow<String?> = _remoteVideoId.asStateFlow()

    fun clearRemoteVideoId() {
        _remoteVideoId.value = null
    }
"""
    # Insert after _remoteSeekPosition
    content = content.replace(
        """    private val _remoteSeekPosition = """,
        remote_video_id_code.lstrip('\n') + """    private val _remoteSeekPosition = """
    )

if '_remoteVideoId.value = videoId' not in content:
    content = content.replace(
        '_nowPlayingVideoId.value = videoId',
        '_nowPlayingVideoId.value = videoId\n                    if (videoId != null) {\n                        _remoteVideoId.value = videoId\n                    }'
    )

with open(filename, 'w') as f:
    f.write(content)

print("JamViewModel patched successfully.")
