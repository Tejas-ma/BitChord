with open('current_jam_view_model.txt', 'r') as f:
    orig = f.read()

# Start from clean slate
with open('app/src/main/java/com/music/bitchord/ui/social/JamViewModel.kt', 'w') as f:
    f.write(orig)
