with open('app/src/main/java/com/music/bitchord/ui/player/NowPlayingScreen.kt', 'r') as f:
    content = f.read()

# Fix enum modifier: Extract LoopMode out of the composable function to the top-level
content = content.replace("                enum class LoopMode { OFF, REPEAT_ONE, REPEAT_ALL, AUTOPLAY }\n", "")

import_end = content.rfind("import ")
import_end_line = content.find("\n", import_end)
content = content[:import_end_line+1] + "\nenum class LoopMode { OFF, REPEAT_ONE, REPEAT_ALL, AUTOPLAY }\n" + content[import_end_line+1:]

# Fix IconButton unresolved reference: import androidx.compose.material3.IconButton
if "import androidx.compose.material3.IconButton" not in content:
    content = content.replace("import androidx.compose.material3.Icon", "import androidx.compose.material3.Icon\nimport androidx.compose.material3.IconButton")

with open('app/src/main/java/com/music/bitchord/ui/player/NowPlayingScreen.kt', 'w') as f:
    f.write(content)
