package dev.example.edd

import dev.dokimos.core.ExperimentResult

fun ExperimentResult.print() {   // 6. Display results
    println("=".repeat(60))
    println("Evaluation Results")
    println("=".repeat(60))
    println("Pass rate: ${"%.0f".format(this.passRate() * 100)}%")
    println()

    println("Average Scores:")
    evaluatorNames().forEach { evalutor ->
        println("  $evalutor: ${"%.2f".format(this.averageScore(evalutor))}")
    }
    println()

    println("Detailed Results:")
    println("-".repeat(60))
    this.itemResults().forEach { item ->
        println()
        println("Question: ${item.example().input()}")
        println("Response: ${item.actualOutputs()["output"]}")
        println("Expected: ${item.example().expectedOutput()}")
        println("Status: ${if (item.success()) "✅ PASS" else "❌ FAIL"}")
        println("Scores:")
        item.evalResults().forEach { eval ->
            println("  • ${eval.name()}: ${"%.2f".format(eval.score())}${if (eval.success()) " ✅" else " ❌"}")
        }
        println("- - ".repeat(20))
    }
    println()
    println("=".repeat(60))
}