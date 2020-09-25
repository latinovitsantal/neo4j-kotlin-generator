package dev.latantal.neo4j.generator

import dev.latantal.neo4j.generator.util.IndentedStringBuilder
import dev.latantal.neo4j.generator.util.buildIndentedString
import kotlin.reflect.KClass


sealed class KotlinModel {
    data class DataClass(
        var name: String,
        val properties: Map<String, KotlinModel>
    ) : KotlinModel() {
        override fun notion() = name
        override fun dataClasses(): List<DataClass> = listOf(this)
            .plus(properties.values.flatMap { it.dataClasses() })
        override fun generateConcatenatedNames(prefixes: List<String>) {
            val newPrefixes = prefixes.plus(name)
            val unneeded = newPrefixes.distinct().map { newPrefixes.indexOf(it) }.maxOrNull()!!
            name = newPrefixes.drop(unneeded).joinToString("")
            properties.values.forEach { it.generateConcatenatedNames(newPrefixes) }
        }
        fun appendCode(builder: IndentedStringBuilder) {
            builder.run {
                append("data class ${name}(")
                indented {
                    properties.forEach { (name, type) ->
                        newline()
                        append("val $name: ${type.notion()},")
                    }
                }
                newline()
                append(")")
            }
        }
    }
    data class Maybe(val type: KotlinModel) : KotlinModel() {
        override fun notion() = "${type.notion()}?"
        override fun dataClasses(): List<DataClass> = type.dataClasses()
        override fun generateConcatenatedNames(prefixes: List<String>) = type.generateConcatenatedNames(prefixes)
    }
    data class Multiple(val element: KotlinModel) : KotlinModel() {
        override fun notion() = "List<${element.notion()}>"
        override fun dataClasses(): List<DataClass> = element.dataClasses()
        override fun generateConcatenatedNames(prefixes: List<String>) = element.generateConcatenatedNames(prefixes)
    }
    data class Primitive(val type: KClass<*>) : KotlinModel() {
        override fun notion() = type.qualifiedName!!
        override fun dataClasses(): List<DataClass> = emptyList()
        override fun generateConcatenatedNames(prefixes: List<String>) {}
    }

    fun withMultiplicity(multiplicity: Multiplicity): KotlinModel = when(multiplicity) {
        Multiplicity.ONE -> this
        Multiplicity.ONE_OR_ZERO -> Maybe(this)
        Multiplicity.MANY -> Multiple(this)
    }

    abstract fun notion(): String
    abstract fun dataClasses(): List<DataClass>
    protected abstract fun generateConcatenatedNames(prefixes: List<String> = listOf())

    fun dataClassTreeCode() = buildIndentedString {
        generateConcatenatedNames()
        val dataClasses = dataClasses()
        dataClasses.forEach {
            newline()
            it.appendCode(this)
            newline()
        }
    }
}