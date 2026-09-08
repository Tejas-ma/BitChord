import sys

content = open('app/src/main/java/com/music/bitchord/ui/screens/SocialScreen.kt').read()

import_mod = "    val coroutineScope = rememberCoroutineScope()"
import_rep = "    val snackbarHostState = remember { SnackbarHostState() }\n    val coroutineScope = rememberCoroutineScope()"

if "val snackbarHostState =" not in content:
    content = content.replace(import_mod, import_rep)

open('app/src/main/java/com/music/bitchord/ui/screens/SocialScreen.kt', 'w').write(content)
print("Success")
