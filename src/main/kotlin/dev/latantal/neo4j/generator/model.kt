package dev.latantal.neo4j.generator

import dev.latantal.neo4j.generator.KotlinModel.Maybe
import dev.latantal.neo4j.generator.KotlinModel.Primitive
import dev.latantal.neo4j.generator.RelationshipDirection.INCOMING
import dev.latantal.neo4j.generator.RelationshipDirection.OUTGOING
import dev.latantal.neo4j.generator.RelationshipRole.END
import dev.latantal.neo4j.generator.RelationshipRole.START
import dev.latantal.neo4j.generator.util.IndentedStringBuilder
import dev.latantal.neo4j.generator.util.delegateProvider
import kotlin.reflect.KClass

private typealias Rel = DomainRelationship<*>
private typealias Dir = RelationshipDirection
private typealias Nod = DomainNode<*, *>
private typealias Mul = Multiplicity


enum class RelationshipDirection(val arrowStart: String, val arrowEnd: String) {
    INCOMING("<-[", "]-"), OUTGOING("-[", "]->"),
}

enum class RelationshipRole {
    START, END,
}

enum class Multiplicity {
    ONE, ONE_OR_ZERO, MANY
}

interface DomainEntityAttribute {
    val name: String
    val kotlinModel: KotlinModel
    fun appendString(builder: IndentedStringBuilder)
    fun appendCypher(builder: CypherStringBuilder, varName: String)
}

data class Property<T: Any>(
    override val name: String,
    val type: KClass<T>,
    val isNullable: Boolean
): DomainEntityAttribute {
    override fun appendString(builder: IndentedStringBuilder) = builder.append("p $name: ${type.simpleName}")
    override fun appendCypher(builder: CypherStringBuilder, varName: String) {
        builder.append("`$name`:$varName.$name")
    }
    override val kotlinModel get() = if (isNullable) Maybe(Primitive(type)) else Primitive(type)
}

interface DomainEntityHolder<E: DomainEntity>: DomainEntityAttribute {
    override val name: String
    var entity: DomainEntity
    val multiplicity: Multiplicity
    override val kotlinModel get() = entity.model.dataClass.withMultiplicity(multiplicity)
}

interface DomainNodeHolder<N: Nod>: DomainEntityHolder<N> {
    override var entity: DomainEntity
        get() = node
        set(value) { node = value as N }
    var node: N
}

interface DomainRelationshipHolder<R: Rel>: DomainEntityHolder<R> {
    override var entity: DomainEntity
        get() = relationship
        set(value) { relationship = value as R }
    var relationship: R
}

data class NodeOfNode<N: Nod>(
    override val name: String,
    val relationshipType: Enum<*>,
    override val multiplicity: Multiplicity,
    override var node: N,
    val direction: RelationshipDirection,
) : DomainNodeHolder<N> {
    override fun appendString(builder: IndentedStringBuilder) {
        builder.append("n $name: ${direction.arrowStart}$relationshipType${direction.arrowEnd}")
        node.model.appendString(builder)
    }
    override fun appendCypher(builder: CypherStringBuilder, varName: String) {
        builder.run {
            val nodeVarName = variable()
            append("`$name`:")
            append('[')
            append("($varName)${direction.arrowStart}:$relationshipType${direction.arrowEnd}($nodeVarName)")
            append('|')
            node.model.appendCypher(builder, nodeVarName)
            append(']')
        }
    }
}

data class RelationshipOfNode<R: Rel>(
    override val name: String,
    override val multiplicity: Multiplicity,
    override var relationship: R,
    val direction: RelationshipDirection,
) : DomainRelationshipHolder<R> {
    override fun appendString(builder: IndentedStringBuilder) {
        builder.append("r $name: ${direction.arrowStart}[${relationship.model.name}]${direction.arrowEnd}")
        relationship.model.appendString(builder)
    }

    override fun appendCypher(builder: CypherStringBuilder, varName: String) {
        builder.run {
            val relVarName = variable()
            append("`$name`:")
            append('[')
            append("($varName) ()")
            append('|')
            relationship.model.appendCypher(builder, relVarName)
            append(']')
        }
    }
}

data class NodeOfRelationship<N: Nod>(
    override val name: String,
    override var node: N,
    val role: RelationshipRole
) : DomainNodeHolder<N> {
    override val multiplicity get() = Multiplicity.ONE
    override fun appendString(builder: IndentedStringBuilder) {
        builder.append("${role.name.first()} ${role.name.first().toLowerCase()} $name: ")
        node.model.appendString(builder)
    }

    override fun appendCypher(builder: CypherStringBuilder, varName: String) {
        TODO("Not yet implemented")
    }
}

data class EntityModel(val name: String) {
    val properties = mutableSetOf<Property<*>>()
    val propertyFactories = mutableSetOf<() -> Property<*>>()
    val entityHolders = mutableListOf<DomainEntityHolder<*>>()
    val entityHolderFactories = mutableListOf<() -> DomainEntityHolder<*>>()
    val attributes get() = properties + entityHolders
    val attributeFactories get() = propertyFactories + entityHolderFactories

