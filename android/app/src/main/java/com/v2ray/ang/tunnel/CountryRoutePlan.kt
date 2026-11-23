package com.v2ray.ang.tunnel

import java.math.BigInteger
import java.net.InetAddress

/** Computes route subtraction for Android versions without excludeRoute. */
object CountryRoutePlan {
    data class Prefix(val address: String, val length: Int) {
        val bytes: ByteArray get() = InetAddress.getByName(address).address
    }
    private data class Interval(val first: BigInteger, val last: BigInteger, val bits: Int)
    private fun interval(prefix: Prefix): Interval {
        val bytes = prefix.bytes
        val bits = bytes.size * 8
        require(prefix.length in 0..bits)
        val size = BigInteger.ONE.shiftLeft(bits - prefix.length)
        val first = BigInteger(1, bytes).divide(size).multiply(size)
        return Interval(first, first + size - BigInteger.ONE, bits)
    }
    fun subtract(included: List<Prefix>, excluded: List<Prefix>): List<Prefix> {
        val exclusions = excluded.map(::interval).sortedBy { it.first }
        return included.flatMap { prefix ->
            val source = interval(prefix)
            var cursor = source.first
            val ranges = buildList {
                for (cut in exclusions) {
                    if (cut.bits != source.bits || cut.last < cursor) continue
                    if (cut.first > source.last) break
                    if (cut.first > cursor) add(Interval(cursor, cut.first - BigInteger.ONE, source.bits))
                    cursor = maxOf(cursor, cut.last + BigInteger.ONE)
                    if (cursor > source.last) break
                }
                if (cursor <= source.last) add(Interval(cursor, source.last, source.bits))
            }
            ranges.flatMap(::prefixes)
        }.distinct()
    }
    private fun prefixes(range: Interval): List<Prefix> = buildList {
        var current = range.first
        while (current <= range.last) {
            val remaining = range.last - current + BigInteger.ONE
            val power = minOf(if (current == BigInteger.ZERO) range.bits else current.lowestSetBit,
                remaining.bitLength() - 1)
            val raw = current.toByteArray()
            val bytes = ByteArray(range.bits / 8)
            val count = minOf(raw.size, bytes.size)
            raw.copyInto(bytes, bytes.size - count, raw.size - count)
            add(Prefix(InetAddress.getByAddress(bytes).hostAddress!!, range.bits - power))
            current += BigInteger.ONE.shiftLeft(power)
        }
    }
}
