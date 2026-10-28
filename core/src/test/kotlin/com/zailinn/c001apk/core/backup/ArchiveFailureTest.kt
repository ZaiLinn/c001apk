package com.zailinn.c001apk.core.backup

import com.zailinn.c001apk.core.net.ApiFailureKind
import org.junit.Assert.*
import org.junit.Test

class ArchiveFailureTest {
    @Test fun onlyConfirmedBusinessReasonsMarkGoneAndCaptchaNeverFallsBack() {
        assertTrue(ArchiveFailure.isGone(ApiFailureKind.Business, "该动态已删除"))
        assertTrue(ArchiveFailure.canFallback(ApiFailureKind.Business, "没有权限查看"))
        assertFalse(ArchiveFailure.isGone(ApiFailureKind.Transport, "连接失败"))
        assertTrue(ArchiveFailure.canFallback(ApiFailureKind.Transport, "超时"))
        for (kind in listOf(ApiFailureKind.Captcha, ApiFailureKind.Authentication, ApiFailureKind.RateLimit, ApiFailureKind.RiskControl)) {
            assertFalse(ArchiveFailure.canFallback(kind, "不存在"))
        }
        assertFalse(ArchiveFailure.canFallback(ApiFailureKind.Business, "服务器繁忙"))
    }
}
