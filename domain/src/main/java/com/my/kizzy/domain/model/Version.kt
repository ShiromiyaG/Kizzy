// https://github.com/Ashinch/ReadYou/blob/main/app/src/main/java/me/ash/reader/data/model/general/Version.kt

package com.my.kizzy.domain.model

import com.my.kizzy.domain.model.release.Release

/**
 * A class that represents a version.
 * @param numbers The version numbers.
 * @property major The major version number.
 * @property minor The minor version number.
 * @property patch The patch version number.
 */
class Version(numbers: List<String>) {

    private var major: Int = 0
    private var minor: Int = 0
    private var patch: Int = 0

    init {
        major = numbers.getOrNull(0)?.toIntOrNull() ?: 0
        minor = numbers.getOrNull(1)?.toIntOrNull() ?: 0
        patch = numbers.getOrNull(2)?.toIntOrNull() ?: 0
        println("Version parsed: $numbers -> major=$major, minor=$minor, patch=$patch")
    }

    constructor() : this(listOf())
    constructor(string: String?) : this(string?.removePrefix("v")?.split(".") ?: listOf())

    override fun toString() = "$major.$minor.$patch"

    /**
     * Use [major], [minor], [patch] for comparison.
     *
     * 1. [major] <=> [other.major]
     * 2. [minor] <=> [other.minor]
     * 3. [patch] <=> [other.patch]
     */
    operator fun compareTo(other: Version): Int = when {
        major > other.major -> 1
        major < other.major -> -1
        minor > other.minor -> 1
        minor < other.minor -> -1
        patch > other.patch -> 1
        patch < other.patch -> -1
        else -> 0
    }

    /**
     * Returns whether this version is larger [current] version.
     */
    fun whetherNeedUpdate(current: Version): Boolean = this > current
}

fun String?.toVersion(): Version = Version(this)

fun Release.toVersion(): Version = Version(tagName)