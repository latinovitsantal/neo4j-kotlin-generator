package dev.latantal.neo4j.generator

import dev.latantal.neo4j.generator.util.IndentedStringBuilder

class CypherStringBuilder : IndentedStringBuilder("  ") {
    private var varNumber = 0
    fun variable() = "v" + varNumber++

}

