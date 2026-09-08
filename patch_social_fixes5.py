import sys

content = open('app/src/main/java/com/music/bitchord/ui/screens/SocialScreen.kt').read()

lines = content.split('\n')
new_lines = []
count = 0
for line in lines:
    if 'val snackbarHostState = remember { SnackbarHostState() }' in line:
        if count == 0:
            new_lines.append(line)
            count += 1
    else:
        new_lines.append(line)

open('app/src/main/java/com/music/bitchord/ui/screens/SocialScreen.kt', 'w').write('\n'.join(new_lines))
print("Success")
