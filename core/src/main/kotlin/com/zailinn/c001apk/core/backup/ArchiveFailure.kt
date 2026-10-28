package com.zailinn.c001apk.core.backup

import com.zailinn.c001apk.core.net.ApiFailureKind

object ArchiveFailure {
    fun isGone(kind: ApiFailureKind?, message: String): Boolean = kind == ApiFailureKind.Business &&
        listOf("不存在", "已删除", "被删除", "无权限", "没有权限", "不可见", "未公开").any { it in message }
    fun canFallback(kind: ApiFailureKind?, message: String): Boolean = kind == ApiFailureKind.Transport || isGone(kind, message)
}
