package com.aandiclub.online.judge.domain

data class TestCase(
    val caseId: Int,
    val args: List<Any?>,
    val expectedOutput: String,
)
