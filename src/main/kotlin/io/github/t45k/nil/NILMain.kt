package io.github.t45k.nil

import io.github.t45k.nil.entity.CodeBlock
import io.github.t45k.nil.output.CSV
import io.github.t45k.nil.tokenizer.SymbolSeparator
import io.github.t45k.nil.tokenizer.Tokenizer
import io.github.t45k.nil.util.ProgressMonitor
import io.github.t45k.nil.util.toTime
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.kotlin.toObservable
import java.io.File
import kotlin.math.min

// 一旦リストに保持する
// スケーラビリティを考えると将来的にDBを使うかも
// IDはリストとかDBのインデックスで大丈夫そう
class NILMain(private val config: NILConfig) {
    companion object {
        private const val PARTITION = 500_000
    }

    private val tokenizer: Tokenizer = SymbolSeparator()

    fun run() {
        val startTime = System.currentTimeMillis()
        val codeBlocks: List<CodeBlock> = collectSourceFiles(config.src)
            .flatMap(this::collectBlocks)
            .toList()
            .blockingGet()

        println("${codeBlocks.size} code blocks have been extracted in ${((System.currentTimeMillis() - startTime) / 1000).toTime()}.\n")

        val verification = Verification(codeBlocks)
        val progressMonitor = ProgressMonitor(codeBlocks.size)
        val location = Location(config.filteringThreshold, codeBlocks)
        val clonePairs: List<Pair<Int, Int>> = generateSequence(0) { it + 1 }
            .map { it * PARTITION }
            .takeWhile { it < codeBlocks.size }
            .flatMap { startIndex ->
                sequence {
                    val termination = min(startIndex + PARTITION, codeBlocks.size)
                    println()
                    val internal = ProgressMonitor(codeBlocks.size - startIndex)
                    for (index in startIndex until codeBlocks.size) {
                        location.locate(codeBlocks[index].nGrams)
                            .filter { verification.verify(index, it) }
                            .forEach { yield(index to it) }

                        if (index < termination) {
                            location.put(codeBlocks[index].nGrams, index)
                        }
                        internal.update(index + 1)
                    }

                    location.clear()
                    progressMonitor.update((startIndex + 1) * PARTITION)
                }
            }.toList()

        println("${clonePairs.size} clone pairs are detected.")

        val endTime = System.currentTimeMillis()
        println("time: ${((endTime - startTime) / 1000).toTime()}")

        CSV().output(config.outputFileName, clonePairs, codeBlocks)
    }

    private fun collectSourceFiles(dir: File): Observable<File> =
        dir.walk()
            .filter { it.isFile && it.toString().endsWith(".java") }
            .toObservable()

    private fun collectBlocks(sourceFile: File): Observable<CodeBlock> =
        Observable.just(sourceFile)
            .flatMap { AST(tokenizer::tokenize, config).extractBlocks(it) }
}

fun main(args: Array<String>) {
    val config: NILConfig = parseArgs(args)
    NILMain(config).run()
}
