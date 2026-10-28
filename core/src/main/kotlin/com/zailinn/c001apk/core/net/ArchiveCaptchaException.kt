package com.zailinn.c001apk.core.net

/** Safe message; the request is retained privately only to resume its explicit verification. */
class ArchiveCaptchaException(val requestUrl: String) : Exception("需要完成验证后继续")
