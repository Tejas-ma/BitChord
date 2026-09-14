import sys

filename = 'app/src/main/java/com/music/bitchord/MainActivity.kt'
with open(filename, 'r') as f:
    content = f.read()

# CHANGE 1: Add remoteVideoId collection
remote_video_id_collection = """    val remotePlayPause by jamViewModel.remotePlayPause
        .collectAsStateWithLifecycle()
    val remoteVideoId by jamViewModel.remoteVideoId
        .collectAsStateWithLifecycle()"""
if 'val remoteVideoId by jamViewModel.remoteVideoId' not in content:
    content = content.replace(
        '    val remotePlayPause by jamViewModel.remotePlayPause\n        .collectAsStateWithLifecycle()',
        remote_video_id_collection
    )

# CHANGE 2: Add LaunchedEffect(remoteVideoId)
launched_effect_remote_video_id = """    LaunchedEffect(remotePlayPause) {
        remotePlayPause?.let { shouldPlay ->
            if (shouldPlay) controller?.play()
            else controller?.pause()
            jamViewModel.clearRemotePlayPause()
        }
    }

    LaunchedEffect(remoteVideoId) {
        remoteVideoId?.let { videoId ->
            val room = jamViewModel.activeRoom.value
            val isHost = room?.hostId == authStore.localUserId
            if (!isHost && room != null) {
                try {
                    val song = YtMusicRepository
                        .trackLinks(videoId)
                        .getOrNull()
                    if (song != null) {
                        controller?.playSongs(
                            listOf(song), 0
                        )
                    }
                } catch (e: Exception) {
                    // silent — sync is best effort
                }
            }
            jamViewModel.clearRemoteVideoId()
        }
    }"""
if 'LaunchedEffect(remoteVideoId)' not in content:
    content = content.replace(
        """    LaunchedEffect(remotePlayPause) {
        remotePlayPause?.let { shouldPlay ->
            if (shouldPlay) controller?.play()
            else controller?.pause()
            jamViewModel.clearRemotePlayPause()
        }
    }""",
        launched_effect_remote_video_id
    )

# CHANGE 3: Update broadcastNowPlaying
launched_effect_song = """    LaunchedEffect(player.song?.videoId) {
        player.song?.let { song ->
            val room = jamViewModel.activeRoom.value
            val isHost = room?.hostId == authStore.localUserId
            if (isHost && room != null) {
                jamViewModel.broadcastNowPlaying(
                    videoId = song.videoId,
                    title = song.title,
                    artist = song.artist
                )
            }
        }
    }"""

old_launched_effect_song_pattern1 = """    LaunchedEffect(player.song, activeRoom) {
        val song = player.song ?: return@LaunchedEffect
        val room = activeRoom ?: return@LaunchedEffect
        val isHost = room.hostId == authStore.localUserId
        if (isHost) {
            jamViewModel.broadcastNowPlaying(
                videoId = song.videoId,
                title = song.title,
                artist = song.artist
            )
        }
    }"""

if old_launched_effect_song_pattern1 in content:
    content = content.replace(old_launched_effect_song_pattern1, launched_effect_song)
elif 'LaunchedEffect(player.song?.videoId)' not in content:
    print("Could not find old broadcastNowPlaying LaunchedEffect. Make sure to check manually.")

with open(filename, 'w') as f:
    f.write(content)

print("MainActivity patched successfully.")
