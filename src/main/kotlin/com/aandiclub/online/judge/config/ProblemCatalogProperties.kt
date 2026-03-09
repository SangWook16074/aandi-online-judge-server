package com.aandiclub.online.judge.config

import com.aandiclub.online.judge.domain.Problem
import com.aandiclub.online.judge.domain.TestCase
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "judge.problems")
data class ProblemCatalogProperties(
    val items: Map<String, ProblemItem> = emptyMap(),
) {
    fun find(problemId: String): Problem? =
        items[problemId]?.let { Problem(problemId = problemId, testCases = it.testCases) }
}

data class ProblemItem(
    val testCases: List<TestCase> = emptyList(),
)
