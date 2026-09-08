import sys

content = open('app/src/main/java/com/music/bitchord/ui/screens/SocialScreen.kt').read()

import_mod = "    // Detect if a friend started a new Jam\n"
import_rep = "    val snackbarHostState = remember { SnackbarHostState() }\n    // Detect if a friend started a new Jam\n"

if "val result = snackbarHostState" in content:
    content = content.replace(import_mod, import_rep)

open('app/src/main/java/com/music/bitchord/ui/screens/SocialScreen.kt', 'w').write(content)
print("Success")