    private val dataClassProperties = mutableMapOf<String, KotlinModel>()
    val dataClass = KotlinModel.DataClass(name, dataClassProperties)
    fun populateDataClass() { attributes.forEach { dataClassProperties[it.name] = it.kotlinModel } }
    fun populateDataClassTree() {
        populateDataClass()
        entityHolders.forEach { it.entity.model.populateDataClassTree() }
    }

    fun appendString(builder: IndentedStringBuilder) {
        builder.indented {
            attributes.forEach {
                newline()
                it.appendString(builder)
            }
        }
    }

    fun appendCypher(builder: CypherStringBuilder, varName: String) {
        builder.run {
            append('{')
            val attrsList = attributes.toList()
            attrsList.dropLast(1).forEach {
                it.appendCypher(builder, varName)
                append(',')
            }
            attrsList.lastOrNull()?.appendCypher(builder, varName)
            append('}')
        }
    }

}

@GraphQueryDsl
abstract class DomainEntity {
    var model = EntityModel(this::class.simpleName!!)

    protected fun property(type: KClass<*>, nullable: Boolean = false) = delegateProvider { prop ->
        { Property(prop.name, type, nullable).also(model.properties::add) }.also(model.propertyFactories::add)
    }
    protected fun nullableProperty(type: KClass<*>) = property(type)

    protected inline fun <reified T> property() = property(T::class)
    protected inline fun <reified T> nullableProperty() = nullableProperty(T::class)
}

abstract class DomainNode<L: Enum<*>, T: Enum<*>>(private val labels: Set<L>) : DomainEntity() {
    protected fun <N: Nod> relationship(type: T, mul: Mul, node: () -> N, direction: Dir) = delegateProvider { prop ->
        val create = { NodeOfNode(prop.name, type, mul, node(), direction).also(model.entityHolders::add) }
            .also(model.entityHolderFactories::add)
        ({ create().node })
    }

    protected fun <R: Rel> relationship(mul: Mul, relationship: () -> R, direction: Dir) = delegateProvider { prop ->
        val create = { RelationshipOfNode(prop.name, mul, relationship(), direction).also(model.entityHolders::add) }
            .also(model.entityHolderFactories::add)
        ({ create().relationship })
    }

    fun <N: Nod> incoming(type: T, mul: Mul, node: () -> N) = relationship(type, mul, node, INCOMING)
    protected fun <R: Rel> incoming(mul: Mul, rel: () -> R) = relationship(mul, rel, INCOMING)

    protected fun <N: Nod> outgoing(type: T, mul: Mul, node: () -> N) = relationship(type, mul, node, OUTGOING)
    protected fun <R: Rel> outgoing(mul: Mul, rel: () -> R) = relationship(mul, rel, OUTGOING)

    operator fun <N: Nod> N.invoke(build: N.() -> Unit) = apply(build)
    operator fun <R: Rel> R.invoke(build: R.() -> Unit) = apply(build)
}

@GraphQueryDsl
abstract class DomainRelationship<T: Enum<*>>(private val type: T): DomainEntity() {
    protected fun <N: Nod> node(node: () -> N, role: RelationshipRole) = delegateProvider { prop ->
        val create = { NodeOfRelationship(prop.name, node(), role).also(model.entityHolders::add) }
            .also(model.entityHolderFactories::add)
        ({ create().node })
    }

    protected fun <N: Nod> start(node: () -> N) = node(node, START)
    protected fun <N: Nod> end(node: () -> N) = node(node, END)

    operator fun <N: Nod> N.invoke(build: N.() -> Unit) = apply(build)
    operator fun <R: Rel> R.invoke(build: R.() -> Unit) = apply(build)
}

@DslMarker
annotation class GraphQueryDsl

class Schema(entityModels: List<EntityModel>) {
    private val entityModelsByName = entityModels.associateBy { it.name }.toMutableMap()
    val entityModels get() = entityModelsByName.values.toList()
    val dataClasses get() = entityModels.map { it.dataClass }

    init {
        entityModels.onEach { mergeEntityModel(it) }
            .forEach { it.populateDataClass() }
    }

    private fun mergeEntityModel(entityModel: EntityModel) {
        entityModel.attributeFactories.forEach { it() }
        entityModel.entityHolders.forEach { mergeEntityHolder(it) }
    }

    private fun mergeEntityHolder(entHolder: DomainEntityHolder<*>) {
        val entityModel = entHolder.entity.model
        entityModel.attributeFactories.forEach { it() }
        entityModelsByName[entityModel.name]?.let {
            entHolder.entity.model = it
            return
        }
        entityModelsByName[entityModel.name] = entityModel
        mergeEntityModel(entityModel)
    }
}
