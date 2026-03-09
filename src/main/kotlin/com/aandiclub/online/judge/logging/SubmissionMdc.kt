package com.aandiclub.online.judge.logging

import kotlinx.coroutines.slf4j.MDCContext
import org.slf4j.MDC

object SubmissionMdc {
    const val KEY: String = "submissionId"

    fun context(submissionId: String): MDCContext = MDCContext(mapOf(KEY to submissionId))

    inline fun <T> withSubmissionId(submissionId: String, block: () -> T): T {
        val previous = MDC.get(KEY)
        MDC.put(KEY, submissionId)
        return try {
            block()
        } finally {
            if (previous == null) {
                MDC.remove(KEY)
            } else {
                MDC.put(KEY, previous)
            }
        }
    }
}
