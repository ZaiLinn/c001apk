package com.zailinn.c001apk.core.net

/**
 * Both the API and the complete public representation refused access to the post.
 * [needsLogin]: the server wants an account (guest summary, expired login), so a
 * 数盟 ID would not help. Otherwise the device or a captcha is the obstacle.
 */
class BackupRestrictedException(val needsLogin: Boolean = false) : Exception(
    if (needsLogin) "游客无法读取完整正文，登录后可备份全文" else "帖子完整正文暂时受限，可填写数盟 ID 后重试")
