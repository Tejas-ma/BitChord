import sys

content = open('app/src/main/java/com/music/bitchord/ui/social/JamRoomScreen.kt').read()

import_mod = "import androidx.compose.ui.platform.LocalContext\n"
import_rep = "import androidx.compose.ui.platform.LocalContext\nimport androidx.compose.foundation.clickable\n"

if "import androidx.compose.foundation.clickable" not in content:
    content = content.replace(import_mod, import_rep)

open('app/src/main/java/com/music/bitchord/ui/social/JamRoomScreen.kt', 'w').write(content)
print("Success")
