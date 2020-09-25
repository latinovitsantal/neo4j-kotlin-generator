package dev.latantal.neo4j.generator.util

open class IndentedStringBuilder(private val tab: String) {
    private val builder = StringBuilder()
    private var indentation = 0
    fun append(obj: Any) {
        builder.append(obj)
    }
    fun newline() {
        append('\n')
        append(tab.repeat(indentation))
    }
    fun indented(action: IndentedStringBuilder.() -> Unit) {
        indentation++
        action()
        indentation--
    }
    override fun toString() = builder.toString()
}

fun buildIndentedString(tab: String = "  ", build: IndentedStringBuilder.() -> Unit) =
    IndentedStringBuilder(tab).apply(build)

fun <T> treeString(
    root: T?,
    children: (T) -> Iterable<T>,
    string: (T) -> String = { it.toString() },
    tab: String = "  "
) = buildIndentedString {
    fun append(node: T) {
        append(string(node))
        indented {
            children(node).forEach {
                newline()
                append(it)
            }
        }
    }
    root?.let(::append)
}