package dev.baseline.ai

/** What a question is for, which decides where the router sends it (spec §6). A typed question is [PAGE]. */
enum class QuestionTag { PAGE, TRAINING, PATTERN }
