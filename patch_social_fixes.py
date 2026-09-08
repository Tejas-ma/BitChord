import sys

content = open('app/src/main/java/com/music/bitchord/ui/screens/SocialScreen.kt').read()

import_mod = "import androidx.compose.ui.platform.LocalContext\n"
import_rep = "import androidx.compose.ui.platform.LocalContext\nimport androidx.compose.material3.SnackbarHostState\nimport androidx.compose.material3.SnackbarDuration\nimport androidx.compose.material3.SnackbarResult\n"

if "import androidx.compose.material3.SnackbarHostState" not in content:
    content = content.replace(import_mod, import_rep)

open('app/src/main/java/com/music/bitchord/ui/screens/SocialScreen.kt', 'w').write(content)
print("Success")
