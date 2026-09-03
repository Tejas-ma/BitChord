import re

with open('app/build.gradle.kts', 'r') as f:
    content = f.read()

# Modify signing block to properly handle non-existent keystores without crashing the build
signing_block_old = """    val store = storeFilePath?.let { file(it) }

    if (store != null) {
        create("release") {
            storeFile = store
            storePassword = System.getenv("CM_KEYSTORE_PASSWORD") ?: signing.getProperty("storePassword")
            keyAlias = System.getenv("CM_KEY_ALIAS") ?: signing.getProperty("keyAlias")
            keyPassword = System.getenv("CM_KEY_PASSWORD") ?: signing.getProperty("keyPassword")
        }
    }"""

signing_block_new = """    val store = storeFilePath?.let { file(it) }

    if (store != null && store.exists()) {
        create("release") {
            storeFile = store
            storePassword = System.getenv("CM_KEYSTORE_PASSWORD") ?: signing.getProperty("storePassword")
            keyAlias = System.getenv("CM_KEY_ALIAS") ?: signing.getProperty("keyAlias")
            keyPassword = System.getenv("CM_KEY_PASSWORD") ?: signing.getProperty("keyPassword")
        }
    }"""

content = content.replace(signing_block_old, signing_block_new)

with open('app/build.gradle.kts', 'w') as f:
    f.write(content)
