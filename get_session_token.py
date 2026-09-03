import re

with open('app/src/main/java/com/music/bitchord/playback/PlaybackService.kt', 'r') as f:
    content = f.read()

print("Companion object exists:", "companion object" in content)
