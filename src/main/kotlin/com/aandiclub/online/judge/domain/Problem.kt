package com.aandiclub.online.judge.domain

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "problems")
data class Problem(
    @Id
    val problemId: String,
    val testCases: List<TestCase>,
    val updatedAt: Instant = Instant.now(),
)
