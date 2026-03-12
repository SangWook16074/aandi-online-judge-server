package com.aandiclub.online.judge.repository

import com.aandiclub.online.judge.domain.Problem
import org.springframework.data.mongodb.repository.ReactiveMongoRepository

interface ProblemRepository : ReactiveMongoRepository<Problem, String>
