package com.thegreatequalizer.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class ImageImportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val grantFlags = intent.flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        val editorIntent = Intent(this, MainActivity::class.java).apply {
            action = intent.action
            setDataAndType(intent.data, intent.type)
            clipData = intent.clipData
            replaceExtras(intent)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    grantFlags
            )
        }

        startActivity(editorIntent)
        finish()
    }
}
