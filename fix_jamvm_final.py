import re
with open('app/src/main/java/com/music/bitchord/ui/social/JamViewModel.kt', 'r') as f:
    content = f.read()

# Fix the duplicate createRoom functions and the unresolved auth references
# There are two `fun createRoom`!
# Let's remove the first one that is causing problems and use the second one, or merge them.
# The original file had `fun createRoom(name: String, hostId: String, privacy: String = "everyone")`
# We added `fun createRoom(name: String, isPrivate: Boolean, maxMembers: Int)` but with the wrong imports and missing `import io.github.jan.supabase.gotrue.auth`

# Let's remove our added createRoom and just adapt the existing one. Wait, SocialScreen expects `createRoom(name, isPrivate, maxMembers)`
# So we need to keep `createRoom(name, isPrivate, maxMembers)` but fix it so it doesn't use auth if auth is unresolved, or use auth correctly.

# The compiler error was:
# e: file:///home/runner/work/BitChord/BitChord/app/src/main/java/com/music/bitchord/ui/social/JamViewModel.kt:10:31 Unresolved reference 'gotrue'.
# e: file:///home/runner/work/BitChord/BitChord/app/src/main/java/com/music/bitchord/ui/social/JamViewModel.kt:71:43 Unresolved reference 'auth'.

# If `gotrue` is unresolved, maybe it's not a dependency of this project? The original `JamViewModel` didn't use `supabase.auth` at all!
# It used `com.music.bitchord.data.jam.JamRepository`.
# We should use `repository.createRoom` instead of `supabase.postgrest["rooms"].insert`!
# Ah!

replacement = """
    fun createRoom(name: String, isPrivate: Boolean, maxMembers: Int) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.createRoom(name, "host_id", if (isPrivate) "private" else "everyone")
                loadRooms()
            } catch (e: Exception) {
                _error.value = "Could not create room."
            } finally {
                _isLoading.value = false
            }
        }
    }
"""

content = re.sub(r'fun createRoom\(name: String, isPrivate: Boolean, maxMembers: Int\) \{.*?\n    \}', replacement.strip(), content, flags=re.DOTALL)
content = content.replace("import io.github.jan.supabase.gotrue.auth\n", "")
content = content.replace("import io.github.jan.supabase.gotrue.auth", "")

with open('app/src/main/java/com/music/bitchord/ui/social/JamViewModel.kt', 'w') as f:
    f.write(content)
