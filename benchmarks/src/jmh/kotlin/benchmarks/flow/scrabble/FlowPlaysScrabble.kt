/*
 * Copyright 2016-2019 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package benchmarks.flow.scrabble

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.openjdk.jmh.annotations.*
import java.lang.Long.*
import java.util.*
import java.util.concurrent.*

@Warmup(iterations = 7, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 7, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
open class FlowPlaysScrabbleBase : ShakespearePlaysScrabble() {

    @Benchmark
    public fun play(): List<Map.Entry<Int, List<String>>> {
        val scoreOfALetter = { letter: Int -> flowOf(letterScores[letter - 'a'.toInt()]) }

        val letterScore = { entry: Map.Entry<Int, LongWrapper> ->
            flowOf(
                letterScores[entry.key - 'a'.toInt()] * Integer.min(
                    entry.value.get().toInt(),
                    scrabbleAvailableLetters[entry.key - 'a'.toInt()]
                )
            )
        }

        val toIntegerStream = { string: String ->
            IterableSpliterator.of(string.chars().boxed().spliterator()).asFlow()
        }

        val histoOfLetters = { word: String ->
            val f: suspend () -> Map<Int, LongWrapper> = {
                toIntegerStream(word).fold(HashMap()) { accumulator, value ->
                    var newValue: LongWrapper? = accumulator[value]
                    if (newValue == null) {
                        newValue = LongWrapper.zero()
                    }
                    accumulator[value] = newValue.incAndSet()
                    accumulator
                }
            }

            f.asFlow()
        }

        val blank = { entry: Map.Entry<Int, LongWrapper> ->
            flowOf(max(0L, entry.value.get() - scrabbleAvailableLetters[entry.key - 'a'.toInt()]))
        }

        val nBlanks = { word: String ->
            val f: suspend () -> Long = {
                histoOfLetters(word)
                    .flatMapConcat { map -> map.entries.iterator().asFlow() }
                    .flatMapConcat({ blank(it) })
                    .reduce { a, b -> a + b }
            }
            f.asFlow()
        }

        val checkBlanks = { word: String ->
            nBlanks(word).flatMapConcat { l -> flowOf(l <= 2L) }
        }


        val score2 =  { word: String ->
            val f: suspend () -> Int = {
                (histoOfLetters(word)
                    .flatMapConcat { map -> map.entries.iterator().asFlow() }
                    .flatMapConcat { letterScore(it) }
                    .reduce { a, b -> a + b })
            }

            f.asFlow()
        }

        val first3 = { word: String ->
            IterableSpliterator.of(word.chars().boxed().limit(3).spliterator()).asFlow()
        }

        val last3 = { word: String ->
            IterableSpliterator.of(word.chars().boxed().skip(3).spliterator()).asFlow()
        }

        val toBeMaxed = { word: String -> flowOf(first3(word), last3(word)).flattenConcat() }

        // Bonus for double letter
        val bonusForDoubleLetter = { word: String ->
            val f: suspend () -> Int = {
                toBeMaxed(word)
                    .flatMapConcat { scoreOfALetter(it) }
                    .reduce { a, b -> Integer.max(a, b) }
            }

            f.asFlow()
        }

        val score3 = { word: String ->
            val f: suspend () -> Int = {
                flowOf(score2(word), score2(word),
                bonusForDoubleLetter(word),
                bonusForDoubleLetter(word),
                flowOf(if (word.length == 7) 50 else 0)).flattenConcat().reduce { a, b -> a + b }
            }

            f.asFlow()
        }

        val buildHistoOnScore: (((String) -> Flow<Int>) -> Flow<TreeMap<Int, List<String>>>) = { score ->
            val f: suspend () -> TreeMap<Int, List<String>> = {
                shakespeareWords.iterator().asFlow()
                    .filter({ scrabbleWords.contains(it) && checkBlanks(it).single() })
                    .fold(TreeMap<Int, List<String>>(Collections.reverseOrder())) { acc, value ->
                        val key = score(value).single()
                        var list = acc[key] as MutableList<String>?
                        if (list == null) {
                            list = ArrayList()
                            acc[key] = list
                        }
                        list.add(value)
                        acc
                    }
            }

            f.asFlow()
        }

        return runBlocking {
            buildHistoOnScore(score3)
                .flatMapConcat { map -> map.entries.iterator().asFlow() }
                .take(3)
                .toList()
        }
    }

}
