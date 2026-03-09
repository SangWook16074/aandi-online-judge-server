package com.aandiclub.online.judge.api.dto

import com.aandiclub.online.judge.domain.Language
import jakarta.validation.Validation
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SubmissionRequestValidationTest {

    private val validator = Validation.buildDefaultValidatorFactory().validator

    @Test
    fun `problemId accepts alphanumeric and hyphen`() {
        val req = SubmissionRequest(
            problemId = "quiz-101-A1",
            language = Language.PYTHON,
            code = "def solution(a,b): return a+b",
        )

        val violations = validator.validate(req)

        assertTrue(violations.none { it.propertyPath.toString() == "problemId" })
    }

    @Test
    fun `problemId rejects invalid characters`() {
        val req = SubmissionRequest(
            problemId = "quiz_101 bad",
            language = Language.PYTHON,
            code = "def solution(a,b): return a+b",
        )

        val violations = validator.validate(req)

        assertFalse(violations.none { it.propertyPath.toString() == "problemId" })
    }
}
