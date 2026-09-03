import re

file_path = "./app/src/main/java/com/music/bitchord/ui/components/SongActionsSheet.kt"

with open(file_path, "r") as f:
    content = f.read()

import_lines = [
    "import androidx.compose.animation.animateColorAsState",
    "import androidx.compose.animation.core.animateFloatAsState",
    "import androidx.compose.ui.hapticfeedback.HapticFeedbackType",
    "import androidx.compose.ui.platform.LocalHapticFeedback",
    "import androidx.compose.ui.draw.scale",
    "import androidx.compose.runtime.mutableStateOf",
    "import androidx.compose.runtime.getValue",
    "import androidx.compose.runtime.setValue",
    "import androidx.compose.runtime.remember"
]

for imp in import_lines:
    if imp not in content:
        content = content.replace("import androidx.compose.foundation.clickable", imp + "\nimport androidx.compose.foundation.clickable")

with open(file_path, "w") as f:
    f.write(content)
print("Updated SongActionsSheet imports")
