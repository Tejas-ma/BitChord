import sys

content = open('app/src/main/java/com/music/bitchord/ui/screens/SocialScreen.kt').read()

import_mod = "    val snackbarHostState = remember { SnackbarHostState() }\n"
if "    val snackbarHostState = remember { SnackbarHostState() }" not in content:
    content = content.replace("    val coroutineScope = rememberCoroutineScope()", "    val snackbarHostState = remember { SnackbarHostState() }\n    val coroutineScope = rememberCoroutineScope()")

open('app/src/main/java/com/music/bitchord/ui/screens/SocialScreen.kt', 'w').write(content)
print("Success")
