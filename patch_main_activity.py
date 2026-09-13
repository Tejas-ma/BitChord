import re

with open('app/src/main/java/com/music/bitchord/MainActivity.kt', 'r') as f:
    content = f.read()

# I need to find the usages of NowPlayingScreen in MainActivity and pass these new variables.

with open('app/src/main/java/com/music/bitchord/MainActivity.kt', 'r') as f:
    content = f.read()

search_now_playing = """        NowPlayingScreen(
            song = displayedSong,
            windowWidth = windowWidth,
            isPlaying = player.isPlaying,"""

replace_now_playing = """        NowPlayingScreen(
            isJamMember = activeRoom != null && activeRoom?.hostId != authStore.localUserId,
            jamAllowsControl = activeRoom?.allowOthersToPlay == true,
            song = displayedSong,
            windowWidth = windowWidth,
            isPlaying = player.isPlaying,"""

content = content.replace(search_now_playing, replace_now_playing)

with open('app/src/main/java/com/music/bitchord/MainActivity.kt', 'w') as f:
    f.write(content)
